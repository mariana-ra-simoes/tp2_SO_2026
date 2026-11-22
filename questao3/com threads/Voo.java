import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Um voo: assentos compartilhados entre as threads + o lock que os protege.
 * Parte do TAD compartilhado (Pessoas 3 e 4).
 *
 * Os campos assentos[] e ocupados so devem ser acessados com o lock do voo
 * (quando o sistema estiver em modo protegido). Os contadores de auditoria sao
 * AtomicInteger, entao ficam corretos mesmo no modo sem protecao.
 */
public class Voo {
    public static final int COLUNAS = 6; // assentos A..F por fileira

    final String codigo;
    final String destino;
    final int capacidade;

    final String[] assentos;   // null = livre; senao, nome do cliente
    int ocupados;              // contador "oficial" do voo

    final ReentrantLock lock = new ReentrantLock();

    // Estatisticas para auditoria
    final AtomicInteger reservasOk = new AtomicInteger();
    final AtomicInteger cancelamentosOk = new AtomicInteger();

    Voo(String codigo, String destino, int capacidade) {
        this.codigo = codigo;
        this.destino = destino;
        this.capacidade = capacidade;
        this.assentos = new String[capacidade];
    }

    public String getCodigo()  { return codigo; }
    public String getDestino() { return destino; }
    public int getCapacidade() { return capacidade; }

    /** Rotulo do assento: indice 0 -> "1A", 7 -> "2B". */
    public static String rotulo(int idx) {
        return (idx / COLUNAS + 1) + "" + (char) ('A' + idx % COLUNAS);
    }
}
