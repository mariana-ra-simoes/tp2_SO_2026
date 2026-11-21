import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q3 (versao COM threads): reserva de passagens aereas.
 *
 * Threads do programa:
 *   - main              : menu interativo (le comandos do usuario)
 *   - N threads cliente : cada uma simula um cliente (pagamento + reserva)
 *   - 1 thread de cancelamento (opcional): cancela reservas aleatorias
 *   - 1 thread monitor (opcional): imprime a ocupacao dos voos periodicamente
 *
 * Compilar: javac *.java        Executar: java ReservasThreads --help
 */
public class ReservasThreads {

    // ---------------- estado global ----------------
    private static SistemaReservas sis;
    private static volatile boolean rodando = true;      // false => threads de fundo devem encerrar
    private static final AtomicInteger proxId = new AtomicInteger();
    private static final AtomicInteger totalOk = new AtomicInteger();
    private static final AtomicInteger totalSemVaga = new AtomicInteger();
    private static int maxLatenciaMs = 800;              // "latencia do pagamento" maxima

    // lista de threads cliente criadas (para dar join no final)
    private static final List<Thread> threadsClientes = Collections.synchronizedList(new ArrayList<>());

    private static final String[] NOMES = {
        "Ana", "Bruno", "Carla", "Diego", "Elisa", "Fabio", "Gabi", "Hugo",
        "Iara", "Joao", "Katia", "Lucas", "Maria", "Nando", "Olga", "Paulo"
    };

    // ---------------- thread CLIENTE ----------------
    private static class Cliente implements Runnable {
        private final int id;
        private final String nome;

        Cliente(int id, String nome) {
            this.id = id;
            this.nome = nome;
        }

        @Override
        public void run() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int n = sis.getNumVoos();
            int primeiro = rnd.nextInt(n);
            int latencia = maxLatenciaMs > 0 ? rnd.nextInt(maxLatenciaMs + 1) : 0;
            Voo desejado = sis.getVoo(primeiro);

            System.out.printf("[Thread %d] Cliente %s quer o voo %s (%s). Processando pagamento (%d ms)...%n",
                    id, nome, desejado.getCodigo(), desejado.getDestino(), latencia);

            // latencia do pagamento: FORA da secao critica
            if (latencia > 0) {
                try {
                    Thread.sleep(latencia);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            for (int k = 0; k < n; k++) {
                int iv = (primeiro + k) % n;
                Voo v = sis.getVoo(iv);
                SistemaReservas.Resposta r = sis.reservar(iv, nome);

                if (r.ok()) {
                    System.out.printf("[Thread %d] Cliente %s RESERVOU assento %s no voo %s%n",
                            id, nome, Voo.rotulo(r.assento()), v.getCodigo());
                    totalOk.incrementAndGet();
                    return;
                }
                if (r.resultado() == Resultado.LOTADO) {
                    System.out.printf("[Thread %d] Voo %s lotado para %s%s%n",
                            id, v.getCodigo(), nome, k + 1 < n ? ", tentando outro voo..." : "");
                } else {
                    System.out.printf("[Thread %d] Cliente %s: %s (voo %s)%n",
                            id, nome, r.resultado().mensagem(), v.getCodigo());
                }
            }

            System.out.printf("[Thread %d] Cliente %s ficou SEM VAGA em todos os voos%n", id, nome);
            totalSemVaga.incrementAndGet();
        }
    }

    private static void spawnClientes(int quantidade) {
        for (int i = 0; i < quantidade; i++) {
            int id = proxId.incrementAndGet();
            String nome = NOMES[(id - 1) % NOMES.length] + "-" + id;
            Thread t = new Thread(new Cliente(id, nome), "Cliente-" + id);
            threadsClientes.add(t);
            t.start();
        }
    }

    private static void joinClientes() throws InterruptedException {
        // Nenhuma thread nova e criada durante o join (o menu ja encerrou).
        List<Thread> copia;
        synchronized (threadsClientes) {
            copia = new ArrayList<>(threadsClientes);
        }
        for (Thread t : copia) t.join();
    }

    // ---------------- thread de CANCELAMENTO ----------------
    private static class Cancelamento implements Runnable {
        @Override
        public void run() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            while (rodando) {
                try {
                    Thread.sleep(1500 + rnd.nextInt(2000));
                } catch (InterruptedException e) {
                    break; // main nos acordou para encerrar
                }
                if (!rodando) break;

                int iv = rnd.nextInt(sis.getNumVoos());
                SistemaReservas.Resposta r = sis.cancelarAleatorio(iv, rnd);
                if (r.ok()) {
                    System.out.printf("[Thread-Cancel] Cliente %s CANCELOU o assento %s do voo %s%n",
                            r.cliente(), Voo.rotulo(r.assento()), sis.getVoo(iv).getCodigo());
                }
            }
        }
    }

    // ---------------- thread MONITOR ----------------
    private static class Monitor implements Runnable {
        @Override
        public void run() {
            while (rodando) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
                if (!rodando) break;

                StringBuilder sb = new StringBuilder("[Monitor] ----- ocupacao -----\n");
                for (int i = 0; i < sis.getNumVoos(); i++) {
                    Voo v = sis.getVoo(i);
                    int oc = sis.ocupados(i);
                    int barra = 20;
                    int cheios = v.getCapacidade() > 0 ? oc * barra / v.getCapacidade() : 0;
                    cheios = Math.max(0, Math.min(barra, cheios));

                    sb.append(String.format("[Monitor] %-6s %-15s [", v.getCodigo(), v.getDestino()));
                    for (int b = 0; b < barra; b++) sb.append(b < cheios ? '#' : '.');
                    sb.append(String.format("] %d/%d%n", oc, v.getCapacidade()));
                }
                System.out.print(sb);
            }
        }
    }

    // ---------------- menu ----------------
    private static String leLinha(BufferedReader in, String prompt) throws IOException {
        System.out.print(prompt);
        System.out.flush();
        String s = in.readLine();
        return s == null ? null : s.trim();
    }

    private static void menu() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("\n===== MENU =====\n"
                    + " 1) Reservar assento (manual)\n"
                    + " 2) Cancelar reserva (manual)\n"
                    + " 3) Listar voos e assentos\n"
                    + " 4) Adicionar clientes simulados (novas threads)\n"
                    + " 5) Auditar consistencia (detecta overbooking)\n"
                    + " 0) Sair\n");
            String linha = leLinha(in, "> ");
            if (linha == null) return; // EOF

            switch (linha) {
                case "0":
                    return;
                case "1":
                case "2": {
                    String nome = leLinha(in, "Nome do cliente: ");
                    if (nome == null) return;
                    if (nome.isEmpty()) break;
                    String cod = leLinha(in, "Codigo do voo (ex.: AM101): ");
                    if (cod == null) return;

                    int iv = sis.buscarVoo(cod);
                    boolean reservar = linha.equals("1");
                    SistemaReservas.Resposta r = reservar ? sis.reservar(iv, nome) : sis.cancelar(iv, nome);
                    if (r.ok()) {
                        System.out.printf("[Main] %s %s: assento %s, voo %s%n", nome,
                                reservar ? "RESERVOU" : "CANCELOU", Voo.rotulo(r.assento()), cod);
                    } else {
                        System.out.println("[Main] Falhou: " + r.resultado().mensagem());
                    }
                    break;
                }
                case "3":
                    sis.listar();
                    break;
                case "4": {
                    String q = leLinha(in, "Quantos clientes? ");
                    if (q == null) return;
                    try {
                        int qtd = Integer.parseInt(q);
                        if (qtd > 0) {
                            System.out.printf("[Main] Criando %d threads cliente...%n", qtd);
                            spawnClientes(qtd);
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Numero invalido.");
                    }
                    break;
                }
                case "5": {
                    int ruins = sis.auditar(true);
                    System.out.println("[Main] Voos inconsistentes: " + ruins);
                    break;
                }
                default:
                    System.out.println("Opcao invalida.");
            }
        }
    }

    // ---------------- main ----------------
    private static void uso() {
        System.out.print(
                "Uso: java ReservasThreads [opcoes]\n"
              + "  -c N             clientes simulados iniciais (threads)   [30]\n"
              + "  -v N             numero de voos                          [3]\n"
              + "  -a N             assentos por voo                        [8]\n"
              + "  -l MS            latencia maxima do pagamento em ms      [800]\n"
              + "  --batch          sem menu: roda, espera as threads e mostra o tempo\n"
              + "  --cancelamento   liga a thread de cancelamento (padrao: ligada no modo interativo)\n"
              + "  --monitor        liga a thread monitor (padrao: ligada no modo interativo)\n"
              + "  --sem-mutex      DESLIGA a sincronizacao (demonstra overbooking)\n"
              + "  --atraso-critico US  atraso dentro da secao critica (torna a corrida mais provavel)\n");
    }

    public static void main(String[] args) throws Exception {
        int clientes = 30, nVoos = 3, assentos = 8, atraso = 0;
        boolean usarMutex = true, batch = false;
        int cancelOn = -1, monitorOn = -1; // -1 = decidir pelo modo

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-c": clientes = Integer.parseInt(args[++i]); break;
                case "-v": nVoos = Integer.parseInt(args[++i]); break;
                case "-a": assentos = Integer.parseInt(args[++i]); break;
                case "-l": maxLatenciaMs = Integer.parseInt(args[++i]); break;
                case "--atraso-critico": atraso = Integer.parseInt(args[++i]); break;
                case "--batch": batch = true; break;
                case "--sem-mutex": usarMutex = false; break;
                case "--cancelamento": cancelOn = 1; break;
                case "--monitor": monitorOn = 1; break;
                default:
                    uso();
                    System.exit(args[i].equals("--help") ? 0 : 1);
            }
        }
        if (cancelOn < 0) cancelOn = batch ? 0 : 1;
        if (monitorOn < 0) monitorOn = batch ? 0 : 1;

        sis = new SistemaReservas(nVoos, assentos, usarMutex);
        sis.setAtrasoCriticoUs(atraso);

        System.out.printf("=== Reserva de passagens aereas (COM threads) ===%n"
                        + "voos=%d | assentos/voo=%d | clientes iniciais=%d | sincronizacao=%s%n%n",
                sis.getNumVoos(), assentos, clientes, usarMutex ? "LOCK por voo" : "DESLIGADA (inseguro!)");

        long t0 = System.nanoTime();

        Thread thCancel = null, thMonitor = null;
        if (cancelOn == 1) {
            thCancel = new Thread(new Cancelamento(), "Cancelamento");
            thCancel.start();
        }
        if (monitorOn == 1) {
            thMonitor = new Thread(new Monitor(), "Monitor");
            thMonitor.start();
        }

        spawnClientes(clientes);

        if (!batch) {
            menu();
            System.out.println("[Main] Encerrando: aguardando threads cliente em andamento...");
        }
        joinClientes();
        long t1 = System.nanoTime();

        rodando = false;
        if (thCancel != null) { thCancel.interrupt(); thCancel.join(); }
        if (thMonitor != null) { thMonitor.interrupt(); thMonitor.join(); }

        System.out.println("\n===== RESUMO FINAL =====");
        sis.listar();
        System.out.printf("Clientes com reserva: %d | sem vaga: %d%n", totalOk.get(), totalSemVaga.get());
        int ruins = sis.auditar(true);
        if (batch) System.out.printf("Tempo total (batch): %.3f s%n", (t1 - t0) / 1e9);

        System.exit(ruins > 0 ? 2 : 0);
    }
}
