import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Q3 (versao COM threads) -- INTERFACE GRAFICA (Swing).
 *
 * Reaproveita o TAD SistemaReservas (o mesmo da versao de console) e mostra:
 *   - o mapa de assentos de cada voo, em tempo real (cores = o que cada thread acabou de fazer);
 *   - uma tabela com o ESTADO de cada thread cliente (pagando, tentando voo X, reservou, sem vaga);
 *   - um log com a acao de cada thread;
 *   - botoes: adicionar clientes, reservar/cancelar manualmente, auditar, ligar/desligar a thread de cancelamento.
 *
 * Compilar: javac *.java        Executar: java ReservasGUI
 */
public class ReservasGUI {

    /** Parametros escolhidos na janela inicial. */
    record Config(int voos, int assentos, int clientesIniciais, int latenciaMs, int atrasoCriticoUs, boolean usarMutex) { }

    // ---------------- cores dos assentos ----------------
    private static final Color LIVRE    = new Color(0xC8E6C9); // verde claro
    private static final Color OCUPADO  = new Color(0xEF9A9A); // vermelho claro
    private static final Color NOVO     = new Color(0xFFEB3B); // amarelo: acabou de ser reservado
    private static final Color LIBERADO = new Color(0x64B5F6); // azul: acabou de ser cancelado
    private static final long FLASH_MS = 700;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final String[] NOMES = {
        "Ana", "Bruno", "Carla", "Diego", "Elisa", "Fabio", "Gabi", "Hugo",
        "Iara", "Joao", "Katia", "Lucas", "Maria", "Nando", "Olga", "Paulo"
    };

    // ---------------- estado ----------------
    private final Config cfg;
    private final SistemaReservas sis;
    private volatile int maxLatenciaMs;
    private volatile boolean rodando = true;
    private final AtomicInteger proxId = new AtomicInteger();
    private final AtomicInteger totalOk = new AtomicInteger();
    private final AtomicInteger totalSemVaga = new AtomicInteger();
    private final AtomicInteger ativos = new AtomicInteger();
    private Thread thCancel;                               // so mexe nela a EDT

    private final JPanel root = new JPanel(new BorderLayout(6, 6));
    private final List<VooPanel> vooPanels = new ArrayList<>();
    private final ThreadsModel threadsModel = new ThreadsModel();
    private JTable tabelaThreads;
    private final JTextArea logArea = new JTextArea();
    private final JLabel statsLabel = new JLabel();
    private JCheckBox chkCancel;
    private final Timer timer;

    // ======================================================================
    // Construcao da interface
    // ======================================================================
    ReservasGUI(Config cfg) {
        this.cfg = cfg;
        this.maxLatenciaMs = cfg.latenciaMs();
        this.sis = new SistemaReservas(cfg.voos(), cfg.assentos(), cfg.usarMutex());
        this.sis.setAtrasoCriticoUs(cfg.atrasoCriticoUs());

        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(montaControles(), BorderLayout.NORTH);
        root.add(montaCentro(), BorderLayout.CENTER);

        // "Thread monitor" da interface: a cada 100 ms redesenha o estado dos voos.
        timer = new Timer(100, e -> tick());
    }

    JComponent getRoot() { return root; }

    private JComponent montaControles() {
        JPanel linha1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));

        JSpinner spQtd = new JSpinner(new SpinnerNumberModel(10, 1, 500, 1));
        JButton btnAdd = new JButton("Adicionar clientes (threads)");
        btnAdd.addActionListener(e -> spawnClientes((Integer) spQtd.getValue()));

        JSpinner spLat = new JSpinner(new SpinnerNumberModel(cfg.latenciaMs(), 0, 5000, 100));
        spLat.addChangeListener(e -> maxLatenciaMs = (Integer) spLat.getValue());

        chkCancel = new JCheckBox("Thread de cancelamento", true);
        chkCancel.addActionListener(e -> ligarCancelamento(chkCancel.isSelected()));

        JButton btnRes = new JButton("Reservar...");
        btnRes.addActionListener(e -> dialogoManual(true));
        JButton btnCan = new JButton("Cancelar...");
        btnCan.addActionListener(e -> dialogoManual(false));
        JButton btnAud = new JButton("Auditar");
        btnAud.addActionListener(e -> auditar());
        JButton btnLimpa = new JButton("Limpar log");
        btnLimpa.addActionListener(e -> logArea.setText(""));

        linha1.add(new JLabel("Clientes:"));
        linha1.add(spQtd);
        linha1.add(btnAdd);
        linha1.add(new JLabel("  Latencia max. do pagamento (ms):"));
        linha1.add(spLat);
        linha1.add(chkCancel);
        linha1.add(btnRes);
        linha1.add(btnCan);
        linha1.add(btnAud);
        linha1.add(btnLimpa);

        JLabel modo = new JLabel(cfg.usarMutex()
                ? "<html><b>Sincronizacao: LOCK por voo</b> (correto)</html>"
                : "<html><b>Sincronizacao: DESLIGADA</b> (inseguro - vai ocorrer overbooking)</html>");
        modo.setForeground(cfg.usarMutex() ? new Color(0x2E7D32) : Color.RED);

        JLabel legenda = new JLabel("<html>Legenda: "
                + cor(LIVRE) + " livre &nbsp; " + cor(OCUPADO) + " ocupado &nbsp; "
                + cor(NOVO) + " acabou de reservar &nbsp; " + cor(LIBERADO) + " acabou de cancelar</html>");

        JPanel linha2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        linha2.add(modo);
        linha2.add(new JLabel("  |  "));
        linha2.add(legenda);
        linha2.add(new JLabel("  |  "));
        linha2.add(statsLabel);

        JPanel p = new JPanel(new GridLayout(2, 1));
        p.add(linha1);
        p.add(linha2);
        return p;
    }

    private static String cor(Color c) {
        return String.format("<font color='#%06X'>&#9608;&#9608;</font>", c.getRGB() & 0xFFFFFF);
    }

    private JComponent montaCentro() {
        // ---- esquerda: voos ----
        JPanel grade = new JPanel(new GridLayout(0, 1, 8, 8));
        for (int i = 0; i < sis.getNumVoos(); i++) {
            VooPanel vp = new VooPanel(i);
            vooPanels.add(vp);
            grade.add(vp);
        }
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(grade, BorderLayout.NORTH);
        JScrollPane scrollVoos = new JScrollPane(wrapper);
        scrollVoos.setBorder(BorderFactory.createTitledBorder("Voos e assentos (memoria compartilhada)"));
        scrollVoos.getVerticalScrollBar().setUnitIncrement(16);

        // ---- direita em cima: tabela de threads ----
        tabelaThreads = new JTable(threadsModel);
        tabelaThreads.setFillsViewportHeight(true);
        tabelaThreads.getColumnModel().getColumn(0).setPreferredWidth(80);
        tabelaThreads.getColumnModel().getColumn(1).setPreferredWidth(100);
        tabelaThreads.getColumnModel().getColumn(2).setPreferredWidth(260);
        tabelaThreads.setDefaultRenderer(Object.class, new EstadoRenderer());
        JScrollPane scrollThreads = new JScrollPane(tabelaThreads);
        scrollThreads.setBorder(BorderFactory.createTitledBorder("Threads cliente (estado em tempo real)"));

        // ---- direita embaixo: log ----
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollLog = new JScrollPane(logArea);
        scrollLog.setBorder(BorderFactory.createTitledBorder("Log das threads"));

        JSplitPane direita = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollThreads, scrollLog);
        direita.setResizeWeight(0.5);
        direita.setDividerLocation(300);

        JSplitPane principal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollVoos, direita);
        principal.setResizeWeight(0.55);
        principal.setDividerLocation(720);
        return principal;
    }

    // ======================================================================
    // Painel de um voo
    // ======================================================================
    private class VooPanel extends JPanel {
        private final int idx;
        private final Voo voo;
        private final JLabel titulo = new JLabel();
        private final JProgressBar barra;
        private final JLabel[] cel;
        private final String[] prev;
        private final long[] mudouEm;
        private final int[] tipo;          // 1 = acabou de reservar, 2 = acabou de cancelar
        private final Color[] corAtual;

        VooPanel(int idx) {
            super(new BorderLayout(4, 4));
            this.idx = idx;
            this.voo = sis.getVoo(idx);
            int cap = voo.getCapacidade();

            cel = new JLabel[cap];
            prev = new String[cap];
            mudouEm = new long[cap];
            tipo = new int[cap];
            corAtual = new Color[cap];

            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.GRAY),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6)));

            barra = new JProgressBar(0, cap);
            barra.setStringPainted(true);

            JPanel topo = new JPanel(new BorderLayout(4, 4));
            topo.add(titulo, BorderLayout.NORTH);
            topo.add(barra, BorderLayout.SOUTH);
            add(topo, BorderLayout.NORTH);

            JPanel grid = new JPanel(new GridLayout(0, Voo.COLUNAS, 3, 3));
            for (int i = 0; i < cap; i++) {
                JLabel c = new JLabel(texto(i, null), JLabel.CENTER);
                c.setOpaque(true);
                c.setBackground(LIVRE);
                c.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
                c.setFont(c.getFont().deriveFont(Font.PLAIN, 11f));
                c.setPreferredSize(new Dimension(70, 36));
                c.setToolTipText(Voo.rotulo(i) + " - livre");
                cel[i] = c;
                corAtual[i] = LIVRE;
                grid.add(c);
            }
            add(grid, BorderLayout.CENTER);
            refresh();
        }

        private String texto(int i, String nome) {
            String curto = nome == null ? "--" : (nome.length() > 9 ? nome.substring(0, 9) : nome);
            return "<html><div style='text-align:center'><b>" + Voo.rotulo(i) + "</b><br>" + curto + "</div></html>";
        }

        void refresh() {
            String[] snap = sis.snapshot(idx);
            long agora = System.currentTimeMillis();
            int reais = 0;

            for (int i = 0; i < snap.length; i++) {
                String n = snap[i];
                if (n != null) reais++;

                if (!Objects.equals(n, prev[i])) {
                    tipo[i] = (n != null) ? 1 : 2;
                    mudouEm[i] = agora;
                    prev[i] = n;
                    cel[i].setText(texto(i, n));
                    cel[i].setToolTipText(Voo.rotulo(i) + (n == null ? " - livre" : " - " + n));
                }

                boolean recente = agora - mudouEm[i] < FLASH_MS;
                Color c;
                if (n == null) c = (recente && tipo[i] == 2) ? LIBERADO : LIVRE;
                else           c = (recente && tipo[i] == 1) ? NOVO : OCUPADO;

                if (!c.equals(corAtual[i])) {
                    cel[i].setBackground(c);
                    corAtual[i] = c;
                }
            }

            int confirmadas = sis.confirmadas(idx);
            boolean ruim = confirmadas != reais || confirmadas > voo.getCapacidade();

            titulo.setText("<html><b>" + voo.getCodigo() + "</b> &rarr; " + voo.getDestino()
                    + " &nbsp;|&nbsp; assentos ocupados: " + reais + "/" + voo.getCapacidade()
                    + " &nbsp;|&nbsp; reservas confirmadas: " + confirmadas
                    + (ruim ? " &nbsp; <b>&#9888; OVERBOOKING / ASSENTO DUPLICADO</b>" : "")
                    + "</html>");
            titulo.setForeground(ruim ? Color.RED : Color.BLACK);
            barra.setValue(reais);
            barra.setString(reais + " / " + voo.getCapacidade());
        }
    }

    // ======================================================================
    // Tabela de threads
    // ======================================================================
    private static class ThreadsModel extends AbstractTableModel {
        private final List<String[]> linhas = new ArrayList<>();   // {thread, cliente, estado}
        private final Map<Integer, Integer> posicao = new HashMap<>();
        private static final String[] COLS = {"Thread", "Cliente", "Estado"};

        void add(int id, String nome) {
            posicao.put(id, linhas.size());
            linhas.add(new String[] {"Thread " + id, nome, "Criada"});
            fireTableRowsInserted(linhas.size() - 1, linhas.size() - 1);
        }

        void set(int id, String estado) {
            Integer p = posicao.get(id);
            if (p == null) return;
            linhas.get(p)[2] = estado;
            fireTableRowsUpdated(p, p);
        }

        @Override public int getRowCount()               { return linhas.size(); }
        @Override public int getColumnCount()            { return COLS.length; }
        @Override public String getColumnName(int c)     { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) { return linhas.get(r)[c]; }
    }

    private static class EstadoRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            if (!sel) {
                String estado = String.valueOf(t.getModel().getValueAt(row, 2));
                Color bg = Color.WHITE;
                if (estado.startsWith("Pagando"))        bg = new Color(0xFFF9C4);
                else if (estado.startsWith("Tentando"))  bg = new Color(0xBBDEFB);
                else if (estado.startsWith("RESERVOU"))  bg = new Color(0xC8E6C9);
                else if (estado.startsWith("SEM VAGA"))  bg = new Color(0xFFCDD2);
                c.setBackground(bg);
                c.setForeground(Color.BLACK);
            }
            return c;
        }
    }

    // ======================================================================
    // Log e estado das threads (podem ser chamados de qualquer thread)
    // ======================================================================
    private void log(String msg) {
        String linha = "[" + HORA.format(LocalTime.now()) + "] " + msg + "\n";
        if (SwingUtilities.isEventDispatchThread()) appendLog(linha);
        else SwingUtilities.invokeLater(() -> appendLog(linha));
    }

    private void appendLog(String linha) {
        logArea.append(linha);
        int len = logArea.getDocument().getLength();
        if (len > 200_000) logArea.replaceRange("", 0, 50_000);   // nao deixa o log crescer sem limite
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void estado(int id, String estado) {
        SwingUtilities.invokeLater(() -> threadsModel.set(id, estado));
    }

    // ======================================================================
    // Threads cliente
    // ======================================================================
    private class Cliente implements Runnable {
        private final int id;
        private final String nome;

        Cliente(int id, String nome) {
            this.id = id;
            this.nome = nome;
        }

        @Override
        public void run() {
            try {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                int n = sis.getNumVoos();
                int primeiro = rnd.nextInt(n);
                int max = maxLatenciaMs;
                int latencia = max > 0 ? rnd.nextInt(max + 1) : 0;
                Voo desejado = sis.getVoo(primeiro);

                estado(id, "Pagando (" + latencia + " ms) para o voo " + desejado.getCodigo());
                log(String.format("[Thread %d] Cliente %s quer o voo %s (%s). Processando pagamento (%d ms)...",
                        id, nome, desejado.getCodigo(), desejado.getDestino(), latencia));

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
                    estado(id, "Tentando reservar no voo " + v.getCodigo());
                    SistemaReservas.Resposta r = sis.reservar(iv, nome);

                    if (r.ok()) {
                        String rot = Voo.rotulo(r.assento());
                        estado(id, "RESERVOU assento " + rot + " no voo " + v.getCodigo());
                        log(String.format("[Thread %d] Cliente %s RESERVOU assento %s no voo %s",
                                id, nome, rot, v.getCodigo()));
                        totalOk.incrementAndGet();
                        return;
                    }
                    if (r.resultado() == Resultado.LOTADO) {
                        log(String.format("[Thread %d] Voo %s lotado para %s%s", id, v.getCodigo(), nome,
                                k + 1 < n ? ", tentando outro voo..." : ""));
                    } else {
                        log(String.format("[Thread %d] Cliente %s: %s (voo %s)",
                                id, nome, r.resultado().mensagem(), v.getCodigo()));
                    }
                }

                estado(id, "SEM VAGA em todos os voos");
                log(String.format("[Thread %d] Cliente %s ficou SEM VAGA em todos os voos", id, nome));
                totalSemVaga.incrementAndGet();
            } finally {
                ativos.decrementAndGet();
            }
        }
    }

    void spawnClientes(int quantidade) {
        for (int i = 0; i < quantidade; i++) {
            int id = proxId.incrementAndGet();
            String nome = NOMES[(id - 1) % NOMES.length] + "-" + id;
            threadsModel.add(id, nome);              // chamado na EDT
            ativos.incrementAndGet();
            Thread t = new Thread(new Cliente(id, nome), "Cliente-" + id);
            t.setDaemon(true);
            t.start();
        }
        log("[Main] Criadas " + quantidade + " threads cliente");
        if (tabelaThreads != null && threadsModel.getRowCount() > 0) {
            tabelaThreads.scrollRectToVisible(tabelaThreads.getCellRect(threadsModel.getRowCount() - 1, 0, true));
        }
    }

    // ======================================================================
    // Thread de cancelamento (liga/desliga pelo checkbox)
    // ======================================================================
    private void ligarCancelamento(boolean ligar) {
        if (ligar && thCancel == null) {
            thCancel = new Thread(this::loopCancelamento, "Cancelamento");
            thCancel.setDaemon(true);
            thCancel.start();
            log("[Main] Thread de cancelamento LIGADA");
        } else if (!ligar && thCancel != null) {
            thCancel.interrupt();                    // acorda o sleep e encerra o laco
            thCancel = null;
            log("[Main] Thread de cancelamento DESLIGADA");
        }
    }

    private void loopCancelamento() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (rodando && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1500 + rnd.nextInt(2000));
            } catch (InterruptedException e) {
                break;
            }
            int iv = rnd.nextInt(sis.getNumVoos());
            SistemaReservas.Resposta r = sis.cancelarAleatorio(iv, rnd);
            if (r.ok()) {
                log(String.format("[Thread-Cancel] Cliente %s CANCELOU o assento %s do voo %s",
                        r.cliente(), Voo.rotulo(r.assento()), sis.getVoo(iv).getCodigo()));
            }
        }
    }

    // ======================================================================
    // Acoes manuais
    // ======================================================================
    private void dialogoManual(boolean reservar) {
        JTextField nome = new JTextField(12);
        String[] codigos = new String[sis.getNumVoos()];
        for (int i = 0; i < codigos.length; i++) codigos[i] = sis.getVoo(i).getCodigo();
        JComboBox<String> voo = new JComboBox<>(codigos);

        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Cliente:"));
        p.add(nome);
        p.add(new JLabel("Voo:"));
        p.add(voo);

        int opt = JOptionPane.showConfirmDialog(root, p, reservar ? "Reservar assento" : "Cancelar reserva",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        String n = nome.getText().trim();
        if (opt != JOptionPane.OK_OPTION || n.isEmpty()) return;

        int iv = voo.getSelectedIndex();
        SistemaReservas.Resposta r = reservar ? sis.reservar(iv, n) : sis.cancelar(iv, n);
        if (r.ok()) {
            log(String.format("[Main] %s %s: assento %s, voo %s", n, reservar ? "RESERVOU" : "CANCELOU",
                    Voo.rotulo(r.assento()), sis.getVoo(iv).getCodigo()));
        } else {
            log("[Main] Falhou: " + r.resultado().mensagem());
        }
    }

    private void auditar() {
        int ruins = sis.auditar(this::log);
        log("[Main] Voos inconsistentes: " + ruins);
    }

    // ======================================================================
    // Atualizacao periodica
    // ======================================================================
    void tick() {
        for (VooPanel vp : vooPanels) vp.refresh();
        statsLabel.setText("<html>Threads cliente ativas: <b>" + ativos.get()
                + "</b> &nbsp; com reserva: <b>" + totalOk.get()
                + "</b> &nbsp; sem vaga: <b>" + totalSemVaga.get() + "</b></html>");
    }

    void iniciar() {
        timer.start();
        if (chkCancel.isSelected()) ligarCancelamento(true);
        if (cfg.clientesIniciais() > 0) spawnClientes(cfg.clientesIniciais());
    }

    void encerrar() {
        rodando = false;
        timer.stop();
        if (thCancel != null) thCancel.interrupt();
    }

    // ======================================================================
    // main
    // ======================================================================
    private static Config pedirConfig() {
        JSpinner voos = new JSpinner(new SpinnerNumberModel(3, 1, 8, 1));
        JSpinner assentos = new JSpinner(new SpinnerNumberModel(12, 1, 48, 1));
        JSpinner clientes = new JSpinner(new SpinnerNumberModel(20, 0, 500, 1));
        JSpinner latencia = new JSpinner(new SpinnerNumberModel(1200, 0, 5000, 100));
        JSpinner atraso = new JSpinner(new SpinnerNumberModel(0, 0, 100_000, 500));
        JCheckBox mutex = new JCheckBox("Usar lock por voo (desmarque para demonstrar overbooking)", true);

        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Numero de voos (1-8):"));
        p.add(voos);
        p.add(new JLabel("Assentos por voo:"));
        p.add(assentos);
        p.add(new JLabel("Clientes iniciais (threads):"));
        p.add(clientes);
        p.add(new JLabel("Latencia max. do pagamento (ms):"));
        p.add(latencia);
        p.add(new JLabel("Atraso na secao critica (us):"));
        p.add(atraso);
        p.add(mutex);

        int opt = JOptionPane.showConfirmDialog(null, p, "Reserva de passagens - configuracao",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) return null;
        return new Config((Integer) voos.getValue(), (Integer) assentos.getValue(),
                (Integer) clientes.getValue(), (Integer) latencia.getValue(),
                (Integer) atraso.getValue(), mutex.isSelected());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Config cfg = pedirConfig();
            if (cfg == null) System.exit(0);

            ReservasGUI gui = new ReservasGUI(cfg);
            JFrame f = new JFrame("Reserva de passagens aereas - threads (Q3)");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(gui.getRoot());
            f.setSize(1350, 840);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            gui.iniciar();
        });
    }
}
