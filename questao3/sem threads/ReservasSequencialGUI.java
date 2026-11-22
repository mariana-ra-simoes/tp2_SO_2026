import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Q3 (versao SEQUENCIAL) -- INTERFACE GRAFICA (Swing).
 *
 * Mesmo visual da ReservasGUI (versao com threads), mas SEM paralelismo:
 * os clientes entram numa FILA e um unico atendente (uma thread de trabalho)
 * processa um de cada vez. Enquanto um cliente "paga", todos os outros
 * ficam parados na fila ("Na fila") -- as latencias se SOMAM.
 *
 * Por que uma thread de trabalho e nao a propria EDT do Swing? Porque o
 * sleep do pagamento congelaria a janela. Ela e so o "atendente" que
 * executa os clientes em sequencia; nao ha dois clientes ao mesmo tempo.
 * Tudo que MODIFICA o sistema (reservar, cancelar, auditar) passa por essa
 * mesma fila; a EDT so le o estado para desenhar. Por isso o TAD roda com
 * usarMutex = false (nao ha disputa).
 *
 * Compilar: javac *.java        Executar: java ReservasSequencialGUI
 */
public class ReservasSequencialGUI {

    record Config(int voos, int assentos, int clientesIniciais, int latenciaMs) { }

    private static final Color LIVRE    = new Color(0xC8E6C9);
    private static final Color OCUPADO  = new Color(0xEF9A9A);
    private static final Color NOVO     = new Color(0xFFEB3B);
    private static final Color LIBERADO = new Color(0x64B5F6);
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
    private int proxId = 0;                                // so a EDT mexe
    private final Random rnd = new Random();               // so o atendente usa

    // O unico "atendente": uma thread que executa as tarefas da fila, uma por vez.
    private final ExecutorService atendente = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Atendente");
        t.setDaemon(true);
        return t;
    });

    // Contabilidade do lote de clientes (protegida por synchronized)
    private int pendentes = 0, atendidos = 0, comReserva = 0, semVaga = 0;
    private long inicioLote = 0;
    private long ultimoLoteNs = -1;                        // duracao do ultimo lote concluido

    private final JPanel root = new JPanel(new BorderLayout(6, 6));
    private final List<VooPanel> vooPanels = new ArrayList<>();
    private final ThreadsModel clientesModel = new ThreadsModel();
    private JTable tabelaClientes;
    private final JTextArea logArea = new JTextArea();
    private final JLabel statsLabel = new JLabel();
    private final Timer timer;

    // ======================================================================
    // Construcao da interface
    // ======================================================================
    ReservasSequencialGUI(Config cfg) {
        this.cfg = cfg;
        this.maxLatenciaMs = cfg.latenciaMs();
        this.sis = new SistemaReservas(cfg.voos(), cfg.assentos(), false);

        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(montaControles(), BorderLayout.NORTH);
        root.add(montaCentro(), BorderLayout.CENTER);

        timer = new Timer(100, e -> tick());
    }

    JComponent getRoot() { return root; }

    private JComponent montaControles() {
        JPanel linha1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));

        JSpinner spQtd = new JSpinner(new SpinnerNumberModel(10, 1, 500, 1));
        JButton btnAdd = new JButton("Adicionar clientes (na fila)");
        btnAdd.addActionListener(e -> enfileirarClientes((Integer) spQtd.getValue()));

        JSpinner spLat = new JSpinner(new SpinnerNumberModel(cfg.latenciaMs(), 0, 5000, 100));
        spLat.addChangeListener(e -> maxLatenciaMs = (Integer) spLat.getValue());

        JButton btnRes = new JButton("Reservar...");
        btnRes.addActionListener(e -> dialogoManual(true));
        JButton btnCan = new JButton("Cancelar...");
        btnCan.addActionListener(e -> dialogoManual(false));
        JButton btnCanRnd = new JButton("Cancelar aleatorio");
        btnCanRnd.addActionListener(e -> cancelarAleatorio());
        JButton btnAud = new JButton("Auditar");
        btnAud.addActionListener(e -> auditar());
        JButton btnLimpa = new JButton("Limpar log");
        btnLimpa.addActionListener(e -> logArea.setText(""));

        linha1.add(new JLabel("Clientes:"));
        linha1.add(spQtd);
        linha1.add(btnAdd);
        linha1.add(new JLabel("  Latencia max. do pagamento (ms):"));
        linha1.add(spLat);
        linha1.add(btnRes);
        linha1.add(btnCan);
        linha1.add(btnCanRnd);
        linha1.add(btnAud);
        linha1.add(btnLimpa);

        JLabel modo = new JLabel("<html><b>SEQUENCIAL: 1 cliente por vez</b> (sem threads concorrentes, sem lock)</html>");
        modo.setForeground(new Color(0x1565C0));

        JLabel legenda = new JLabel("<html>Legenda: "
                + cor(LIVRE) + " livre &nbsp; " + cor(OCUPADO) + " ocupado &nbsp; "
                + cor(NOVO) + " acabou de reservar &nbsp; " + cor(LIBERADO) + " acabou de cancelar</html>");

        JPanel linha2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        linha2.add(modo);
        linha2.add(new JLabel("  |  "));
        linha2.add(legenda);

        JPanel linha3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        linha3.add(statsLabel);

        JPanel p = new JPanel(new GridLayout(3, 1, 0, 4));
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        p.add(linha1);
        p.add(linha2);
        p.add(linha3);
        return p;
    }

    private static String cor(Color c) {
        return String.format("<font color='#%06X'>&#9608;&#9608;</font>", c.getRGB() & 0xFFFFFF);
    }

    private JComponent montaCentro() {
        JPanel grade = new JPanel(new GridLayout(0, 1, 8, 8));
        for (int i = 0; i < sis.getNumVoos(); i++) {
            VooPanel vp = new VooPanel(i);
            vooPanels.add(vp);
            grade.add(vp);
        }
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(grade, BorderLayout.NORTH);
        JScrollPane scrollVoos = new JScrollPane(wrapper);
        scrollVoos.setBorder(BorderFactory.createTitledBorder("Voos e assentos"));
        scrollVoos.getVerticalScrollBar().setUnitIncrement(16);

        tabelaClientes = new JTable(clientesModel);
        tabelaClientes.setFillsViewportHeight(true);
        tabelaClientes.getColumnModel().getColumn(0).setPreferredWidth(80);
        tabelaClientes.getColumnModel().getColumn(1).setPreferredWidth(100);
        tabelaClientes.getColumnModel().getColumn(2).setPreferredWidth(260);
        tabelaClientes.setDefaultRenderer(Object.class, new EstadoRenderer());
        JScrollPane scrollClientes = new JScrollPane(tabelaClientes);
        scrollClientes.setBorder(BorderFactory.createTitledBorder("Fila de clientes (estado em tempo real)"));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollLog = new JScrollPane(logArea);
        scrollLog.setBorder(BorderFactory.createTitledBorder("Log"));

        JSplitPane direita = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollClientes, scrollLog);
        direita.setResizeWeight(0.5);
        direita.setDividerLocation(300);

        JSplitPane principal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollVoos, direita);
        principal.setResizeWeight(0.55);
        principal.setDividerLocation(720);
        return principal;
    }

    // ======================================================================
    // Painel de um voo (so leitura: a EDT apenas desenha)
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

            titulo.setText("<html><b>" + voo.getCodigo() + "</b> &rarr; " + voo.getDestino()
                    + " &nbsp;|&nbsp; assentos ocupados: " + reais + "/" + voo.getCapacidade() + "</html>");
            barra.setValue(reais);
            barra.setString(reais + " / " + voo.getCapacidade());
        }
    }

    // ======================================================================
    // Tabela de clientes
    // ======================================================================
    private static class ThreadsModel extends AbstractTableModel {
        private final List<String[]> linhas = new ArrayList<>();   // {cliente n., nome, estado}
        private final Map<Integer, Integer> posicao = new HashMap<>();
        private static final String[] COLS = {"Cliente", "Nome", "Estado"};

        void add(int id, String nome) {
            posicao.put(id, linhas.size());
            linhas.add(new String[] {"Cliente " + id, nome, "Na fila"});
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
                if (estado.startsWith("Na fila"))        bg = new Color(0xECEFF1);
                else if (estado.startsWith("Pagando"))   bg = new Color(0xFFF9C4);
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
    // Log e estado (chamados pelo atendente ou pela EDT)
    // ======================================================================
    private void log(String msg) {
        String linha = "[" + HORA.format(LocalTime.now()) + "] " + msg + "\n";
        if (SwingUtilities.isEventDispatchThread()) appendLog(linha);
        else SwingUtilities.invokeLater(() -> appendLog(linha));
    }

    private void appendLog(String linha) {
        logArea.append(linha);
        int len = logArea.getDocument().getLength();
        if (len > 200_000) logArea.replaceRange("", 0, 50_000);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void estado(int id, String estado) {
        SwingUtilities.invokeLater(() -> clientesModel.set(id, estado));
    }

    // ======================================================================
    // Contabilidade do lote (tempo total para esvaziar a fila)
    // ======================================================================
    private synchronized void iniciaLote(int q) {
        if (pendentes == 0) { inicioLote = System.nanoTime(); atendidos = comReserva = semVaga = 0; }
        pendentes += q;
    }

    /** Chamado pelo atendente ao fim de cada cliente. Devolve a duracao do lote se a fila esvaziou, senao -1. */
    private synchronized long terminouCliente(boolean reservou) {
        pendentes--;
        atendidos++;
        if (reservou) comReserva++; else semVaga++;
        if (pendentes == 0) {
            ultimoLoteNs = System.nanoTime() - inicioLote;
            return ultimoLoteNs;
        }
        return -1;
    }

    // ======================================================================
    // Clientes: cada um e UMA TAREFA na fila do atendente
    // ======================================================================
    private void enfileirarClientes(int quantidade) {
        iniciaLote(quantidade);
        for (int i = 0; i < quantidade; i++) {
            int id = ++proxId;
            String nome = NOMES[(id - 1) % NOMES.length] + "-" + id;
            clientesModel.add(id, nome);                       // chamado na EDT
            atendente.submit(() -> atende(id, nome));
        }
        log("[Main] " + quantidade + " clientes entraram na fila");
        if (tabelaClientes != null && clientesModel.getRowCount() > 0)
            tabelaClientes.scrollRectToVisible(tabelaClientes.getCellRect(clientesModel.getRowCount() - 1, 0, true));
    }

    /** Mesma regra do Cliente da versao com threads, mas executada em sequencia. */
    private void atende(int id, String nome) {
        boolean reservou = false;
        try {
            int n = sis.getNumVoos();
            int primeiro = rnd.nextInt(n);
            int max = maxLatenciaMs;
            int latencia = max > 0 ? rnd.nextInt(max + 1) : 0;
            Voo desejado = sis.getVoo(primeiro);

            estado(id, "Pagando (" + latencia + " ms) para o voo " + desejado.getCodigo());
            log(String.format("[Cliente %d] %s quer o voo %s (%s). Processando pagamento (%d ms)...",
                    id, nome, desejado.getCodigo(), desejado.getDestino(), latencia));

            if (latencia > 0) {
                try {
                    Thread.sleep(latencia);          // aqui TODOS os outros clientes esperam
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            for (int k = 0; k < n && !reservou; k++) {
                int iv = (primeiro + k) % n;
                Voo v = sis.getVoo(iv);
                estado(id, "Tentando reservar no voo " + v.getCodigo());
                SistemaReservas.Resposta r = sis.reservar(iv, nome);

                if (r.ok()) {
                    String rot = Voo.rotulo(r.assento());
                    estado(id, "RESERVOU assento " + rot + " no voo " + v.getCodigo());
                    log(String.format("[Cliente %d] %s RESERVOU assento %s no voo %s", id, nome, rot, v.getCodigo()));
                    reservou = true;
                } else if (r.resultado() == Resultado.LOTADO) {
                    log(String.format("[Cliente %d] Voo %s lotado para %s%s", id, v.getCodigo(), nome,
                            k + 1 < n ? ", tentando outro voo..." : ""));
                } else {
                    log(String.format("[Cliente %d] %s: %s (voo %s)", id, nome, r.resultado().mensagem(), v.getCodigo()));
                }
            }

            if (!reservou) {
                estado(id, "SEM VAGA em todos os voos");
                log(String.format("[Cliente %d] %s ficou SEM VAGA em todos os voos", id, nome));
            }
        } finally {
            long dur = terminouCliente(reservou);
            if (dur >= 0) log(String.format("[Main] Fila concluida em %.3f s (tempo sequencial)", dur / 1e9));
        }
    }

    // ======================================================================
    // Acoes manuais (tambem entram na fila: so o atendente mexe nos voos)
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
        atendente.submit(() -> {
            SistemaReservas.Resposta r = reservar ? sis.reservar(iv, n) : sis.cancelar(iv, n);
            if (r.ok()) {
                log(String.format("[Main] %s %s: assento %s, voo %s", n, reservar ? "RESERVOU" : "CANCELOU",
                        Voo.rotulo(r.assento()), sis.getVoo(iv).getCodigo()));
            } else {
                log("[Main] Falhou: " + r.resultado().mensagem());
            }
        });
    }

    private void cancelarAleatorio() {
        atendente.submit(() -> {
            int iv = rnd.nextInt(sis.getNumVoos());
            SistemaReservas.Resposta r = sis.cancelarAleatorio(iv, rnd);
            if (r.ok()) {
                log(String.format("[Main] Cliente %s CANCELOU o assento %s do voo %s",
                        r.cliente(), Voo.rotulo(r.assento()), sis.getVoo(iv).getCodigo()));
            } else {
                log("[Main] Nenhuma reserva para cancelar no voo " + sis.getVoo(iv).getCodigo());
            }
        });
    }

    private void auditar() {
        log("[Main] Auditoria pedida (roda quando chegar a vez na fila)");
        atendente.submit(() -> {
            int ruins = sis.auditar(this::log);
            log("[Main] Voos inconsistentes: " + ruins);
        });
    }

    // ======================================================================
    // Atualizacao periodica
    // ======================================================================
    private synchronized String statsHtml() {
        String tempo;
        if (pendentes > 0)         tempo = String.format("%.1f s (rodando)", (System.nanoTime() - inicioLote) / 1e9);
        else if (ultimoLoteNs >= 0) tempo = String.format("%.3f s (ultimo lote)", ultimoLoteNs / 1e9);
        else                        tempo = "--";
        return "<html>Na fila: <b>" + pendentes + "</b> &nbsp; atendidos: <b>" + atendidos
                + "</b> &nbsp; com reserva: <b>" + comReserva + "</b> &nbsp; sem vaga: <b>" + semVaga
                + "</b> &nbsp; tempo: <b>" + tempo + "</b></html>";
    }

    void tick() {
        for (VooPanel vp : vooPanels) vp.refresh();
        statsLabel.setText(statsHtml());
    }

    void iniciar() {
        timer.start();
        if (cfg.clientesIniciais() > 0) enfileirarClientes(cfg.clientesIniciais());
    }

    // ======================================================================
    // main
    // ======================================================================
    private static Config pedirConfig() {
        JSpinner voos = new JSpinner(new SpinnerNumberModel(3, 1, 8, 1));
        JSpinner assentos = new JSpinner(new SpinnerNumberModel(12, 1, 48, 1));
        JSpinner clientes = new JSpinner(new SpinnerNumberModel(20, 0, 500, 1));
        JSpinner latencia = new JSpinner(new SpinnerNumberModel(1200, 0, 5000, 100));

        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Numero de voos (1-8):"));
        p.add(voos);
        p.add(new JLabel("Assentos por voo:"));
        p.add(assentos);
        p.add(new JLabel("Clientes iniciais (na fila):"));
        p.add(clientes);
        p.add(new JLabel("Latencia max. do pagamento (ms):"));
        p.add(latencia);

        int opt = JOptionPane.showConfirmDialog(null, p, "Reserva de passagens (sequencial) - configuracao",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) return null;
        return new Config((Integer) voos.getValue(), (Integer) assentos.getValue(),
                (Integer) clientes.getValue(), (Integer) latencia.getValue());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Config cfg = pedirConfig();
            if (cfg == null) System.exit(0);

            ReservasSequencialGUI gui = new ReservasSequencialGUI(cfg);
            JFrame f = new JFrame("Reserva de passagens aereas - SEQUENCIAL (Q3)");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(gui.getRoot());
            f.setSize(1350, 840);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            gui.iniciar();
        });
    }
}