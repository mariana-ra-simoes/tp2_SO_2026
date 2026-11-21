import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * TAD "Sistema de Reservas de Passagens Aereas".
 *
 * Contem SOMENTE a logica de dominio (voos, assentos, reservar, cancelar,
 * listar, auditar). NAO cria threads.
 *
 *  - Versao com threads (Pessoa 3): varias threads chamam estes metodos.
 *  - Versao sequencial (Pessoa 4):  um laco chama os MESMOS metodos, um
 *                                   cliente por vez (use usarMutex = false).
 *
 * Como a regra de negocio e a mesma nas duas versoes, a comparacao de tempo
 * (Speedup) fica justa.
 *
 * Sincronizacao: um ReentrantLock POR VOO. Threads em voos diferentes nao se
 * bloqueiam; threads no mesmo voo se serializam na secao critica.
 */
public class SistemaReservas {

    /** Resposta de uma operacao: resultado, indice do assento e nome do cliente envolvido. */
    public record Resposta(Resultado resultado, int assento, String cliente) {
        public boolean ok() { return resultado == Resultado.OK; }
    }

    private static final String[] DESTINOS = {
        "Sao Paulo", "Brasilia", "Rio de Janeiro", "Belem",
        "Recife", "Porto Velho", "Fortaleza", "Salvador"
    };

    private final List<Voo> voos = new ArrayList<>();

    /** true = protegido (correto); false = SEM protecao (so para demonstrar overbooking). */
    private final boolean usarMutex;

    /** Atraso artificial DENTRO da secao critica (0 = nenhum); aumenta a chance de a corrida aparecer quando usarMutex = false. */
    private volatile int atrasoCriticoUs = 0;

    public SistemaReservas(int nVoos, int assentosPorVoo, boolean usarMutex) {
        this.usarMutex = usarMutex;
        nVoos = Math.max(1, nVoos);
        assentosPorVoo = Math.max(1, assentosPorVoo);
        for (int i = 0; i < nVoos; i++) {
            voos.add(new Voo("AM" + (101 + i), DESTINOS[i % DESTINOS.length], assentosPorVoo));
        }
    }

    // ------------------------------------------------------------------
    // Configuracao / consultas simples
    // ------------------------------------------------------------------

    public void setAtrasoCriticoUs(int us) { this.atrasoCriticoUs = us; }
    public boolean isUsarMutex()           { return usarMutex; }
    public int getNumVoos()                { return voos.size(); }
    public Voo getVoo(int idx)             { return voos.get(idx); }

    /** Indice do voo pelo codigo, ou -1 se nao existir. */
    public int buscarVoo(String codigo) {
        for (int i = 0; i < voos.size(); i++)
            if (voos.get(i).codigo.equals(codigo)) return i;
        return -1;
    }

    private boolean indiceValido(int idx) {
        return idx >= 0 && idx < voos.size();
    }

    private void trava(Voo v)    { if (usarMutex) v.lock.lock(); }
    private void destrava(Voo v) { if (usarMutex) v.lock.unlock(); }

    private void dormeCritico() {
        int us = atrasoCriticoUs;
        if (us <= 0) return;
        try {
            TimeUnit.MICROSECONDS.sleep(us);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Operacoes
    // ------------------------------------------------------------------

    /**
     * SECAO CRITICA: "verificar se ha vaga" + "escolher assento" + "gravar"
     * precisam acontecer como UMA operacao indivisivel. Sem o lock, duas
     * threads podem verificar ao mesmo tempo, ver o mesmo assento livre e
     * gravar nele (condicao de corrida -> overbooking).
     */
    public Resposta reservar(int idxVoo, String cliente) {
        if (!indiceValido(idxVoo)) return new Resposta(Resultado.VOO_INEXISTENTE, -1, cliente);
        if (cliente == null || cliente.isEmpty()) return new Resposta(Resultado.NOME_INVALIDO, -1, cliente);

        Voo v = voos.get(idxVoo);
        Resultado res = Resultado.OK;
        int escolhido = -1;

        trava(v);
        try {
            for (int i = 0; i < v.capacidade; i++) {
                if (cliente.equals(v.assentos[i])) {
                    res = Resultado.JA_RESERVADO;
                    escolhido = i;
                    break;
                }
            }

            if (res == Resultado.OK) {
                if (v.ocupados >= v.capacidade) {
                    res = Resultado.LOTADO;
                } else {
                    for (int i = 0; i < v.capacidade; i++) {
                        if (v.assentos[i] == null) { escolhido = i; break; }
                    }
                    if (escolhido < 0) {
                        res = Resultado.LOTADO;
                    } else {
                        // Janela de corrida: entre "achei livre" e "gravei".
                        dormeCritico();

                        v.assentos[escolhido] = cliente;
                        v.ocupados++;
                    }
                }
            }
        } finally {
            destrava(v);
        }

        if (res == Resultado.OK) v.reservasOk.incrementAndGet();
        return new Resposta(res, escolhido, cliente);
    }

    /** Cancela a reserva do cliente no voo. */
    public Resposta cancelar(int idxVoo, String cliente) {
        if (!indiceValido(idxVoo)) return new Resposta(Resultado.VOO_INEXISTENTE, -1, cliente);
        if (cliente == null || cliente.isEmpty()) return new Resposta(Resultado.NOME_INVALIDO, -1, cliente);

        Voo v = voos.get(idxVoo);
        Resultado res = Resultado.NAO_ENCONTRADO;
        int achado = -1;

        trava(v);
        try {
            for (int i = 0; i < v.capacidade; i++) {
                if (cliente.equals(v.assentos[i])) {
                    v.assentos[i] = null;
                    v.ocupados--;
                    achado = i;
                    res = Resultado.OK;
                    break;
                }
            }
        } finally {
            destrava(v);
        }

        if (res == Resultado.OK) v.cancelamentosOk.incrementAndGet();
        return new Resposta(res, achado, cliente);
    }

    /** Cancela uma reserva aleatoria do voo (usado pela thread de cancelamento). */
    public Resposta cancelarAleatorio(int idxVoo, Random rnd) {
        if (!indiceValido(idxVoo)) return new Resposta(Resultado.VOO_INEXISTENTE, -1, null);

        Voo v = voos.get(idxVoo);
        Resultado res = Resultado.NAO_ENCONTRADO;
        int achado = -1;
        String nome = null;

        trava(v);
        try {
            int total = 0;
            for (int i = 0; i < v.capacidade; i++)
                if (v.assentos[i] != null) total++;

            if (total > 0) {
                int alvo = rnd.nextInt(total);
                for (int i = 0; i < v.capacidade; i++) {
                    if (v.assentos[i] == null) continue;
                    if (alvo-- == 0) {
                        nome = v.assentos[i];
                        v.assentos[i] = null;
                        v.ocupados--;
                        achado = i;
                        res = Resultado.OK;
                        break;
                    }
                }
            }
        } finally {
            destrava(v);
        }

        if (res == Resultado.OK) v.cancelamentosOk.incrementAndGet();
        return new Resposta(res, achado, nome);
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    public int ocupados(int idxVoo) {
        if (!indiceValido(idxVoo)) return -1;
        Voo v = voos.get(idxVoo);
        trava(v);
        try {
            return v.ocupados;
        } finally {
            destrava(v);
        }
    }

    /**
     * Monta o mapa de um voo num StringBuilder (sob o lock) e imprime depois,
     * de uma vez so, para que a saida de um voo nao se misture com a de
     * outras threads.
     */
    public void listar() {
        for (Voo v : voos) {
            StringBuilder sb = new StringBuilder();
            trava(v);
            try {
                sb.append(String.format("=== Voo %s -> %s | %d/%d assentos ocupados ===%n",
                        v.codigo, v.destino, v.ocupados, v.capacidade));
                for (int fila = 0; fila * Voo.COLUNAS < v.capacidade; fila++) {
                    sb.append(String.format("  %2d |", fila + 1));
                    for (int c = 0; c < Voo.COLUNAS; c++) {
                        int idx = fila * Voo.COLUNAS + c;
                        if (idx >= v.capacidade) break;
                        String n = v.assentos[idx];
                        String curto = (n == null) ? "--" : (n.length() > 8 ? n.substring(0, 8) : n);
                        sb.append(String.format(" %c:%-8s", (char) ('A' + c), curto));
                    }
                    sb.append(System.lineSeparator());
                }
            } finally {
                destrava(v);
            }
            System.out.print(sb);
        }
    }

    /**
     * Confere se reservas confirmadas == assentos realmente ocupados e
     * <= capacidade. Retorna quantos voos estao inconsistentes (0 = tudo certo).
     */
    public int auditar(boolean verbose) {
        int ruins = 0;
        for (Voo v : voos) {
            int real = 0;
            int contador;
            trava(v);
            try {
                for (int j = 0; j < v.capacidade; j++)
                    if (v.assentos[j] != null) real++;
                contador = v.ocupados;
            } finally {
                destrava(v);
            }

            int liquido = v.reservasOk.get() - v.cancelamentosOk.get();
            boolean ruim = (liquido != real) || (liquido > v.capacidade) || (contador > v.capacidade);
            if (ruim) ruins++;

            if (verbose) {
                System.out.printf("[Auditoria] Voo %s: capacidade=%d | clientes com reserva confirmada=%d | "
                                + "assentos realmente ocupados=%d | contador=%d -> %s%n",
                        v.codigo, v.capacidade, liquido, real, contador,
                        ruim ? "INCONSISTENTE (overbooking / assento duplicado)" : "OK");
            }
        }
        return ruins;
    }
}
