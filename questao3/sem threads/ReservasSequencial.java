import java.util.Random;

/**
 * Q3 (versao SEQUENCIAL): reserva de passagens aereas, um cliente por vez.
 *
 * Usa o MESMO TAD (SistemaReservas) da versao com threads, com a mesma regra
 * de cada cliente: escolhe um voo, "paga" (sleep), reserva e, se o voo lotou,
 * tenta o proximo. A unica diferenca e que aqui NAO ha threads: um laco
 * processa os clientes em fila, e as latencias de pagamento se SOMAM.
 *
 * Compilar: javac *.java
 * Executar: java ReservasSequencial --batch -c 40 -v 3 -a 8 -l 200
 *
 * Opcoes iguais as de ReservasThreads (-c, -v, -a, -l), mais --seed N para
 * repetir exatamente o mesmo cenario.
 */
public class ReservasSequencial {

    private static final String[] NOMES = {
        "Ana", "Bruno", "Carla", "Diego", "Elisa", "Fabio", "Gabi", "Hugo",
        "Iara", "Joao", "Katia", "Lucas", "Maria", "Nando", "Olga", "Paulo"
    };

    public static void main(String[] args) throws Exception {
        int clientes = 30, nVoos = 3, assentos = 8, maxLatenciaMs = 800;
        long seed = System.nanoTime();
        boolean quieto = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-c": clientes = Integer.parseInt(args[++i]); break;
                case "-v": nVoos = Integer.parseInt(args[++i]); break;
                case "-a": assentos = Integer.parseInt(args[++i]); break;
                case "-l": maxLatenciaMs = Integer.parseInt(args[++i]); break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                case "--quieto": quieto = true; break;
                case "--batch": break; // aceito so por compatibilidade com ReservasThreads
                default:
                    System.out.print("Uso: java ReservasSequencial [-c N] [-v N] [-a N] [-l MS] [--seed N] [--quieto]\n");
                    System.exit(args[i].equals("--help") ? 0 : 1);
            }
        }

        // usarMutex = false: sem threads nao ha disputa, entao nao paga o custo do lock
        SistemaReservas sis = new SistemaReservas(nVoos, assentos, false);
        Random rnd = new Random(seed);
        int totalOk = 0, totalSemVaga = 0;

        System.out.printf("=== Reserva de passagens aereas (SEQUENCIAL) ===%n"
                        + "voos=%d | assentos/voo=%d | clientes=%d | latencia max=%d ms%n%n",
                sis.getNumVoos(), assentos, clientes, maxLatenciaMs);

        long t0 = System.nanoTime();

        for (int id = 1; id <= clientes; id++) {
            String nome = NOMES[(id - 1) % NOMES.length] + "-" + id;
            int n = sis.getNumVoos();
            int primeiro = rnd.nextInt(n);
            int latencia = maxLatenciaMs > 0 ? rnd.nextInt(maxLatenciaMs + 1) : 0;
            Voo desejado = sis.getVoo(primeiro);

            if (!quieto)
                System.out.printf("[Cliente %d] %s quer o voo %s (%s). Processando pagamento (%d ms)...%n",
                        id, nome, desejado.getCodigo(), desejado.getDestino(), latencia);

            if (latencia > 0) Thread.sleep(latencia); // aqui a espera BLOQUEIA o proximo cliente

            boolean reservou = false;
            for (int k = 0; k < n && !reservou; k++) {
                int iv = (primeiro + k) % n;
                Voo v = sis.getVoo(iv);
                SistemaReservas.Resposta r = sis.reservar(iv, nome);

                if (r.ok()) {
                    if (!quieto)
                        System.out.printf("[Cliente %d] %s RESERVOU assento %s no voo %s%n",
                                id, nome, Voo.rotulo(r.assento()), v.getCodigo());
                    totalOk++;
                    reservou = true;
                } else if (!quieto) {
                    System.out.printf("[Cliente %d] %s: %s (voo %s)%n",
                            id, nome, r.resultado().mensagem(), v.getCodigo());
                }
            }
            if (!reservou) {
                if (!quieto) System.out.printf("[Cliente %d] %s ficou SEM VAGA em todos os voos%n", id, nome);
                totalSemVaga++;
            }
        }

        long t1 = System.nanoTime();

        System.out.println("\n===== RESUMO FINAL =====");
        sis.listar();
        System.out.printf("Clientes com reserva: %d | sem vaga: %d%n", totalOk, totalSemVaga);
        int ruins = sis.auditar(true);
        System.out.printf("Tempo total (batch): %.3f s%n", (t1 - t0) / 1e9);

        System.exit(ruins > 0 ? 2 : 0);
    }
}
