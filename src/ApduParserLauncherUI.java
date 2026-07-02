import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class ApduParserLauncherUI {

    private static final Color APP_BG = new Color(232, 236, 241);
    private static final Color SHELL_BG = new Color(244, 246, 249);
    private static final Color CARD_BG = Color.WHITE;
    private static final Color CARD_BORDER = new Color(208, 214, 221);
    private static final Color MUTED = new Color(97, 105, 117);
    private static final Color TEXT = new Color(24, 31, 42);
    private static final Color BLUE = new Color(32, 99, 185);
    private static final Color BLUE_SOFT = new Color(240, 244, 248);
    private static final Color CODE_BG = new Color(18, 22, 28);
    private static final Color CODE_TEXT = new Color(230, 235, 241);
    private static final int RESPONSIVE_STACK_WIDTH = 980;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            try {
                new ApduParserLauncherUI().show();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(
                        null,
                        "Failed to start UI:\n" + e.getMessage(),
                        "APDU Parser Launcher",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }

    private final ApduParserEngine engine;
    private final JFrame frame;
    private final JPanel importedLogsListPanel;
    private final JScrollPane importedLogsScrollPane;
    private final JTextArea previewArea;
    private final JTextArea analysisArea;
    private final JTextArea appletArea;
    private final JTextArea consoleArea;
    private final JTabbedPane outputTabs;
    private final JLabel statusLabel;
    private final JTextArea selectionSummary;
    private final JLabel filesMetric;
    private final JLabel parsedMetric;
    private final JLabel unknownMetric;
    private final JLabel parserMetric;
    private final JCheckBox detectOnlyCheckbox;
    private final FlatButton refreshButton;
    private final FlatButton parseButton;
    private final FlatButton extractAppletsButton;
    private final FlatButton importButton;
    private final FlatButton registerButton;
    private final FlatButton openInputButton;
    private final FlatButton openOutputButton;
    private final FlatButton removeSelectedButton;
    private final FlatButton clearAllButton;
    private final FlatButton toggleConsoleButton;
    private final JPanel responsiveColumnsPanel;
    private final JPanel leftColumnPanel;
    private final JPanel centerColumnPanel;
    private final RoundedPanel consoleCardPanel;
    private final JTabbedPane filterTabs;
    private List<ApduOutputAnalyzer.AnalysisItem> currentAnalysisItems;
    private ApduOutputAnalyzer.FilterMode selectedFilter;
    private boolean consoleVisible;
    private Path selectedImportedFile;
    private RoundedPanel dropTargetPanel;

    private ApduParserLauncherUI() throws Exception {
        this.engine = new ApduParserEngine();

        frame = new JFrame("APDU Parser Launcher");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1240, 760);
        frame.setMinimumSize(new Dimension(920, 640));
        frame.getContentPane().setBackground(APP_BG);
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                refreshAll();
            }
        });

        RoundedPanel shell = new RoundedPanel(SHELL_BG, 16, false);
        shell.setLayout(new BorderLayout(12, 12));
        shell.setBorder(new EmptyBorder(8, 8, 8, 8));

        importedLogsListPanel = new JPanel();
        importedLogsListPanel.setLayout(new BoxLayout(importedLogsListPanel, BoxLayout.Y_AXIS));
        importedLogsListPanel.setBackground(Color.WHITE);
        importedLogsListPanel.setOpaque(true);
        importedLogsScrollPane = new JScrollPane(importedLogsListPanel);
        importedLogsScrollPane.setBorder(BorderFactory.createEmptyBorder());
        importedLogsScrollPane.getViewport().setBackground(Color.WHITE);
        previewArea = createTextArea(false, CODE_BG, CODE_TEXT, true);
        analysisArea = createTextArea(false, CODE_BG, CODE_TEXT, true);
        appletArea = createTextArea(false, CODE_BG, CODE_TEXT, true);
        outputTabs = new JTabbedPane();
        consoleArea = createTextArea(true, new Color(245, 248, 252), new Color(53, 80, 108), false);
        statusLabel = createLabel("Ready", 12, MUTED, Font.PLAIN);
        selectionSummary = createInfoText();
        selectionSummary.setForeground(TEXT);
        filesMetric = createMetricValue("0");
        parsedMetric = createMetricValue("0");
        unknownMetric = createMetricValue("0");
        parserMetric = createMetricValue("Select a file");
        detectOnlyCheckbox = new JCheckBox("Detect Only", true);
        styleCheckbox(detectOnlyCheckbox);
        refreshButton = createGhostButton("Refresh");
        parseButton = createPrimaryButton("Parse Logs");
        extractAppletsButton = createGhostButton("Extract Applets from APDUs");
        importButton = createGhostButton("Import Logs");
        registerButton = createGhostButton("Register Log Type");
        openInputButton = createGhostButton("Open Input Folder");
        openOutputButton = createGhostButton("Open Output Folder");
        removeSelectedButton = createGhostButton("Remove Selected");
        clearAllButton = createGhostButton("Clear All");
        toggleConsoleButton = createGhostButton("Show Console");
        filterTabs = new JTabbedPane();
        currentAnalysisItems = List.of();
        selectedFilter = ApduOutputAnalyzer.FilterMode.ALL;
        consoleVisible = false;

        leftColumnPanel = transparent();
        leftColumnPanel.setLayout(new BoxLayout(leftColumnPanel, BoxLayout.Y_AXIS));
        centerColumnPanel = transparent();
        centerColumnPanel.setLayout(new BoxLayout(centerColumnPanel, BoxLayout.Y_AXIS));
        responsiveColumnsPanel = new ResponsiveColumnsPanel();
        consoleCardPanel = consoleCard();

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(APP_BG);
        outer.setBorder(new EmptyBorder(4, 4, 4, 4));

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(4, 6, 6, 6));
        content.add(shell);

        shell.add(buildTopBar(), BorderLayout.NORTH);
        shell.add(buildCenter(), BorderLayout.CENTER);
        shell.add(buildFooter(), BorderLayout.SOUTH);

        JScrollPane rootScrollPane = new JScrollPane(content);
        rootScrollPane.setBorder(BorderFactory.createEmptyBorder());
        rootScrollPane.getViewport().setBackground(APP_BG);
        rootScrollPane.getVerticalScrollBar().setUnitIncrement(18);
        rootScrollPane.getHorizontalScrollBar().setUnitIncrement(18);

        outer.add(rootScrollPane, BorderLayout.CENTER);
        frame.setContentPane(outer);
        frame.setLocationRelativeTo(null);

        wireActions();
        refreshModeState();
        refreshAll();
        appendConsole("Desktop UI ready.");
        appendConsole("This machine has no JavaFX, so Swing is now the main UI.");
    }

    private JPanel buildTopBar() {
        JPanel topWrap = transparent(new BorderLayout(8, 8));

        RoundedPanel topBar = new RoundedPanel(Color.WHITE, 16, true);
        ResponsiveHeaderPanel topBarContent = new ResponsiveHeaderPanel(980);
        topBar.setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel left = transparent();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(trafficLights());
        left.add(Box.createHorizontalStrut(8));

        JPanel titleBox = transparent();
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(createLabel("IM / eSIM DEBUGGING TOOL", 9, BLUE, Font.BOLD));
        titleBox.add(Box.createVerticalStrut(1));
        titleBox.add(createLabel("APDU Parser Launcher", 20, TEXT, Font.BOLD));
        titleBox.add(Box.createVerticalStrut(1));
        titleBox.add(createLabel("Import logs, run parsers, inspect raw APDUs, and review analyzer output.", 11, MUTED, Font.PLAIN));
        left.add(titleBox);

        JPanel right = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(importButton);
        right.add(refreshButton);
        right.add(registerButton);
        right.add(openInputButton);
        right.add(openOutputButton);
        right.add(detectOnlyCheckbox);
        right.add(parseButton);
        right.add(extractAppletsButton);

        topBarContent.setLeading(left);
        topBarContent.setTrailing(right);
        topBar.setLayout(new BorderLayout());
        topBar.add(topBarContent, BorderLayout.CENTER);

        JPanel metrics = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        metrics.add(metricCard("Imported Logs", filesMetric));
        metrics.add(metricCard("Parsed Outputs", parsedMetric));
        metrics.add(metricCard("Unmatched", unknownMetric));
        metrics.add(metricCard("Detected Parser", parserMetric));

        topWrap.add(topBar, BorderLayout.NORTH);
        topWrap.add(metrics, BorderLayout.SOUTH);
        return topWrap;
    }

    private Component buildCenter() {
        leftColumnPanel.removeAll();
        leftColumnPanel.add(importCard());
        leftColumnPanel.add(Box.createVerticalStrut(8));
        leftColumnPanel.add(queueCard());

        centerColumnPanel.removeAll();
        centerColumnPanel.add(previewCard());
        centerColumnPanel.add(Box.createVerticalStrut(8));
        consoleCardPanel.setVisible(consoleVisible);
        centerColumnPanel.add(consoleCardPanel);

        return responsiveColumnsPanel;
    }

    private RoundedPanel importCard() {
        RoundedPanel card = cardPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(10, 10, 10, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.add(createLabel("Drop Customer Logs", 15, TEXT, Font.BOLD));
        card.add(Box.createVerticalStrut(4));
        card.add(createWrappedText("Drag .txt, .log, .html, or .htm files here, or click Import Logs. Supported files are copied into the tool inbox automatically.", 11, MUTED));
        card.add(Box.createVerticalStrut(8));

        RoundedPanel inner = new RoundedPanel(BLUE_SOFT, 14, false);
        dropTargetPanel = inner;
        inner.setLayout(new BorderLayout());
        inner.setPreferredSize(new Dimension(300, 58));
        inner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
        inner.setBorder(new EmptyBorder(10, 10, 10, 10));
        inner.add(createCenteredLabel("Drop files here or click Import Logs", 11, MUTED, Font.BOLD), BorderLayout.CENTER);
        inner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        inner.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                importFiles();
            }
        });
        installFileDropSupport(inner);
        card.add(inner);
        card.setMinimumSize(new Dimension(260, 142));
        card.setPreferredSize(new Dimension(286, 142));
        return card;
    }

    private RoundedPanel queueCard() {
        RoundedPanel card = cardPanel();
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new EmptyBorder(10, 10, 10, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        ResponsiveHeaderPanel header = new ResponsiveHeaderPanel(360);
        JLabel title = createLabel("Imported Logs", 15, TEXT, Font.BOLD);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        styleCompactButton(removeSelectedButton);
        styleCompactButton(clearAllButton);
        actions.add(removeSelectedButton);
        actions.add(clearAllButton);
        header.setLeading(title);
        header.setTrailing(actions);
        card.add(header, BorderLayout.NORTH);

        importedLogsScrollPane.setOpaque(true);
        importedLogsScrollPane.getViewport().setOpaque(true);
        card.add(importedLogsScrollPane, BorderLayout.CENTER);
        card.setMinimumSize(new Dimension(260, 220));
        card.setPreferredSize(new Dimension(286, 310));
        return card;
    }

    private RoundedPanel previewCard() {
        RoundedPanel card = cardPanel();
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new EmptyBorder(10, 10, 10, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.setMinimumSize(new Dimension(480, 360));
        card.setPreferredSize(new Dimension(840, 560));

        ResponsiveHeaderPanel header = new ResponsiveHeaderPanel(740);
        JPanel headerLeft = transparent();
        headerLeft.setLayout(new BoxLayout(headerLeft, BoxLayout.Y_AXIS));
        headerLeft.add(createLabel("APDU Inspection", 15, TEXT, Font.BOLD));
        headerLeft.add(Box.createVerticalStrut(3));
        selectionSummary.setText("Select a log to inspect parser output, ES10 operations, FETCH/TR traffic, and LSI activity.");
        headerLeft.add(selectionSummary);

        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        FlatButton copy = createGhostButton("Copy");
        copy.addActionListener(e -> copyPreview());
        FlatButton export = createGhostButton("Open Output");
        export.addActionListener(e -> openFolder(engine.getOutputDir()));
        toggleConsoleButton.addActionListener(e -> toggleConsoleVisibility());
        copy.setPreferredSize(new Dimension(68, 30));
        export.setPreferredSize(new Dimension(98, 30));
        toggleConsoleButton.setPreferredSize(new Dimension(104, 30));
        actions.add(toggleConsoleButton);
        actions.add(copy);
        actions.add(export);
        header.setLeading(headerLeft);
        header.setTrailing(actions);

        JPanel content = transparent(new BorderLayout(0, 6));
        content.add(buildFilterTabs(), BorderLayout.NORTH);

        JScrollPane rawScroll = new JScrollPane(previewArea);
        rawScroll.setBorder(BorderFactory.createEmptyBorder());
        rawScroll.getViewport().setBackground(CODE_BG);
        JScrollPane analysisScroll = new JScrollPane(analysisArea);
        analysisScroll.setBorder(BorderFactory.createEmptyBorder());
        analysisScroll.getViewport().setBackground(CODE_BG);
        JScrollPane appletScroll = new JScrollPane(appletArea);
        appletScroll.setBorder(BorderFactory.createEmptyBorder());
        appletScroll.getViewport().setBackground(CODE_BG);

        outputTabs.removeAll();
        outputTabs.addTab("Raw APDU", rawScroll);
        outputTabs.addTab("Enhanced Analysis", analysisScroll);
        outputTabs.addTab("Applet Extraction", appletScroll);
        outputTabs.setFont(new Font("Segoe UI", Font.BOLD, 11));
        outputTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        outputTabs.setForeground(TEXT);
        outputTabs.setBackground(Color.WHITE);
        content.add(outputTabs, BorderLayout.CENTER);

        card.add(header, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private JTabbedPane buildFilterTabs() {
        if (filterTabs.getTabCount() == 0) {
            filterTabs.setFont(new Font("Segoe UI", Font.BOLD, 13));
            filterTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            filterTabs.setBackground(Color.WHITE);
            filterTabs.setForeground(TEXT);
            for (ApduOutputAnalyzer.FilterMode filterMode : ApduOutputAnalyzer.FilterMode.values()) {
                JPanel placeholder = new JPanel();
                placeholder.setOpaque(false);
                placeholder.setPreferredSize(new Dimension(0, 0));
                filterTabs.addTab(filterMode.getLabel(), placeholder);
            }
            filterTabs.addChangeListener(e -> {
                int index = filterTabs.getSelectedIndex();
                if (index >= 0 && index < ApduOutputAnalyzer.FilterMode.values().length) {
                    selectedFilter = ApduOutputAnalyzer.FilterMode.values()[index];
                    renderAnalysisPreview();
                    selectionSummary.setText(buildSelectionSummary());
                }
            });
        }
        filterTabs.setSelectedIndex(selectedFilter.ordinal());
        return filterTabs;
    }

    private RoundedPanel consoleCard() {
        RoundedPanel card = cardPanel();
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(10, 10, 10, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel header = transparent(new BorderLayout());
        header.add(createLabel("Processing Console", 13, TEXT, Font.BOLD), BorderLayout.WEST);
        FlatButton clear = createGhostButton("Clear");
        clear.addActionListener(e -> consoleArea.setText(""));
        header.add(clear, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(consoleArea);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(245, 248, 252));

        card.add(header, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        card.setMinimumSize(new Dimension(480, 88));
        card.setPreferredSize(new Dimension(840, 106));
        return card;
    }

    private Component buildFooter() {
        JPanel footer = transparent(new BorderLayout());
        footer.setBorder(new EmptyBorder(4, 4, 0, 4));
        footer.add(createLabel("QA APDU debugger workspace", 12, MUTED, Font.PLAIN), BorderLayout.WEST);
        footer.add(statusLabel, BorderLayout.EAST);
        return footer;
    }

    private void wireActions() {
        importButton.addActionListener(e -> importFiles());
        refreshButton.addActionListener(e -> {
            appendConsole("Manual refresh requested.");
            refreshAll();
        });
        removeSelectedButton.addActionListener(e -> removeSelectedImportedLog());
        clearAllButton.addActionListener(e -> clearAllImportedLogs());
        registerButton.addActionListener(e -> addParserType());
        openInputButton.addActionListener(e -> openFolder(engine.getInputDir()));
        openOutputButton.addActionListener(e -> openFolder(engine.getOutputDir()));
        parseButton.addActionListener(e -> runParser());
        extractAppletsButton.addActionListener(e -> runAppletExtraction());
        detectOnlyCheckbox.addActionListener(e -> refreshModeState());
    }

    private void importFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        int result = chooser.showOpenDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        List<Path> files = new ArrayList<>();
        for (java.io.File file : chooser.getSelectedFiles()) {
            files.add(file.toPath());
        }

        importFiles(files);
    }

    private void importFiles(List<Path> files) {
        try {
            List<Path> accepted = new ArrayList<>();
            List<Path> rejected = new ArrayList<>();
            for (Path file : files) {
                if (isSupportedImportFile(file)) {
                    accepted.add(file);
                } else {
                    rejected.add(file);
                }
            }

            if (!accepted.isEmpty()) {
                engine.importFiles(accepted);
                appendConsole("Imported " + accepted.size() + " supported file(s).");
            }
            if (!rejected.isEmpty()) {
                appendConsole("Skipped unsupported file(s): " + joinFileNames(rejected));
            }
            if (accepted.isEmpty() && !rejected.isEmpty()) {
                statusLabel.setText("No supported log files were imported");
            }
            refreshAll();
        } catch (Exception ex) {
            showError("Import failed", ex);
        }
    }

    private void addParserType() {
        ApduParserEngine.ParserDefinition template = suggestParserTemplate();
        JDialog dialog = buildRegisterLogTypeDialog(template);
        dialog.setVisible(true);
    }

    private JDialog buildRegisterLogTypeDialog(ApduParserEngine.ParserDefinition template) {
        JTextField nameField = new JTextField(valueOrDefault(template == null ? null : template.getName(), ""));
        JTextField folderField = new JTextField(valueOrDefault(template == null ? null : template.getExtractorFolder(), "../new_vendor_extractor"));
        JTextField scriptField = new JTextField(valueOrDefault(template == null ? null : template.getScriptFile(), "script.java"));
        JTextField extensionsField = new JTextField(String.join(",", template == null ? List.of(".txt", ".log") : template.getExtensions()));
        JTextField stagedScriptField = new JTextField(valueOrDefault(template == null ? null : template.getStagedScriptFileName(), "script.java"));
        JTextField stagedInputField = new JTextField(valueOrDefault(template == null ? null : template.getStagedInputFileName(), "input.log"));
        JTextField stagedOutputField = new JTextField(valueOrDefault(template == null ? null : template.getStagedOutputFileName(), "output.txt"));
        JTextField outputExtensionField = new JTextField(valueOrDefault(template == null ? null : template.getOutputExtension(), ".txt"));
        JTextField regexField = new JTextField(template == null ? "" : template.getFileNameRegex());
        JTextField commandArgsField = new JTextField(String.join(",", template == null ? List.of("{input}", "{output}") : template.getCommandArgs()));

        String defaultDetectionMode = valueOrDefault(template == null ? null : template.getDetectionMode(), "all");
        List<String> defaultPatterns = template == null ? List.of() : template.getPatterns();

        JDialog dialog = new JDialog(frame, "Register Log Type", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(true);
        dialog.setMinimumSize(new Dimension(720, 560));

        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBackground(SHELL_BG);
        root.setBorder(new EmptyBorder(18, 18, 18, 18));

        JPanel intro = new JPanel();
        intro.setOpaque(false);
        intro.setLayout(new BoxLayout(intro, BoxLayout.Y_AXIS));
        intro.add(createDialogTitle("Add New Parser"));
        intro.add(Box.createVerticalStrut(8));
        intro.add(createDialogHint("Register a parser by selecting its extractor folder, main Java script, and supported file extensions. Advanced settings stay hidden unless you need them."));
        if (template != null) {
            intro.add(Box.createVerticalStrut(6));
            intro.add(createDialogHint("This wizard was prefilled from the currently detected parser to make duplication faster."));
        }
        intro.add(Box.createVerticalStrut(6));
        intro.add(createDialogHint("Most QA users only need the basic fields. Advanced settings are for staging names and optional extractor arguments."));
        root.add(intro, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(4, 2, 4, 2));

        form.add(createSectionTitle("Basic Parser Info"));
        form.add(Box.createVerticalStrut(10));
        JPanel basicSection = new JPanel(new GridBagLayout());
        basicSection.setOpaque(false);
        basicSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        addFormRow(basicSection, 0, "Parser name", "Required. Friendly parser id shown in the launcher and used for the output folder.", nameField);
        addFormRow(basicSection, 1, "Supported extensions", "Required. Examples: .log, .txt, .html", extensionsField);
        form.add(basicSection);
        form.add(Box.createVerticalStrut(18));

        form.add(createSectionTitle("Extractor Files"));
        form.add(Box.createVerticalStrut(10));
        JPanel extractorSection = new JPanel(new GridBagLayout());
        extractorSection.setOpaque(false);
        extractorSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        addFormRow(extractorSection, 0, "Extractor folder", "Required. Relative folder containing the extractor implementation.", folderField);
        addFormRow(extractorSection, 1, "Main Java extractor script", "Required. Java file that launches the parser logic.", scriptField);
        form.add(extractorSection);
        form.add(Box.createVerticalStrut(18));

        JCheckBox advancedToggle = new JCheckBox("Show Advanced Settings");
        advancedToggle.setOpaque(false);
        advancedToggle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        advancedToggle.setForeground(TEXT);
        advancedToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(advancedToggle);
        form.add(Box.createVerticalStrut(10));

        JPanel advancedSection = new JPanel(new GridBagLayout());
        advancedSection.setOpaque(false);
        advancedSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        addFormRow(advancedSection, 0, "Staged script file", "Optional. Temporary Java file name used during execution. Defaults to the main script file name.", stagedScriptField);
        addFormRow(advancedSection, 1, "Staged input file", "Optional. Temporary input file name expected by the extractor.", stagedInputField);
        addFormRow(advancedSection, 2, "Staged output file", "Optional. Temporary output file name produced by the extractor.", stagedOutputField);
        addFormRow(advancedSection, 3, "Output extension", "Optional. Final copied output extension, usually .txt.", outputExtensionField);
        addFormRow(advancedSection, 4, "File regex", "Optional. Extra file-name filter applied before matching.", regexField);
        addFormRow(advancedSection, 5, "Command args", "Optional. Extra extractor args. Supported placeholders: {input}, {output}.", commandArgsField);

        RoundedPanel advancedCard = new RoundedPanel(new Color(252, 253, 255), 18, true);
        advancedCard.setLayout(new BorderLayout());
        advancedCard.setBorder(new EmptyBorder(14, 14, 8, 14));
        advancedCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        advancedCard.add(advancedSection, BorderLayout.CENTER);
        advancedCard.setVisible(false);
        form.add(advancedCard);

        advancedToggle.addActionListener(e -> {
            advancedCard.setVisible(advancedToggle.isSelected());
            dialog.revalidate();
        });

        JScrollPane scrollPane = new JScrollPane(form);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(SHELL_BG);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        root.add(scrollPane, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(4, 0, 0, 0));
        footer.add(createDialogHint("Save writes the new parser into config.json immediately. Cancel closes the dialog without changing anything."), BorderLayout.WEST);

        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        FlatButton cancel = createGhostButton("Cancel");
        FlatButton save = createPrimaryButton("Save");
        cancel.setPreferredSize(new Dimension(120, 40));
        save.setPreferredSize(new Dimension(120, 40));
        actions.add(cancel);
        actions.add(save);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        cancel.addActionListener(e -> dialog.dispose());
        save.addActionListener(e -> {
            try {
                String parserName = nameField.getText().trim();
                List<String> supportedExtensions = splitCsv(extensionsField.getText());
                String stagedScriptName = valueOrDefault(stagedScriptField.getText().trim(), scriptField.getText().trim());
                String stagedInputName = valueOrDefault(stagedInputField.getText().trim(), "input.log");
                String stagedOutputName = valueOrDefault(stagedOutputField.getText().trim(), "output.txt");
                engine.addParserDefinition(new ApduParserEngine.ParserDefinition(
                        parserName,
                        folderField.getText().trim(),
                        scriptField.getText().trim(),
                        stagedScriptName,
                        stagedInputName,
                        stagedOutputName,
                        outputExtensionField.getText().trim(),
                        defaultDetectionMode,
                        defaultPatterns,
                        supportedExtensions,
                        regexField.getText().trim(),
                        splitCsv(commandArgsField.getText())
                ));
                appendConsole("Registered new log type successfully: " + parserName);
                appendConsole("Saved parser configuration to " + engine.getLauncherRoot().resolve("config.json"));
                refreshAll();
                dialog.dispose();
                JOptionPane.showMessageDialog(
                        frame,
                        "Registered parser '" + parserName + "' in config.json.\nImport a sample log and click Parse Logs to test it.",
                        "Register Log Type",
                        JOptionPane.INFORMATION_MESSAGE
                );
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                        dialog,
                        "Add parser failed:\n" + ex.getMessage(),
                        "Register Log Type",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });

        dialog.setContentPane(root);
        dialog.setSize(820, 760);
        dialog.setLocationRelativeTo(frame);
        return dialog;
    }

    private void runParser() {
        setBusy(true);
        appendConsole("==================================================");
        appendConsole("Starting parser. detectOnly=" + detectOnlyCheckbox.isSelected());

        SwingWorker<List<ApduParserEngine.RunResult>, String> worker = new SwingWorker<List<ApduParserEngine.RunResult>, String>() {
            @Override
            protected List<ApduParserEngine.RunResult> doInBackground() throws Exception {
                return engine.processAll(detectOnlyCheckbox.isSelected(), this::publishOne);
            }

            private void publishOne(String line) {
                publish(line);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    appendConsole(chunk);
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                    appendConsole("Processing finished.");
                    refreshAll();
                } catch (Exception ex) {
                    appendConsole("Processing failed: " + ex.getMessage());
                    showError("Run failed", ex);
                } finally {
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private void runAppletExtraction() {
        if (selectedImportedFile == null) {
            appletArea.setText("No APDUs available for applet extraction.");
            outputTabs.setSelectedIndex(2);
            appendConsole("Applet extraction skipped: no imported log is selected.");
            return;
        }

        setBusy(true);
        appendConsole("==================================================");
        appendConsole("Starting applet extraction from extracted APDUs.");

        SwingWorker<ApduParserEngine.AppletExtractionResult, String> worker = new SwingWorker<ApduParserEngine.AppletExtractionResult, String>() {
            @Override
            protected ApduParserEngine.AppletExtractionResult doInBackground() throws Exception {
                return engine.extractApplets(selectedImportedFile, this::publishOne);
            }

            private void publishOne(String line) {
                publish(line);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    appendConsole(chunk);
                }
            }

            @Override
            protected void done() {
                try {
                    ApduParserEngine.AppletExtractionResult result = get();
                    if (result.hasApplets()) {
                        appendConsole("Applet extraction finished.");
                    } else {
                        appendConsole("Applet extraction finished: no applet data found.");
                    }
                    refreshAll();
                    outputTabs.setSelectedIndex(2);
                } catch (Exception ex) {
                    String message = ex.getMessage() == null ? "Applet extraction failed." : ex.getMessage();
                    appletArea.setText(message);
                    outputTabs.setSelectedIndex(2);
                    appendConsole("Applet extraction failed: " + message);
                    showError("Applet extraction failed", ex);
                } finally {
                    setBusy(false);
                }
            }
        };
        worker.execute();
    }

    private void refreshAll() {
        try {
            List<Path> files = engine.listInputFiles();
            rebuildImportedLogsList(files);

            int unknownCount = countVisibleFiles(engine.getUnknownDir());
            int outputCount = countRawOutputFiles(engine.getOutputDir());
            filesMetric.setText(String.valueOf(files.size()));
            parsedMetric.setText(String.valueOf(Math.max(0, outputCount - unknownCount)));
            unknownMetric.setText(String.valueOf(unknownCount));

            statusLabel.setText("Input: " + engine.getInputDir());
            if (selectedImportedFile == null && !files.isEmpty()) {
                selectedImportedFile = files.get(0);
            } else if (selectedImportedFile != null && !files.contains(selectedImportedFile)) {
                selectedImportedFile = files.isEmpty() ? null : files.get(0);
            }
            removeSelectedButton.setEnabled(selectedImportedFile != null);
            clearAllButton.setEnabled(!files.isEmpty());
            extractAppletsButton.setEnabled(selectedImportedFile != null);
            updateDetectionAndPreview();
        } catch (Exception ex) {
            statusLabel.setText("Refresh failed: " + ex.getMessage());
        }
    }

    private void updateDetectionAndPreview() {
        Path selected = selectedImportedFile;
        if (selected == null) {
            parserMetric.setText("Select a file");
            selectionSummary.setText(buildSelectionSummary());
            previewArea.setText("");
            analysisArea.setText("");
            appletArea.setText("No APDUs available for applet extraction.");
            currentAnalysisItems = List.of();
            return;
        }

        try {
            ApduParserEngine.DetectionResult detection = engine.detectParser(selected);
            if (detection.matched()) {
                parserMetric.setText(detection.getParserName());
            } else {
                parserMetric.setText("No match");
            }
            selectionSummary.setText(buildSelectionSummary());

            Path rawPreviewTarget = resolveRawPreviewTarget(selected, detection);
            if (rawPreviewTarget != null && Files.exists(rawPreviewTarget)) {
                previewArea.setText(engine.readFilePreview(rawPreviewTarget, 24000));
                previewArea.setCaretPosition(0);
            } else {
                previewArea.setText("No output yet. Click Parse Logs to generate APDU output.");
            }

            if (detection.matched() && rawPreviewTarget != null && Files.exists(rawPreviewTarget)) {
                currentAnalysisItems = ApduOutputAnalyzer.analyzeEntries(selected, rawPreviewTarget);
                renderAnalysisPreview();
                renderAppletPreview(selected, detection);
            } else {
                currentAnalysisItems = List.of();
                analysisArea.setText("Enhanced analysis is available after a parser match and extraction run.");
                appletArea.setText("No APDUs available for applet extraction.");
            }
        } catch (Exception ex) {
            parserMetric.setText("Error");
            selectionSummary.setText("The selected log could not be analyzed.");
            previewArea.setText(ex.getMessage());
            analysisArea.setText(ex.getMessage());
            appletArea.setText(ex.getMessage());
            currentAnalysisItems = List.of();
        }
    }

    private void rebuildImportedLogsList(List<Path> files) {
        importedLogsListPanel.removeAll();
        if (files.isEmpty()) {
            importedLogsListPanel.add(createEmptyLogsState());
        } else {
            for (Path file : files) {
                importedLogsListPanel.add(createImportedLogRow(file));
                importedLogsListPanel.add(Box.createVerticalStrut(8));
            }
        }
        importedLogsListPanel.revalidate();
        importedLogsListPanel.repaint();
    }

    private Component createEmptyLogsState() {
        JTextArea empty = createInfoText();
        empty.setText("No imported logs yet.");
        empty.setBorder(new EmptyBorder(12, 6, 12, 6));
        return empty;
    }

    private Component createImportedLogRow(Path file) {
        RoundedPanel row = new RoundedPanel(selected(file) ? new Color(234, 244, 255) : Color.WHITE, 16, true);
        row.setLayout(new BorderLayout(10, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        row.setBorder(new EmptyBorder(4, 8, 4, 4));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel label = createLabel(file.getFileName().toString(), 11, TEXT, Font.PLAIN);
        row.add(label, BorderLayout.CENTER);

        FlatButton delete = createGhostButton("Delete");
        delete.setPreferredSize(new Dimension(62, 26));
        delete.addActionListener(e -> removeImportedLog(file));
        row.add(delete, BorderLayout.EAST);

        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectedImportedFile = file;
                refreshAll();
            }
        });
        return row;
    }

    private boolean selected(Path file) {
        return selectedImportedFile != null && selectedImportedFile.equals(file);
    }

    private void removeSelectedImportedLog() {
        if (selectedImportedFile == null) {
            appendConsole("Remove Selected ignored: no file is selected.");
            return;
        }
        removeImportedLog(selectedImportedFile);
    }

    private void clearAllImportedLogs() {
        try {
            int deleted = engine.clearImportedFiles();
            selectedImportedFile = null;
            appendConsole("Cleared " + deleted + " imported log(s).");
            refreshAll();
        } catch (Exception ex) {
            showError("Clear logs failed", ex);
        }
    }

    private void removeImportedLog(Path file) {
        try {
            boolean deleted = engine.deleteImportedFile(file);
            if (deleted) {
                appendConsole("Removed imported log: " + file.getFileName());
            }
            if (file.equals(selectedImportedFile)) {
                selectedImportedFile = null;
            }
            refreshAll();
        } catch (Exception ex) {
            showError("Remove log failed", ex);
        }
    }

    private Path resolveRawPreviewTarget(Path selected, ApduParserEngine.DetectionResult detection) {
        if (!detection.matched()) {
            return engine.getUnknownDir().resolve(selected.getFileName());
        }
        String fileName = selected.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return engine.getOutputDir().resolve(detection.getParserName()).resolve(base + ".txt");
    }

    private void renderAppletPreview(Path selected, ApduParserEngine.DetectionResult detection) {
        if (!detection.matched()) {
            appletArea.setText("No APDUs available for applet extraction.");
            return;
        }
        try {
            Path appletDir = engine.resolveAppletOutputDir(selected, detection.getParserName());
            if (!Files.exists(appletDir)) {
                appletArea.setText("Click Extract Applets from APDUs to search for applet data.");
                return;
            }
            appletArea.setText(engine.readAppletPreview(appletDir, 24000));
            appletArea.setCaretPosition(0);
        } catch (Exception ex) {
            appletArea.setText(ex.getMessage());
        }
    }

    private void setBusy(boolean busy) {
        parseButton.setEnabled(!busy);
        extractAppletsButton.setEnabled(!busy && selectedImportedFile != null);
        importButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
        openInputButton.setEnabled(!busy);
        openOutputButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        removeSelectedButton.setEnabled(!busy && selectedImportedFile != null);
        clearAllButton.setEnabled(!busy && !"0".equals(filesMetric.getText()));
        detectOnlyCheckbox.setEnabled(!busy);
        statusLabel.setText(busy ? "Running..." : statusLabel.getText());
    }

    private void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception ex) {
            showError("Open folder failed", ex);
        }
    }

    private void copyPreview() {
        JTextArea activeArea;
        int selectedIndex = outputTabs.getSelectedIndex();
        if (selectedIndex == 1) {
            activeArea = analysisArea;
        } else if (selectedIndex == 2) {
            activeArea = appletArea;
        } else {
            activeArea = previewArea;
        }
        activeArea.selectAll();
        activeArea.copy();
        activeArea.select(0, 0);
        statusLabel.setText("Preview copied");
    }

    private void appendConsole(String text) {
        consoleArea.append(text + System.lineSeparator());
        consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
    }

    private RoundedPanel cardPanel() {
        return new RoundedPanel(CARD_BG, 26, true);
    }

    private JPanel trafficLights() {
        JPanel lights = transparent(new FlowLayout(FlowLayout.LEFT, 5, 6));
        lights.add(dot(new Color(255, 95, 87)));
        lights.add(dot(new Color(254, 188, 46)));
        lights.add(dot(new Color(40, 200, 64)));
        return lights;
    }

    private Component dot(Color color) {
        RoundedPanel dot = new RoundedPanel(color, 99, false);
        dot.setPreferredSize(new Dimension(10, 10));
        dot.setMaximumSize(new Dimension(10, 10));
        return dot;
    }

    private JLabel createLabel(String text, int size, Color color, int style) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", style, size));
        label.setForeground(color);
        return label;
    }

    private JTextArea createWrappedText(String text, int size, Color color) {
        JTextArea area = createInfoText();
        area.setFont(new Font("Segoe UI", Font.PLAIN, size));
        area.setForeground(color);
        area.setText(text);
        return area;
    }

    private JTextArea createInfoText() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        area.setForeground(MUTED);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    private JLabel createCenteredLabel(String text, int size, Color color, int style) {
        JLabel label = createLabel(text, size, color, style);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private JLabel createMetricValue(String text) {
        return createLabel(text, 18, TEXT, Font.BOLD);
    }

    private RoundedPanel metricCard(String title, JLabel valueLabel) {
        RoundedPanel card = cardPanel();
        card.setPreferredSize(new Dimension(168, 68));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));
        card.add(createLabel(title, 10, MUTED, Font.PLAIN));
        card.add(Box.createVerticalStrut(4));
        card.add(valueLabel);
        return card;
    }

    private JLabel createBadge(String text) {
        JLabel label = createLabel("  " + text + "  ", 13, new Color(21, 98, 215), Font.BOLD);
        label.setOpaque(true);
        label.setBackground(new Color(235, 244, 255));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(208, 226, 255), 1, true),
                new EmptyBorder(10, 12, 10, 12)
        ));
        return label;
    }

    private void styleCheckbox(JCheckBox checkBox) {
        checkBox.setFont(new Font("Segoe UI", Font.BOLD, 11));
        checkBox.setForeground(TEXT);
        checkBox.setOpaque(false);
        checkBox.setFocusPainted(false);
        checkBox.setBorder(new EmptyBorder(0, 4, 0, 4));
    }

    private JTextArea createTextArea(boolean light, Color bg, Color fg, boolean mono) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(!mono);
        area.setWrapStyleWord(!mono);
        area.setBackground(bg);
        area.setForeground(fg);
        area.setCaretColor(fg);
        area.setBorder(new EmptyBorder(10, 10, 10, 10));
        area.setFont(new Font(mono ? Font.MONOSPACED : "Segoe UI", Font.PLAIN, mono ? 11 : 10));
        return area;
    }

    private FlatButton createPrimaryButton(String text) {
        return new FlatButton(text, BLUE, Color.WHITE, new Color(16, 99, 223));
    }

    private FlatButton createGhostButton(String text) {
        return new FlatButton(text, new Color(245, 248, 252), TEXT, CARD_BORDER);
    }

    private void refreshModeState() {
        statusLabel.setText(detectOnlyCheckbox.isSelected()
                ? "Detect-only mode enabled"
                : "Execute mode enabled");
    }

    private void styleWideButton(FlatButton button) {
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private void styleCompactButton(FlatButton button) {
        button.setPreferredSize(new Dimension(118, 28));
        button.setMinimumSize(new Dimension(118, 28));
    }

    private void toggleConsoleVisibility() {
        consoleVisible = !consoleVisible;
        consoleCardPanel.setVisible(consoleVisible);
        toggleConsoleButton.setText(consoleVisible ? "Hide Console" : "Show Console");
        centerColumnPanel.revalidate();
        centerColumnPanel.repaint();
    }

    private void renderAnalysisPreview() {
        if (currentAnalysisItems == null || currentAnalysisItems.isEmpty()) {
            analysisArea.setText("No enhanced analysis output yet. Click Parse Logs to generate the APDU timeline.");
            return;
        }
        analysisArea.setText(ApduOutputAnalyzer.renderEnhancedOutput(currentAnalysisItems, selectedFilter));
        analysisArea.setCaretPosition(0);
    }

    private String buildSelectionSummary() {
        if (selectedImportedFile == null) {
            return "Select a log to inspect raw APDUs, the enhanced analysis timeline, and optional applet extraction results.";
        }
        String parserName = parserMetric.getText();
        return "Selected: "
                + selectedImportedFile.getFileName()
                + "  |  Parser: "
                + parserName
                + "  |  Step: Import -> Detect -> Parse -> Extract Applets"
                + "  |  View: "
                + selectedFilter.getLabel();
    }

    private JPanel labeledField(String label, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(0, 0, 8, 0));
        panel.add(createLabel(label, 12, MUTED, Font.PLAIN), BorderLayout.NORTH);
        field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        panel.add(field, BorderLayout.CENTER);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        return panel;
    }

    private void addFormRow(JPanel form, int row, String labelText, String helperText, JTextField field) {
        styleDialogField(field);
        field.setToolTipText(helperText);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(0, 0, 14, 0);

        JPanel rowPanel = new JPanel();
        rowPanel.setOpaque(false);
        rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.Y_AXIS));

        JLabel label = createLabel(labelText, 13, TEXT, Font.BOLD);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowPanel.add(label);
        rowPanel.add(Box.createVerticalStrut(4));
        rowPanel.add(createDialogHint(helperText));
        rowPanel.add(Box.createVerticalStrut(8));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowPanel.add(field);

        form.add(rowPanel, gbc);
    }

    private void styleDialogField(JTextField field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(208, 218, 231), 1, true),
                new EmptyBorder(10, 12, 10, 12)
        ));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        field.setPreferredSize(new Dimension(320, 42));
    }

    private JPanel createDialogHint(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(createWrappedText(text, 12, MUTED), BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JLabel createDialogTitle(String text) {
        JLabel title = createLabel(text, 20, TEXT, Font.BOLD);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        return title;
    }

    private JLabel createSectionTitle(String text) {
        JLabel label = createLabel(text, 15, TEXT, Font.BOLD);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private ApduParserEngine.ParserDefinition suggestParserTemplate() {
        Path selected = selectedImportedFile;
        if (selected == null) {
            return null;
        }
        try {
            ApduParserEngine.DetectionResult detection = engine.detectParser(selected);
            if (!detection.matched()) {
                return null;
            }
            for (ApduParserEngine.ParserDefinition definition : engine.listParserDefinitions()) {
                if (detection.getParserName().equals(definition.getName())) {
                    return definition;
                }
            }
        } catch (Exception ignored) {
            // Keep the dialog usable if template lookup fails.
        }
        return null;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void installFileDropSupport(JComponent component) {
        if (component == null) {
            return;
        }
        component.setTransferHandler(new FileDropHandler());
        installNativeFileDropSupport(component);
    }

    private void installNativeFileDropSupport(JComponent component) {
        if (component == null) {
            return;
        }
        component.setDropTarget(new DropTarget(component, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent event) {
                if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dropTargetPanel.setBackground(new Color(226, 235, 246));
                    statusLabel.setText("Drop log files to import");
                    event.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    event.rejectDrag();
                }
            }

            @Override
            public void dragExit(java.awt.dnd.DropTargetEvent event) {
                dropTargetPanel.setBackground(BLUE_SOFT);
                statusLabel.setText("Ready");
            }

            @Override
            public void drop(DropTargetDropEvent event) {
                handleNativeFileDrop(event);
            }
        }, true));
    }

    private void handleNativeFileDrop(DropTargetDropEvent event) {
        try {
            event.acceptDrop(DnDConstants.ACTION_COPY);
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                event.dropComplete(false);
                statusLabel.setText("Only file drops are supported");
                return;
            }
            @SuppressWarnings("unchecked")
            List<java.io.File> droppedFiles = (List<java.io.File>) event.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
            List<Path> paths = new ArrayList<>();
            for (java.io.File file : droppedFiles) {
                paths.add(file.toPath());
            }
            if (paths.isEmpty()) {
                statusLabel.setText("No files were dropped");
                dropTargetPanel.setBackground(BLUE_SOFT);
                event.dropComplete(false);
                return;
            }
            importFiles(paths);
            dropTargetPanel.setBackground(BLUE_SOFT);
            event.dropComplete(true);
        } catch (Exception ex) {
            dropTargetPanel.setBackground(BLUE_SOFT);
            appendConsole("Native drag-and-drop import failed: " + ex.getMessage());
            showError("Drag-and-drop failed", ex);
            event.dropComplete(false);
        }
    }

    private boolean isSupportedImportFile(Path file) {
        if (file == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : supportedImportExtensions()) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> supportedImportExtensions() {
        Set<String> extensions = new LinkedHashSet<>();
        extensions.add(".txt");
        extensions.add(".log");
        extensions.add(".html");
        extensions.add(".htm");
        try {
            for (ApduParserEngine.ParserDefinition definition : engine.listParserDefinitions()) {
                for (String extension : definition.getExtensions()) {
                    if (extension != null && !extension.isBlank()) {
                        String normalized = extension.toLowerCase(Locale.ROOT);
                        extensions.add(normalized.startsWith(".") ? normalized : "." + normalized);
                    }
                }
            }
        } catch (Exception ignored) {
            // Keep the fallback extensions even if config loading fails.
        }
        return extensions;
    }

    private String joinFileNames(List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path file : files) {
            names.add(file.getFileName().toString());
        }
        return String.join(", ", names);
    }

    private List<String> splitCsv(String text) {
        List<String> values = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return values;
        }
        String[] parts = text.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static int countVisibleFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return 0;
        }
        int count = 0;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path) && !name.startsWith(".")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countRawOutputFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return 0;
        }
        int count = 0;
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path) && !name.startsWith(".") && !name.contains(".analysis.")) {
                    count++;
                }
            }
        }
        return count;
    }

    private void show() {
        frame.setVisible(true);
    }

    private void showError(String title, Exception ex) {
        JOptionPane.showMessageDialog(frame, title + ":\n" + ex.getMessage(), "APDU Parser Launcher", JOptionPane.ERROR_MESSAGE);
    }

    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return false;
            }
            support.setDropAction(COPY);
            return true;
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                @SuppressWarnings("unchecked")
                List<java.io.File> droppedFiles = (List<java.io.File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> paths = new ArrayList<>();
                for (java.io.File file : droppedFiles) {
                    paths.add(file.toPath());
                }
                if (paths.isEmpty()) {
                    statusLabel.setText("No files were dropped");
                    return false;
                }
                importFiles(paths);
                return true;
            } catch (Exception ex) {
                appendConsole("Drag-and-drop import failed: " + ex.getMessage());
                showError("Drag-and-drop failed", ex);
                return false;
            }
        }
    }

    private JPanel transparent() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        return panel;
    }

    private JPanel transparent(BorderLayout layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private JPanel transparent(FlowLayout layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private final class ResponsiveColumnsPanel extends JPanel {
        private ResponsiveColumnsPanel() {
            setOpaque(false);
            setLayout(new BorderLayout());
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    applyResponsiveLayout();
                }
            });
            applyResponsiveLayout();
        }

        private void applyResponsiveLayout() {
            removeAll();
            int width = Math.max(getWidth(), frame.getWidth() - 120);
            if (width < RESPONSIVE_STACK_WIDTH) {
                JPanel stack = transparent();
                stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
                stack.add(leftColumnPanel);
                stack.add(Box.createVerticalStrut(8));
                stack.add(centerColumnPanel);
                add(stack, BorderLayout.CENTER);
            } else {
                JPanel row = transparent(new BorderLayout(10, 0));
                leftColumnPanel.setPreferredSize(new Dimension(270, leftColumnPanel.getPreferredSize().height));
                row.add(leftColumnPanel, BorderLayout.WEST);
                row.add(centerColumnPanel, BorderLayout.CENTER);
                add(row, BorderLayout.CENTER);
            }
            revalidate();
            repaint();
        }
    }

    private final class ResponsiveHeaderPanel extends JPanel {
        private final int stackThreshold;
        private Component leading;
        private Component trailing;

        private ResponsiveHeaderPanel(int stackThreshold) {
            this.stackThreshold = stackThreshold;
            setOpaque(false);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    applyLayoutMode();
                }
            });
        }

        private void setLeading(Component leading) {
            this.leading = leading;
            applyLayoutMode();
        }

        private void setTrailing(Component trailing) {
            this.trailing = trailing;
            applyLayoutMode();
        }

        private void applyLayoutMode() {
            removeAll();
            if (leading == null || trailing == null) {
                revalidate();
                repaint();
                return;
            }

            int width = Math.max(getWidth(), 1);
            if (width < stackThreshold) {
                setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
                if (leading instanceof javax.swing.JComponent leadingComponent) {
                    leadingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
                }
                if (trailing instanceof javax.swing.JComponent trailingComponent) {
                    trailingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
                }
                add(leading);
                add(Box.createVerticalStrut(12));
                add(trailing);
            } else {
                setLayout(new BorderLayout(16, 0));
                add(leading, BorderLayout.WEST);
                add(trailing, BorderLayout.EAST);
            }
            revalidate();
            repaint();
        }
    }

    private static final class FlatButton extends JButton {
        private final Color fill;
        private final Color text;
        private final Color border;

        private FlatButton(String label, Color fill, Color text, Color border) {
            super(label);
            this.fill = fill;
            this.text = text;
            this.border = border;
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(text);
            setFont(new Font("Segoe UI", Font.BOLD, 11));
            setBorder(new EmptyBorder(6, 12, 6, 12));
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean enabled = isEnabled();
            boolean pressed = getModel().isPressed();
            boolean hover = getModel().isRollover();

            Color bg = fill;
            Color stroke = border;
            Color fg = text;

            if (!enabled) {
                bg = withAlpha(fill, 120);
                stroke = withAlpha(border, 120);
                fg = withAlpha(text, 140);
            } else if (pressed) {
                bg = darken(fill, 0.12f);
                stroke = darken(border, 0.15f);
            } else if (hover) {
                bg = lighten(fill, 0.04f);
                stroke = darken(border, 0.06f);
            }

            setForeground(fg);

            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 14, 14));
            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2f, getHeight() - 2f, 14, 14));
            g2.dispose();
            super.paintComponent(g);
        }

        private Color lighten(Color color, float amount) {
            int r = Math.min(255, color.getRed() + Math.round((255 - color.getRed()) * amount));
            int g = Math.min(255, color.getGreen() + Math.round((255 - color.getGreen()) * amount));
            int b = Math.min(255, color.getBlue() + Math.round((255 - color.getBlue()) * amount));
            return new Color(r, g, b, color.getAlpha());
        }

        private Color darken(Color color, float amount) {
            int r = Math.max(0, Math.round(color.getRed() * (1 - amount)));
            int g = Math.max(0, Math.round(color.getGreen() * (1 - amount)));
            int b = Math.max(0, Math.round(color.getBlue() * (1 - amount)));
            return new Color(r, g, b, color.getAlpha());
        }

        private Color withAlpha(Color color, int alpha) {
            return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
        }
    }

    private static final class RoundedPanel extends JPanel {
        private final Color backgroundColor;
        private final int radius;
        private final boolean border;

        private RoundedPanel(Color backgroundColor, int radius, boolean border) {
            this.backgroundColor = backgroundColor;
            this.radius = radius;
            this.border = border;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(backgroundColor);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            if (border) {
                g2.setColor(CARD_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
