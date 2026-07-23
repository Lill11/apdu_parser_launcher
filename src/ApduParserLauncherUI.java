import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class ApduParserLauncherUI {

    private static final Color BG = new Color(240, 244, 249);
    private static final Color CARD = Color.WHITE;
    private static final Color TEXT = new Color(18, 25, 39);
    private static final Color MUTED = new Color(77, 91, 112);
    private static final Color SUBTLE = new Color(117, 132, 155);
    private static final Color BORDER = new Color(205, 216, 230);
    private static final Color BORDER_STRONG = new Color(188, 201, 220);
    private static final Color PRIMARY = new Color(34, 94, 214);
    private static final Color PRIMARY_DARK = new Color(22, 78, 190);
    private static final Color PRIMARY_SOFT = new Color(230, 238, 252);
    private static final Color SURFACE = new Color(247, 250, 255);
    private static final Color CODE_BG = new Color(28, 38, 52);
    private static final Color CODE_TEXT = new Color(237, 243, 252);
    private static final Color CODE_BORDER = new Color(61, 75, 96);

    private final ApduParserEngine engine;
    private final JFrame frame;
    private final LogsTableModel logsTableModel;
    private final JTable logsTable;
    private final JButton analyzeButton;
    private final JButton openResultsButton;
    private final JButton importButton;
    private final JButton moreButton;
    private final JButton clearAllButton;
    private final JLabel summaryLabel;
    private final JLabel selectionLabel;
    private final JTextArea apduArea;
    private final JTextArea analysisArea;
    private final JTextArea appletsArea;
    private final JTextArea errorsArea;
    private final JTextArea diagnosticsArea;
    private final JPanel diagnosticsPanel;
    private final JPanel resultTabs;
    private final CardLayout resultCardLayout;
    private final JPanel resultCardPanel;
    private final Map<ResultView, JToggleButton> resultTabButtons = new LinkedHashMap<>();
    private final Map<Path, List<ApduOutputAnalyzer.AnalysisItem>> analysisCache = new HashMap<>();
    private final EnumMap<ApduOutputAnalyzer.FilterMode, JToggleButton> filterButtons = new EnumMap<>(ApduOutputAnalyzer.FilterMode.class);
    private ApduParserEngine.Config preferences;
    private boolean detectOnlyMode;
    private ResultView selectedResultView = ResultView.ANALYSIS;

    private SwingWorker<ApduParserEngine.RunSummary, ApduParserEngine.ImportedLog> analysisWorker;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                setSystemLookAndFeel();
                new ApduParserLauncherUI().show();
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                JOptionPane.showMessageDialog(
                        null,
                        "Failed to start APDU Parser Launcher:\n" + ex.getMessage(),
                        "Startup Error",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }

    public ApduParserLauncherUI() {
        this.engine = new ApduParserEngine();
        this.preferences = engine.getConfig();
        this.frame = new JFrame("APDU Parser Launcher");
        this.logsTableModel = new LogsTableModel();
        this.logsTable = buildLogsTable();
        this.importButton = createPrimaryButton("Import Logs", false);
        this.analyzeButton = createPrimaryButton("Analyze", true);
        this.openResultsButton = createGhostButton("Open Results");
        this.moreButton = createGhostButton("More");
        this.clearAllButton = createGhostButton("Clear All");
        this.summaryLabel = createLabel("No logs imported yet.", 13, MUTED, Font.PLAIN);
        this.selectionLabel = createLabel("Select a log to inspect APDUs, analysis, applets, and errors.", 13, MUTED, Font.PLAIN);
        this.apduArea = createOutputArea(true, new Color(244, 247, 252), new Color(34, 43, 58), new Color(218, 226, 239));
        this.analysisArea = createOutputArea(false, Color.WHITE, TEXT, BORDER);
        this.appletsArea = createOutputArea(false, Color.WHITE, TEXT, BORDER);
        this.errorsArea = createOutputArea(false, new Color(255, 250, 250), new Color(95, 32, 32), new Color(236, 208, 208));
        this.diagnosticsArea = createOutputArea(true, new Color(244, 247, 252), new Color(56, 68, 88), BORDER);
        this.resultCardLayout = new CardLayout();
        this.resultCardPanel = new JPanel(resultCardLayout);
        this.diagnosticsPanel = buildDiagnosticsPanel();
        this.resultTabs = buildResultTabs();
        buildFrame();
        reloadLogsTable();
        refreshMetrics();
        refreshSelectionView();
    }

    private static void setSystemLookAndFeel() {
        try {
            boolean nimbusApplied = false;
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    nimbusApplied = true;
                    break;
                }
            }
            if (!nimbusApplied) {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            }
            applyThemeDefaults();
        } catch (Exception ignored) {
        }
    }

    private static void applyThemeDefaults() {
        FontUIResource baseFont = new FontUIResource("Segoe UI", Font.PLAIN, 13);
        FontUIResource boldFont = new FontUIResource("Segoe UI", Font.BOLD, 13);

        UIManager.put("defaultFont", baseFont);
        UIManager.put("Label.font", baseFont);
        UIManager.put("Button.font", boldFont);
        UIManager.put("ToggleButton.font", boldFont);
        UIManager.put("Table.font", baseFont);
        UIManager.put("TableHeader.font", boldFont);
        UIManager.put("TabbedPane.font", boldFont);
        UIManager.put("MenuItem.font", baseFont);
        UIManager.put("CheckBoxMenuItem.font", baseFont);

        UIManager.put("control", new ColorUIResource(BG));
        UIManager.put("info", new ColorUIResource(CARD));
        UIManager.put("nimbusBase", new ColorUIResource(PRIMARY_DARK));
        UIManager.put("nimbusBlueGrey", new ColorUIResource(new Color(223, 231, 242)));
        UIManager.put("nimbusLightBackground", new ColorUIResource(CARD));
        UIManager.put("text", new ColorUIResource(TEXT));
        UIManager.put("TabbedPane.contentAreaColor", new ColorUIResource(CARD));
        UIManager.put("TabbedPane.selected", new ColorUIResource(Color.WHITE));
        UIManager.put("Table.alternateRowColor", new ColorUIResource(SURFACE));
    }

    private void show() {
        frame.setVisible(true);
    }

    private void buildFrame() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1120, 720));
        frame.setSize(new Dimension(
                Math.max(1120, preferences.windowWidth()),
                Math.max(720, preferences.windowHeight())
        ));
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout(0, 12));

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(16, 16, 16, 16));
        JPanel topStack = new JPanel(new BorderLayout(0, 10));
        topStack.setOpaque(false);
        topStack.add(buildHeader(), BorderLayout.NORTH);
        topStack.add(buildSummaryBar(), BorderLayout.CENTER);
        root.add(topStack, BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(diagnosticsPanel, BorderLayout.SOUTH);

        attachDropSupport(root);
        frame.setContentPane(root);
        centerWindow();
        setDiagnosticsVisible(preferences.showDiagnosticsOnLaunch());
        detectOnlyMode = preferences.detectOnlyDefault();
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                persistPreferences();
            }
        });
    }

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(16, 12));
        panel.setOpaque(false);

        JPanel titlePanel = new RoundedPanel(20, true);
        titlePanel.setLayout(new BorderLayout(0, 12));
        titlePanel.setBorder(new EmptyBorder(18, 20, 18, 20));

        JPanel topRow = new JPanel(new BorderLayout(18, 0));
        topRow.setOpaque(false);
        JLabel badge = createLabel("IM / eSIM DEBUGGING TOOL", 13, PRIMARY_DARK, Font.BOLD);
        topRow.add(badge, BorderLayout.WEST);
        topRow.add(buildToolbar(), BorderLayout.EAST);

        JPanel textColumn = new JPanel();
        textColumn.setOpaque(false);
        textColumn.setLayout(new BoxLayout(textColumn, BoxLayout.Y_AXIS));
        textColumn.add(createLabel("APDU Parser Launcher", 26, TEXT, Font.BOLD));
        textColumn.add(Box.createVerticalStrut(6));
        textColumn.add(createLabel("Import logs, analyze APDUs, and review parser output in one compact QA workspace.", 13, MUTED, Font.PLAIN));

        titlePanel.add(topRow, BorderLayout.NORTH);
        titlePanel.add(textColumn, BorderLayout.CENTER);
        panel.add(titlePanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        toolbar.setOpaque(false);

        importButton.addActionListener(e -> chooseAndImportFiles());
        analyzeButton.addActionListener(e -> toggleAnalyze());
        openResultsButton.addActionListener(e -> openResults());
        moreButton.addActionListener(e -> showMoreMenu());

        toolbar.add(importButton);
        toolbar.add(analyzeButton);
        toolbar.add(openResultsButton);
        toolbar.add(moreButton);
        return toolbar;
    }

    private JPanel buildSummaryBar() {
        JPanel bar = new RoundedPanel(16, false);
        bar.setLayout(new BorderLayout());
        bar.setBorder(new EmptyBorder(10, 14, 10, 14));
        summaryLabel.setForeground(MUTED);
        bar.add(summaryLabel, BorderLayout.WEST);
        return bar;
    }

    private Component buildBody() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeftPanel(), buildResultPanel());
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(8);
        split.setResizeWeight(0.36);
        split.setOpaque(false);
        split.setBackground(BG);
        return split;
    }

    private Component buildLeftPanel() {
        JPanel left = new JPanel(new BorderLayout(0, 12));
        left.setOpaque(false);
        left.add(buildDropCard(), BorderLayout.NORTH);
        left.add(buildLogsCard(), BorderLayout.CENTER);
        return left;
    }

    private Component buildDropCard() {
        JPanel card = new RoundedPanel(18, false);
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(createLabel("Import Logs", 17, TEXT, Font.BOLD), BorderLayout.WEST);

        JButton importInline = createDropZoneButton("Drop files here or click Import Logs");
        importInline.setHorizontalAlignment(SwingConstants.CENTER);
        importInline.addActionListener(e -> chooseAndImportFiles());
        attachDropSupport(importInline);

        JTextArea help = createWrappedText("Drag .txt, .log, .html, or .htm files here.", 13);
        help.setForeground(MUTED);

        JPanel stack = new JPanel();
        stack.setOpaque(false);
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.add(help);
        stack.add(Box.createVerticalStrut(10));
        stack.add(importInline);

        card.add(header, BorderLayout.NORTH);
        card.add(stack, BorderLayout.CENTER);
        return card;
    }

    private Component buildLogsCard() {
        JPanel card = new RoundedPanel(18, false);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.setOpaque(false);
        header.add(createLabel("Imported Logs", 18, TEXT, Font.BOLD), BorderLayout.WEST);

        clearAllButton.addActionListener(e -> clearAllLogs());
        header.add(clearAllButton, BorderLayout.EAST);

        JScrollPane scrollPane = new JScrollPane(logsTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER));
        scrollPane.getViewport().setBackground(SURFACE);

        card.add(header, BorderLayout.NORTH);
        card.add(scrollPane, BorderLayout.CENTER);
        card.setPreferredSize(new Dimension(460, 520));
        return card;
    }

    private JTable buildLogsTable() {
        JTable table = new JTable(logsTableModel);
        table.setRowHeight(38);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(new Color(222, 233, 251));
        table.setSelectionForeground(TEXT);
        table.setBackground(SURFACE);
        table.setForeground(TEXT);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.setDefaultRenderer(Object.class, new LogsCellRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(false);
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        table.getTableHeader().setBackground(new Color(234, 240, 249));
        table.getTableHeader().setForeground(TEXT);
        table.getTableHeader().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_STRONG));
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(100);
        table.getColumnModel().getColumn(3).setPreferredWidth(62);
        table.getSelectionModel().addListSelectionListener(this::onLogSelectionChanged);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                int column = table.columnAtPoint(event.getPoint());
                if (row >= 0 && column == 3) {
                    removeLogAt(row);
                }
            }
        });
        return table;
    }

    private Component buildResultPanel() {
        JPanel panel = new RoundedPanel(18, true);
        panel.setLayout(new BorderLayout(0, 14));
        panel.setBorder(new EmptyBorder(18, 18, 18, 18));

        JPanel top = new JPanel(new BorderLayout(12, 10));
        top.setOpaque(false);

        JPanel titleColumn = new JPanel();
        titleColumn.setOpaque(false);
        titleColumn.setLayout(new BoxLayout(titleColumn, BoxLayout.Y_AXIS));
        titleColumn.add(createLabel("Analysis Workspace", 20, TEXT, Font.BOLD));
        titleColumn.add(Box.createVerticalStrut(6));
        titleColumn.add(selectionLabel);

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterBar.setOpaque(false);
        for (ApduOutputAnalyzer.FilterMode mode : ApduOutputAnalyzer.FilterMode.values()) {
            JToggleButton button = createFilterButton(mode.getLabel());
            button.addActionListener(e -> {
                selectFilter(mode);
                refreshSelectionView();
            });
            filterButtons.put(mode, button);
            filterBar.add(button);
        }
        selectFilter(ApduOutputAnalyzer.FilterMode.ALL);

        top.add(titleColumn, BorderLayout.CENTER);
        top.add(filterBar, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setOpaque(false);
        content.add(resultTabs, BorderLayout.NORTH);
        content.add(resultCardPanel, BorderLayout.CENTER);

        panel.add(top, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildResultTabs() {
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tabs.setOpaque(false);

        resultCardPanel.setOpaque(false);
        resultCardPanel.add(wrapArea(apduArea, new Color(228, 236, 247), true), ResultView.APDUS.name());
        resultCardPanel.add(wrapArea(analysisArea, BORDER, false), ResultView.ANALYSIS.name());
        resultCardPanel.add(wrapArea(appletsArea, BORDER, false), ResultView.APPLETS.name());
        resultCardPanel.add(wrapArea(errorsArea, new Color(236, 208, 208), false), ResultView.ERRORS.name());

        for (ResultView view : ResultView.values()) {
            JToggleButton tabButton = createTabButton(view.label);
            tabButton.addActionListener(e -> selectResultView(view));
            resultTabButtons.put(view, tabButton);
            tabs.add(tabButton);
        }
        selectResultView(selectedResultView);
        return tabs;
    }

    private JScrollPane wrapArea(JTextArea area, Color borderColor, boolean codeStyle) {
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                BorderFactory.createEmptyBorder()
        ));
        scrollPane.getViewport().setBackground(area.getBackground());
        scrollPane.setBackground(area.getBackground());
        if (codeStyle) {
            scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        }
        return scrollPane;
    }

    private JPanel buildDiagnosticsPanel() {
        JPanel panel = new RoundedPanel(16, false);
        panel.setLayout(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        panel.setVisible(false);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(createLabel("Diagnostics", 14, TEXT, Font.BOLD), BorderLayout.WEST);
        JButton hideButton = createGhostButton("Hide");
        hideButton.addActionListener(e -> setDiagnosticsVisible(false));
        header.add(hideButton, BorderLayout.EAST);

        JScrollPane scrollPane = wrapArea(diagnosticsArea, BORDER, true);
        scrollPane.setPreferredSize(new Dimension(10, 160));

        panel.add(header, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private void showMoreMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem refresh = new JMenuItem(new AbstractAction("Refresh Imported Logs") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                refreshImportedLogs();
            }
        });
        JMenuItem openInput = new JMenuItem(new AbstractAction("Open Input Folder") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openDirectory(engine.getInputDir());
            }
        });
        JMenuItem openOutput = new JMenuItem(new AbstractAction("Open Output Folder") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openDirectory(engine.getOutputDir());
            }
        });

        JCheckBoxMenuItem detectOnlyMenuItem = new JCheckBoxMenuItem("Detect only");
        detectOnlyMenuItem.setState(detectOnlyMode);
        detectOnlyMenuItem.addActionListener(e -> {
            detectOnlyMode = detectOnlyMenuItem.getState();
            persistPreferences();
        });

        JCheckBoxMenuItem diagnosticsMenuItem = new JCheckBoxMenuItem("Show diagnostics");
        diagnosticsMenuItem.setState(diagnosticsPanel.isVisible());
        diagnosticsMenuItem.addActionListener(e -> setDiagnosticsVisible(diagnosticsMenuItem.getState()));

        menu.add(refresh);
        menu.add(openInput);
        menu.add(openOutput);
        menu.addSeparator();
        menu.add(detectOnlyMenuItem);
        menu.add(diagnosticsMenuItem);
        menu.show(moreButton, 0, moreButton.getHeight());
    }

    private void chooseAndImportFiles() {
        java.awt.FileDialog dialog = new java.awt.FileDialog(frame, "Import Logs", java.awt.FileDialog.LOAD);
        dialog.setMultipleMode(true);
        dialog.setVisible(true);
        java.io.File[] files = dialog.getFiles();
        if (files == null || files.length == 0) {
            return;
        }
        List<Path> paths = new ArrayList<>();
        for (java.io.File file : files) {
            paths.add(file.toPath());
        }
        importFiles(paths);
    }

    private void importFiles(List<Path> paths) {
        List<Path> accepted = new ArrayList<>();
        for (Path path : paths) {
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".html") || name.endsWith(".htm")) {
                accepted.add(path);
            }
        }
        if (accepted.isEmpty()) {
            showInfo("No supported files were selected.");
            return;
        }

        try {
            List<ApduParserEngine.ImportedLog> imported = engine.importFiles(accepted);
            appendDiagnostic("Imported " + imported.size() + " file(s).");
            reloadLogsTable();
            refreshMetrics();
            if (!imported.isEmpty()) {
                selectLog(imported.get(imported.size() - 1).filePath());
            }
            if (engine.getConfig().autoAnalyzeOnImport()) {
                startAnalysis();
            }
        } catch (IOException ex) {
            showError("Import failed: " + ex.getMessage());
        }
    }

    private void toggleAnalyze() {
        if (analysisWorker != null && !analysisWorker.isDone()) {
            cancelRequested.set(true);
            analyzeButton.setEnabled(false);
            analyzeButton.setText("Cancelling...");
            appendDiagnostic("Cancellation requested.");
            return;
        }
        startAnalysis();
    }

    private void startAnalysis() {
        cancelRequested.set(false);
        analysisWorker = new SwingWorker<>() {
            @Override
            protected ApduParserEngine.RunSummary doInBackground() {
                return engine.analyzeAll(
                        detectOnlyMode,
                        cancelRequested::get,
                        this::publish,
                        ApduParserLauncherUI.this::appendDiagnostic
                );
            }

            @Override
            protected void process(List<ApduParserEngine.ImportedLog> chunks) {
                for (ApduParserEngine.ImportedLog updated : chunks) {
                    logsTableModel.updateLog(updated);
                    analysisCache.remove(updated.filePath());
                }
                refreshMetrics();
                refreshSelectionView();
            }

            @Override
            protected void done() {
                analyzeButton.setText("Analyze");
                analyzeButton.setEnabled(true);
                importButton.setEnabled(true);
                openResultsButton.setEnabled(true);
                moreButton.setEnabled(true);
                logsTable.setEnabled(true);
                try {
                    ApduParserEngine.RunSummary summary = get();
                    appendDiagnostic("Analysis finished. Completed=" + summary.completed()
                            + ", Unsupported=" + summary.unsupported()
                            + ", Failed=" + summary.failed()
                            + ", Cancelled=" + summary.cancelled());
                } catch (Exception ex) {
                    appendDiagnostic("Analysis failed: " + ex.getMessage());
                    showError("Analysis failed: " + ex.getMessage());
                } finally {
                    refreshImportedLogs();
                    analysisWorker = null;
                }
            }
        };

        analyzeButton.setText("Cancel");
        analyzeButton.setEnabled(true);
        importButton.setEnabled(false);
        openResultsButton.setEnabled(false);
        moreButton.setEnabled(false);
        logsTable.setEnabled(true);
        appendDiagnostic("Starting analysis for " + engine.getImportedLogs().size() + " log(s).");
        analysisWorker.execute();
    }

    private void openResults() {
        ApduParserEngine.ImportedLog selected = getSelectedLog();
        Path target = selected == null ? engine.getOutputDir() : selected.resultDir();
        if (selected != null && !Files.exists(target)) {
            target = engine.getOutputDir();
        }
        openDirectory(target);
    }

    private void openDirectory(Path path) {
        try {
            engine.openDirectory(path);
        } catch (IOException ex) {
            showError("Cannot open folder: " + ex.getMessage());
        }
    }

    private void clearAllLogs() {
        if (engine.getImportedLogs().isEmpty()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(frame, "Remove all imported logs and their results?", "Clear All", JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            int deleted = engine.clearImportedFiles();
            appendDiagnostic("Removed " + deleted + " imported log(s).");
            analysisCache.clear();
            reloadLogsTable();
            refreshMetrics();
            refreshSelectionView();
        } catch (IOException ex) {
            showError("Clear failed: " + ex.getMessage());
        }
    }

    private void removeLogAt(int row) {
        ApduParserEngine.ImportedLog log = logsTableModel.getLogAt(row);
        if (log == null) {
            return;
        }
        try {
            engine.deleteImportedFile(log.filePath());
            appendDiagnostic("Removed " + log.fileName());
            analysisCache.remove(log.filePath());
            reloadLogsTable();
            refreshMetrics();
            refreshSelectionView();
        } catch (IOException ex) {
            showError("Remove failed: " + ex.getMessage());
        }
    }

    private void refreshImportedLogs() {
        try {
            engine.refreshImportedLogs();
            reloadLogsTable();
            refreshMetrics();
            refreshSelectionView();
        } catch (IOException ex) {
            showError("Refresh failed: " + ex.getMessage());
        }
    }

    private void reloadLogsTable() {
        logsTableModel.setLogs(engine.getImportedLogs());
        if (logsTableModel.getRowCount() > 0 && logsTable.getSelectedRow() < 0) {
            logsTable.getSelectionModel().setSelectionInterval(0, 0);
        }
    }

    private void refreshMetrics() {
        List<ApduParserEngine.ImportedLog> logs = engine.getImportedLogs();
        int imported = logs.size();
        int parsed = 0;
        int unsupported = 0;
        String parser = "None";
        ApduParserEngine.ImportedLog selected = getSelectedLog();

        for (ApduParserEngine.ImportedLog log : logs) {
            if (log.status() == ApduParserEngine.Status.COMPLETED) {
                parsed++;
            } else if (log.status() == ApduParserEngine.Status.UNSUPPORTED) {
                unsupported++;
            }
        }

        if (selected != null && !selected.detectedFormat().isBlank() && !"Pending".equals(selected.detectedFormat())) {
            parser = selected.detectedFormat();
        } else {
            for (ApduParserEngine.ImportedLog log : logs) {
                if (!log.detectedFormat().isBlank() && !"Pending".equals(log.detectedFormat()) && !"Unsupported".equals(log.detectedFormat())) {
                    parser = log.detectedFormat();
                    break;
                }
            }
        }

        summaryLabel.setText(imported + " logs imported  ·  " + parsed + " parsed  ·  "
                + unsupported + " unsupported  ·  Parser: " + parser);
    }

    private void onLogSelectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting()) {
            refreshMetrics();
            refreshSelectionView();
        }
    }

    private void refreshSelectionView() {
        ApduParserEngine.ImportedLog selected = getSelectedLog();
        if (selected == null) {
            selectResultView(ResultView.ANALYSIS);
            selectionLabel.setText("Select a log to review extracted APDUs, analysis events, applets, and any parsing issues.");
            apduArea.setText("No log selected yet.\n\nImport a log and run Analyze to populate this view.");
            analysisArea.setText("No analysis available yet.\n\nChoose a log from the left panel to inspect enhanced APDU events.");
            appletsArea.setText("No applet extraction available yet.\n\nApplet installation flows will appear here when present in the selected log.");
            errorsArea.setText("No issues to display.");
            return;
        }

        String parserLabel = selected.detectedFormat().isBlank() ? "Pending" : selected.detectedFormat();
        selectionLabel.setText(selected.fileName() + "  |  Format: " + parserLabel + "  |  Status: " + selected.status().name());

        try {
            if (Files.exists(selected.rawOutputPath())) {
                apduArea.setText(engine.readFilePreview(selected.rawOutputPath(), 160_000));
            } else {
                apduArea.setText(selected.status() == ApduParserEngine.Status.UNSUPPORTED
                        ? "No APDU output. The log format is unsupported."
                        : "No APDU output yet.");
            }
        } catch (IOException ex) {
            apduArea.setText(ex.getMessage());
        }

        analysisArea.setText(renderAnalysisText(selected));
        appletsArea.setText(renderAppletsText(selected));
        errorsArea.setText(renderErrorsText(selected));
        if (selected.status() == ApduParserEngine.Status.FAILED) {
            selectResultView(ResultView.ERRORS);
        }
        apduArea.setCaretPosition(0);
        analysisArea.setCaretPosition(0);
        appletsArea.setCaretPosition(0);
        errorsArea.setCaretPosition(0);
    }

    private String renderAnalysisText(ApduParserEngine.ImportedLog selected) {
        if (!Files.exists(selected.rawOutputPath())) {
            return selected.status() == ApduParserEngine.Status.COMPLETED
                    ? "No APDUs extracted."
                    : "Analysis is available after a successful run.";
        }
        try {
            List<ApduOutputAnalyzer.AnalysisItem> items = analysisCache.computeIfAbsent(selected.filePath(), key -> {
                try {
                    return ApduOutputAnalyzer.analyzeEntries(selected.filePath(), selected.rawOutputPath());
                } catch (IOException ex) {
                    return List.of();
                }
            });
            return ApduOutputAnalyzer.renderEnhancedOutput(items, getSelectedFilter());
        } catch (Exception ex) {
            return ex.getMessage();
        }
    }

    private String renderAppletsText(ApduParserEngine.ImportedLog selected) {
        if (selected.appletStatus() == AppletExtractor.ExtractionResult.Status.NOT_APPLICABLE) {
            return selected.appletMessage().isBlank() ? "Applet extraction has not run yet." : selected.appletMessage();
        }
        if (selected.appletStatus() == AppletExtractor.ExtractionResult.Status.NO_APPLETS) {
            return selected.appletMessage().isBlank() ? "No applet installation flow found." : selected.appletMessage();
        }
        if (!Files.exists(selected.appletsDir())) {
            return "Applet extraction completed, but no files were written.";
        }

        StringBuilder sb = new StringBuilder();
        try {
            Path allClean = selected.appletsDir().resolve("all_clean.lop");
            if (Files.exists(allClean)) {
                sb.append("all_clean.lop").append(System.lineSeparator());
                sb.append(Files.readString(allClean, StandardCharsets.UTF_8).strip()).append(System.lineSeparator()).append(System.lineSeparator());
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(selected.appletsDir(), "applet_*.lop")) {
                for (Path file : stream) {
                    sb.append(file.getFileName()).append(System.lineSeparator());
                    sb.append(Files.readString(file, StandardCharsets.UTF_8).strip()).append(System.lineSeparator()).append(System.lineSeparator());
                }
            }
        } catch (IOException ex) {
            return ex.getMessage();
        }
        return sb.length() == 0 ? "No applet files available." : sb.toString().stripTrailing();
    }

    private String renderErrorsText(ApduParserEngine.ImportedLog selected) {
        if (Files.exists(selected.errorsOutputPath())) {
            try {
                return Files.readString(selected.errorsOutputPath(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                return ex.getMessage();
            }
        }
        if (selected.status() == ApduParserEngine.Status.COMPLETED) {
            return selected.warningCount() > 0
                    ? "Completed with " + selected.warningCount() + " parser warning(s)."
                    : "No errors.";
        }
        if (selected.status() == ApduParserEngine.Status.UNSUPPORTED) {
            return "Unsupported log format.";
        }
        return selected.message();
    }

    private void selectLog(Path filePath) {
        int row = logsTableModel.indexOf(filePath);
        if (row >= 0) {
            logsTable.getSelectionModel().setSelectionInterval(row, row);
            logsTable.scrollRectToVisible(logsTable.getCellRect(row, 0, true));
        }
    }

    private ApduParserEngine.ImportedLog getSelectedLog() {
        int selectedRow = logsTable.getSelectedRow();
        return selectedRow >= 0 ? logsTableModel.getLogAt(selectedRow) : null;
    }

    private void selectFilter(ApduOutputAnalyzer.FilterMode mode) {
        for (Map.Entry<ApduOutputAnalyzer.FilterMode, JToggleButton> entry : filterButtons.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == mode);
            styleFilterChip(entry.getValue(), entry.getKey() == mode);
        }
    }

    private ApduOutputAnalyzer.FilterMode getSelectedFilter() {
        for (Map.Entry<ApduOutputAnalyzer.FilterMode, JToggleButton> entry : filterButtons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return ApduOutputAnalyzer.FilterMode.ALL;
    }

    private void setDiagnosticsVisible(boolean visible) {
        diagnosticsPanel.setVisible(visible);
        persistPreferences();
        frame.revalidate();
    }

    private void selectResultView(ResultView view) {
        selectedResultView = view;
        resultCardLayout.show(resultCardPanel, view.name());
        for (Map.Entry<ResultView, JToggleButton> entry : resultTabButtons.entrySet()) {
            boolean selected = entry.getKey() == view;
            entry.getValue().setSelected(selected);
            styleResultTab(entry.getValue(), selected);
        }
    }

    private void appendDiagnostic(String line) {
        SwingUtilities.invokeLater(() -> {
            diagnosticsArea.append(line + System.lineSeparator());
            diagnosticsArea.setCaretPosition(diagnosticsArea.getDocument().getLength());
        });
    }

    private void attachDropSupport(JComponent component) {
        component.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Transferable transferable = support.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<java.io.File> files = (List<java.io.File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> paths = new ArrayList<>();
                    for (java.io.File file : files) {
                        paths.add(file.toPath());
                    }
                    importFiles(paths);
                    return true;
                } catch (Exception ex) {
                    showError("Drop import failed: " + ex.getMessage());
                    return false;
                }
            }
        });
    }

    private void centerWindow() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setLocation(
                Math.max(40, (screen.width - frame.getWidth()) / 2),
                Math.max(20, (screen.height - frame.getHeight()) / 2)
        );
    }

    private void persistPreferences() {
        preferences = new ApduParserEngine.Config(
                preferences.inputDir(),
                preferences.outputDir(),
                preferences.tempDir(),
                preferences.logsDir(),
                preferences.autoAnalyzeOnImport(),
                preferences.retainDebugArtifacts(),
                detectOnlyMode,
                diagnosticsPanel.isVisible(),
                frame.getWidth(),
                frame.getHeight()
        );
        try {
            engine.saveConfig(preferences);
        } catch (IOException ignored) {
        }
    }

    private JButton createPrimaryButton(String text, boolean emphasis) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setMargin(new Insets(11, emphasis ? 22 : 18, 11, emphasis ? 22 : 18));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBackground(emphasis ? PRIMARY : PRIMARY_DARK);
        button.setForeground(Color.WHITE);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(button.getBackground().darker()),
                new EmptyBorder(2, 4, 2, 4)
        ));
        return button;
    }

    private JButton createGhostButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setMargin(new Insets(11, 16, 11, 16));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBackground(new Color(248, 250, 253));
        button.setForeground(TEXT);
        button.setBorder(BorderFactory.createLineBorder(BORDER_STRONG));
        return button;
    }

    private JButton createSoftButton(String text) {
        JButton button = createGhostButton(text);
        button.setBackground(PRIMARY_SOFT);
        button.setForeground(PRIMARY);
        button.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        return button;
    }

    private JButton createDropZoneButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 14));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(PRIMARY_DARK);
        button.setBackground(new Color(236, 242, 251));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createDashedBorder(new Color(143, 170, 212), 2f, 5f),
                new EmptyBorder(22, 18, 22, 18)
        ));
        return button;
    }

    private JToggleButton createTabButton(String text) {
        JToggleButton button = new JToggleButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setMargin(new Insets(9, 16, 9, 16));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        styleResultTab(button, false);
        return button;
    }

    private JToggleButton createFilterButton(String text) {
        JToggleButton button = new JToggleButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setMargin(new Insets(7, 12, 7, 12));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        styleFilterChip(button, false);
        return button;
    }

    private void styleFilterChip(JToggleButton button, boolean selected) {
        button.setBackground(selected ? PRIMARY : new Color(245, 247, 251));
        button.setForeground(selected ? Color.WHITE : TEXT);
        button.setBorder(BorderFactory.createLineBorder(selected ? PRIMARY_DARK : BORDER_STRONG));
    }

    private void styleResultTab(JToggleButton button, boolean selected) {
        button.setBackground(selected ? PRIMARY : new Color(244, 247, 251));
        button.setForeground(selected ? Color.WHITE : TEXT);
        button.setBorder(BorderFactory.createLineBorder(selected ? PRIMARY_DARK : BORDER_STRONG));
    }

    private JLabel createLabel(String text, int size, Color color, int style) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", style, size));
        label.setForeground(color);
        return label;
    }

    private JTextArea createWrappedText(String text, int size) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font("Segoe UI", Font.PLAIN, size));
        area.setForeground(MUTED);
        return area;
    }

    private JTextArea createOutputArea(boolean monospace, Color background, Color foreground, Color borderColor) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(monospace ? new Font("Consolas", Font.PLAIN, 13) : new Font("Segoe UI", Font.PLAIN, 14));
        area.setBackground(background);
        area.setForeground(foreground);
        area.setCaretColor(foreground);
        area.setBorder(new EmptyBorder(14, 14, 14, 14));
        area.setSelectionColor(new Color(209, 226, 252));
        area.setSelectedTextColor(TEXT);
        return area;
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "APDU Parser Launcher", JOptionPane.ERROR_MESSAGE);
    }

    private void showInfo(String message) {
        JOptionPane.showMessageDialog(frame, message, "APDU Parser Launcher", JOptionPane.INFORMATION_MESSAGE);
    }

    private static final class RoundedPanel extends JPanel {
        RoundedPanel(int radius, boolean accentBorder) {
            setOpaque(true);
            setBackground(CARD);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(accentBorder ? BORDER_STRONG : BORDER),
                    BorderFactory.createEmptyBorder()
            ));
        }
    }

    private enum ResultView {
        APDUS("APDUs"),
        ANALYSIS("Analysis"),
        APPLETS("Applets"),
        ERRORS("Errors");

        private final String label;

        ResultView(String label) {
            this.label = label;
        }
    }

    private static final class LogsTableModel extends AbstractTableModel {
        private final String[] columns = {"Filename", "Format", "Status", ""};
        private final List<ApduParserEngine.ImportedLog> logs = new ArrayList<>();

        void setLogs(List<ApduParserEngine.ImportedLog> updatedLogs) {
            logs.clear();
            logs.addAll(updatedLogs);
            fireTableDataChanged();
        }

        void updateLog(ApduParserEngine.ImportedLog updatedLog) {
            int index = indexOf(updatedLog.filePath());
            if (index < 0) {
                logs.add(updatedLog);
                fireTableRowsInserted(logs.size() - 1, logs.size() - 1);
                return;
            }
            logs.set(index, updatedLog);
            fireTableRowsUpdated(index, index);
        }

        int indexOf(Path filePath) {
            for (int i = 0; i < logs.size(); i++) {
                if (logs.get(i).filePath().equals(filePath)) {
                    return i;
                }
            }
            return -1;
        }

        ApduParserEngine.ImportedLog getLogAt(int row) {
            return row >= 0 && row < logs.size() ? logs.get(row) : null;
        }

        @Override
        public int getRowCount() {
            return logs.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ApduParserEngine.ImportedLog log = logs.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> log.fileName();
                case 1 -> log.detectedFormat();
                case 2 -> log.status().name();
                case 3 -> "Remove";
                default -> "";
            };
        }
    }

    private static final class LogsCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setFont(new Font("Segoe UI", column == 0 ? Font.BOLD : Font.PLAIN, 12));
            label.setForeground(column == 3 ? PRIMARY_DARK : TEXT);
            label.setHorizontalAlignment(column == 3 ? SwingConstants.CENTER : SwingConstants.LEFT);
            label.setBackground(isSelected ? new Color(222, 233, 251) : (row % 2 == 0 ? SURFACE : Color.WHITE));
            label.setOpaque(true);
            label.setBorder(new EmptyBorder(0, column == 3 ? 4 : 10, 0, column == 3 ? 4 : 10));
            return label;
        }
    }
}
