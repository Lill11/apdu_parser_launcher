import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class ApduQaWorkbenchFX extends Application {

    private ApduParserEngine engine;
    private final ObservableList<Path> importedItems = FXCollections.observableArrayList();
    private final List<ApduOutputAnalyzer.AnalysisItem> emptyAnalysisItems = List.of();

    private BorderPane root;
    private ListView<Path> importedListView;
    private ListView<ApduOutputAnalyzer.AnalysisItem> analysisTimelineView;
    private TextArea rawOutputArea;
    private TextArea analysisDetailArea;
    private TextArea consoleArea;
    private Label importedMetric;
    private Label parsedMetric;
    private Label unmatchedMetric;
    private Label parserMetric;
    private Label selectionSummary;
    private Label statusLabel;
    private CheckBox detectOnlyCheckbox;
    private TabPane filterTabs;
    private TabPane outputTabs;
    private TitledPane consolePane;
    private String currentRenderedAnalysis = "";

    private Path selectedImportedFile;
    private ApduOutputAnalyzer.FilterMode selectedFilter = ApduOutputAnalyzer.FilterMode.ALL;
    private List<ApduOutputAnalyzer.AnalysisItem> currentAnalysisItems = emptyAnalysisItems;

    @Override
    public void start(Stage stage) throws Exception {
        engine = new ApduParserEngine();

        root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setPadding(new Insets(18));

        ScrollPane shellScroll = new ScrollPane();
        shellScroll.setFitToWidth(true);
        shellScroll.setFitToHeight(false);
        shellScroll.getStyleClass().add("shell-scroll");

        VBox shell = new VBox(16);
        shell.getStyleClass().add("shell");
        shell.setPadding(new Insets(18));
        shell.getChildren().addAll(buildHeader(stage), buildWorkspace(stage), buildFooter());
        shellScroll.setContent(shell);

        root.setCenter(shellScroll);

        Scene scene = new Scene(root, 1500, 940);
        scene.getStylesheets().add(engine.getLauncherRoot().resolve("javafx_preview").resolve("apdu-workbench.css").toUri().toString());

        stage.setTitle("APDU QA Workbench Preview");
        stage.setMinWidth(1040);
        stage.setMinHeight(760);
        stage.setScene(scene);
        stage.show();

        refreshAll();
        appendConsole("JavaFX preview ready.");
    }

    public static void main(String[] args) {
        launch(args);
    }

    private VBox buildHeader(Stage stage) {
        VBox header = new VBox(14);

        HBox topBar = new HBox(14);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("topbar");

        VBox titleBox = new VBox(4);
        Label eyebrow = new Label("QA / ESIM DEBUGGER PREVIEW");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("APDU QA Workbench");
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label("Import logs, run parsers, and inspect resets, ES10 operations, and APDU failures fast.");
        subtitle.getStyleClass().add("muted");
        subtitle.setWrapText(true);
        titleBox.getChildren().addAll(eyebrow, title, subtitle);

        FlowPane actionBar = new FlowPane();
        actionBar.setHgap(10);
        actionBar.setVgap(10);
        actionBar.setAlignment(Pos.CENTER_RIGHT);

        Button importButton = ghostButton("Import Logs");
        importButton.setOnAction(event -> importFiles(stage));
        Button refreshButton = ghostButton("Refresh");
        refreshButton.setOnAction(event -> refreshAll());
        Button registerButton = ghostButton("Register Log Type");
        registerButton.setOnAction(event -> showAddParserDialog(stage));
        Button openInputButton = ghostButton("Open Input Folder");
        openInputButton.setOnAction(event -> openFolder(engine.getInputDir()));
        Button openOutputButton = ghostButton("Open Output Folder");
        openOutputButton.setOnAction(event -> openFolder(engine.getOutputDir()));
        detectOnlyCheckbox = new CheckBox("Detect Only");
        detectOnlyCheckbox.getStyleClass().add("toolbar-checkbox");
        detectOnlyCheckbox.setSelected(true);
        Button parseButton = primaryButton("Parse Logs");
        parseButton.setOnAction(event -> runEngine(parseButton));

        actionBar.getChildren().addAll(
                importButton,
                refreshButton,
                registerButton,
                openInputButton,
                openOutputButton,
                detectOnlyCheckbox,
                parseButton
        );

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        topBar.getChildren().addAll(titleBox, spacer, actionBar);

        HBox metrics = new HBox(12);
        importedMetric = metricValue("0");
        parsedMetric = metricValue("0");
        unmatchedMetric = metricValue("0");
        parserMetric = metricValue("Select a file");
        metrics.getChildren().addAll(
                metricCard("Imported Logs", importedMetric),
                metricCard("Parsed Outputs", parsedMetric),
                metricCard("Unmatched", unmatchedMetric),
                metricCard("Detected Parser", parserMetric)
        );

        header.getChildren().addAll(topBar, metrics);
        return header;
    }

    private Region buildWorkspace(Stage stage) {
        HBox workspace = new HBox(16);
        workspace.setFillHeight(true);

        VBox leftRail = new VBox(16);
        leftRail.setPrefWidth(340);
        leftRail.setMinWidth(300);
        leftRail.getChildren().addAll(buildDropCard(stage), buildImportedLogsCard());
        VBox.setVgrow(leftRail.getChildren().get(1), Priority.ALWAYS);

        VBox centerRail = new VBox(16);
        HBox.setHgrow(centerRail, Priority.ALWAYS);
        centerRail.getChildren().addAll(buildAnalysisCard(), buildConsolePane());
        VBox.setVgrow(centerRail.getChildren().get(0), Priority.ALWAYS);

        workspace.getChildren().addAll(leftRail, centerRail);
        return workspace;
    }

    private VBox buildDropCard(Stage stage) {
        VBox card = card();
        card.setSpacing(10);

        Label title = cardTitle("Drop Customer Logs");
        Label hint = mutedLabel("Drag .txt, .log, .html, or .htm files here, or click Import Logs.");
        hint.setWrapText(true);

        Button importButton = primaryButton("Choose Files");
        importButton.setOnAction(event -> importFiles(stage));

        VBox dropZone = new VBox(10);
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setPadding(new Insets(20));
        Label zoneLabel = new Label("Drop files here or click Import Logs");
        zoneLabel.getStyleClass().add("drop-label");
        dropZone.getChildren().add(zoneLabel);
        installDropSupport(dropZone);

        card.getChildren().addAll(title, hint, dropZone, importButton);
        return card;
    }

    private VBox buildImportedLogsCard() {
        VBox card = card();
        card.setSpacing(12);

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = cardTitle("Imported Logs");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button removeSelectedButton = ghostButton("Remove Selected");
        removeSelectedButton.setOnAction(event -> removeSelectedImportedLog());
        Button clearAllButton = ghostButton("Clear All");
        clearAllButton.setOnAction(event -> clearAllImportedLogs());
        header.getChildren().addAll(title, spacer, removeSelectedButton, clearAllButton);

        importedListView = new ListView<>(importedItems);
        importedListView.getStyleClass().add("imported-list");
        importedListView.setPlaceholder(mutedLabel("No imported logs yet."));
        importedListView.setCellFactory(list -> new ImportedLogCell());
        importedListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            selectedImportedFile = newValue;
            updateSelectionState();
        });

        VBox.setVgrow(importedListView, Priority.ALWAYS);
        card.getChildren().addAll(header, importedListView);
        return card;
    }

    private VBox buildAnalysisCard() {
        VBox card = card();
        card.setSpacing(12);
        VBox.setVgrow(card, Priority.ALWAYS);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titleBox = new VBox(4);
        Label title = cardTitle("APDU Inspection");
        selectionSummary = mutedLabel("Select a log to inspect resets, errors, ES10 tags, and parser output.");
        selectionSummary.setWrapText(true);
        titleBox.getChildren().addAll(title, selectionSummary);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button showConsoleButton = ghostButton("Show Console");
        showConsoleButton.setOnAction(event -> {
            consolePane.setExpanded(!consolePane.isExpanded());
            showConsoleButton.setText(consolePane.isExpanded() ? "Hide Console" : "Show Console");
        });
        Button copyButton = ghostButton("Copy");
        copyButton.setOnAction(event -> copyPreview());
        Button openOutputButton = ghostButton("Open Output");
        openOutputButton.setOnAction(event -> openFolder(engine.getOutputDir()));
        header.getChildren().addAll(titleBox, spacer, showConsoleButton, copyButton, openOutputButton);

        filterTabs = new TabPane();
        filterTabs.getStyleClass().add("filter-tabs");
        filterTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (ApduOutputAnalyzer.FilterMode filterMode : ApduOutputAnalyzer.FilterMode.values()) {
            Tab tab = new Tab(filterMode.getLabel(), new Region());
            filterTabs.getTabs().add(tab);
        }
        filterTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            int index = filterTabs.getSelectionModel().getSelectedIndex();
            if (index >= 0 && index < ApduOutputAnalyzer.FilterMode.values().length) {
                selectedFilter = ApduOutputAnalyzer.FilterMode.values()[index];
                renderAnalysisPreview();
                selectionSummary.setText(buildSelectionSummary());
            }
        });

        outputTabs = new TabPane();
        outputTabs.getStyleClass().add("output-tabs");
        outputTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        rawOutputArea = monoArea();
        analysisTimelineView = new ListView<>();
        analysisTimelineView.getStyleClass().add("analysis-timeline");
        analysisTimelineView.setCellFactory(list -> new AnalysisItemCell());
        analysisTimelineView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> renderAnalysisDetail(newValue));
        VBox.setVgrow(analysisTimelineView, Priority.ALWAYS);

        analysisDetailArea = monoArea();
        analysisDetailArea.setPrefRowCount(8);

        VBox analysisPane = new VBox(10);
        analysisPane.getChildren().addAll(analysisTimelineView, analysisDetailArea);
        VBox.setVgrow(analysisPane, Priority.ALWAYS);

        Tab rawTab = new Tab("Raw APDU Output", rawOutputArea);
        Tab analysisTab = new Tab("Enhanced Analysis", analysisPane);
        outputTabs.getTabs().addAll(rawTab, analysisTab);
        VBox.setVgrow(outputTabs, Priority.ALWAYS);

        card.getChildren().addAll(header, filterTabs, outputTabs);
        return card;
    }

    private TitledPane buildConsolePane() {
        consoleArea = monoArea();
        consoleArea.setPrefRowCount(8);
        consolePane = new TitledPane("Processing Console", consoleArea);
        consolePane.getStyleClass().add("console-pane");
        consolePane.setExpanded(false);
        return consolePane;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_LEFT);
        statusLabel = mutedLabel("Ready");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().addAll(mutedLabel("JavaFX preview kept separate from the existing launcher."), spacer, statusLabel);
        return footer;
    }

    private VBox card() {
        VBox card = new VBox();
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));
        return card;
    }

    private Label cardTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("card-title");
        return label;
    }

    private Label mutedLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    private Label metricValue(String text) {
        Label value = new Label(text);
        value.getStyleClass().add("metric-value");
        return value;
    }

    private VBox metricCard(String title, Label value) {
        VBox box = card();
        box.getStyleClass().add("metric-card");
        box.setSpacing(8);
        Label label = new Label(title);
        label.getStyleClass().add("metric-label");
        box.getChildren().addAll(label, value);
        return box;
    }

    private Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }

    private Button ghostButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        return button;
    }

    private TextArea monoArea() {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().add("mono-area");
        VBox.setVgrow(area, Priority.ALWAYS);
        return area;
    }

    private void installDropSupport(VBox dropZone) {
        dropZone.setOnDragOver(event -> {
            Dragboard dragboard = event.getDragboard();
            if (dragboard.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        dropZone.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            if (dragboard.hasFiles()) {
                List<Path> files = new ArrayList<>();
                for (File file : dragboard.getFiles()) {
                    files.add(file.toPath());
                }
                importFiles(files);
                event.setDropCompleted(true);
            } else {
                event.setDropCompleted(false);
            }
            event.consume();
        });
    }

    private void importFiles(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import customer logs");
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }

        List<Path> paths = new ArrayList<>();
        for (File file : files) {
            paths.add(file.toPath());
        }
        importFiles(paths);
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
                appendConsole("Imported " + accepted.size() + " file(s).");
            }
            if (!rejected.isEmpty()) {
                appendConsole("Skipped unsupported file(s): " + joinFileNames(rejected));
            }
            refreshAll();
        } catch (Exception ex) {
            appendConsole("Import failed: " + ex.getMessage());
        }
    }

    private void showAddParserDialog(Stage stage) {
        Dialog<ApduParserEngine.ParserDefinition> dialog = new Dialog<>();
        dialog.setTitle("Add New Parser");
        dialog.setHeaderText("Register a parser without touching the existing launcher.");

        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        TextField nameField = new TextField();
        TextField extensionsField = new TextField(".txt,.log");
        TextField folderField = new TextField("../new_vendor_extractor");
        TextField scriptField = new TextField("script.java");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(12));
        grid.add(new Label("Parser name"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Supported extensions"), 0, 1);
        grid.add(extensionsField, 1, 1);
        grid.add(new Label("Extractor folder"), 0, 2);
        grid.add(folderField, 1, 2);
        grid.add(new Label("Main Java script"), 0, 3);
        grid.add(scriptField, 1, 3);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(buttonType -> {
            if (buttonType != saveType) {
                return null;
            }
            return new ApduParserEngine.ParserDefinition(
                    nameField.getText().trim(),
                    folderField.getText().trim(),
                    scriptField.getText().trim(),
                    scriptField.getText().trim(),
                    "input.log",
                    "output.txt",
                    ".txt",
                    "all",
                    List.of(),
                    splitCsv(extensionsField.getText()),
                    "",
                    List.of("{input}", "{output}")
            );
        });

        dialog.showAndWait().ifPresent(definition -> {
            try {
                engine.addParserDefinition(definition);
                appendConsole("Registered parser: " + definition.getName());
                refreshAll();
            } catch (Exception ex) {
                appendConsole("Register parser failed: " + ex.getMessage());
            }
        });
    }

    private void refreshAll() {
        try {
            List<Path> files = engine.listInputFiles();
            importedItems.setAll(files);

            int unknownCount = countVisibleFiles(engine.getUnknownDir());
            int outputCount = countRawOutputFiles(engine.getOutputDir());
            importedMetric.setText(String.valueOf(files.size()));
            parsedMetric.setText(String.valueOf(Math.max(0, outputCount - unknownCount)));
            unmatchedMetric.setText(String.valueOf(unknownCount));

            if (selectedImportedFile == null && !files.isEmpty()) {
                selectedImportedFile = files.get(0);
            } else if (selectedImportedFile != null && !files.contains(selectedImportedFile)) {
                selectedImportedFile = files.isEmpty() ? null : files.get(0);
            }
            if (selectedImportedFile != null) {
                importedListView.getSelectionModel().select(selectedImportedFile);
            }
            updateSelectionState();
        } catch (Exception ex) {
            statusLabel.setText("Refresh failed: " + ex.getMessage());
        }
    }

    private void updateSelectionState() {
        Path selected = selectedImportedFile;
        if (selected == null) {
            parserMetric.setText("Select a file");
            selectionSummary.setText(buildSelectionSummary());
            rawOutputArea.clear();
            analysisTimelineView.getItems().clear();
            analysisDetailArea.clear();
            currentAnalysisItems = emptyAnalysisItems;
            currentRenderedAnalysis = "";
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
                rawOutputArea.setText(engine.readFilePreview(rawPreviewTarget, 24000));
                rawOutputArea.positionCaret(0);
            } else {
                rawOutputArea.setText("No raw APDU output yet. Click Parse Logs to generate it.");
            }

            if (detection.matched() && rawPreviewTarget != null && Files.exists(rawPreviewTarget)) {
                currentAnalysisItems = ApduOutputAnalyzer.analyzeEntries(selected, rawPreviewTarget);
                renderAnalysisPreview();
            } else {
                currentAnalysisItems = emptyAnalysisItems;
                analysisTimelineView.getItems().clear();
                analysisDetailArea.setText("Enhanced analysis becomes available after a parser match and extraction run.");
                currentRenderedAnalysis = "";
            }
        } catch (Exception ex) {
            parserMetric.setText("Error");
            selectionSummary.setText("The selected log could not be analyzed.");
            rawOutputArea.setText(ex.getMessage());
            analysisTimelineView.getItems().clear();
            analysisDetailArea.setText(ex.getMessage());
            currentAnalysisItems = emptyAnalysisItems;
            currentRenderedAnalysis = "";
        }
    }

    private void runEngine(Button runButton) {
        runButton.setDisable(true);
        appendConsole("Starting parser run. detectOnly=" + detectOnlyCheckbox.isSelected());

        javafx.concurrent.Task<Void> task = new javafx.concurrent.Task<>() {
            @Override
            protected Void call() throws Exception {
                engine.processAll(detectOnlyCheckbox.isSelected(), message -> Platform.runLater(() -> appendConsole(message)));
                return null;
            }
        };

        task.setOnSucceeded(event -> {
            appendConsole("Processing finished.");
            runButton.setDisable(false);
            refreshAll();
        });
        task.setOnFailed(event -> {
            appendConsole("Processing failed: " + task.getException().getMessage());
            runButton.setDisable(false);
            refreshAll();
        });

        Thread worker = new Thread(task, "apdu-fx-runner");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderAnalysisPreview() {
        if (currentAnalysisItems == null || currentAnalysisItems.isEmpty()) {
            analysisTimelineView.getItems().setAll();
            analysisDetailArea.setText("No enhanced analysis output yet. Click Parse Logs to generate analysis.");
            currentRenderedAnalysis = "";
            return;
        }
        List<ApduOutputAnalyzer.AnalysisItem> filteredItems = ApduOutputAnalyzer.filterEntries(currentAnalysisItems, selectedFilter);
        analysisTimelineView.getItems().setAll(filteredItems);
        currentRenderedAnalysis = ApduOutputAnalyzer.renderEnhancedOutput(currentAnalysisItems, selectedFilter);
        if (filteredItems.isEmpty()) {
            analysisDetailArea.setText("No analysis entries match the current filter.");
        } else {
            analysisTimelineView.getSelectionModel().select(0);
            renderAnalysisDetail(filteredItems.get(0));
        }
    }

    private void renderAnalysisDetail(ApduOutputAnalyzer.AnalysisItem item) {
        if (item == null) {
            analysisDetailArea.clear();
            return;
        }
        if (item.isResetMarker()) {
            StringBuilder resetText = new StringBuilder();
            resetText.append("[")
                    .append(String.format(Locale.ROOT, "%04d", item.sequenceIndex))
                    .append("] ")
                    .append(item.resetMarker)
                    .append(System.lineSeparator());
            if (item.sourceLine > 0) {
                resetText.append("Log line: ").append(item.sourceLine).append(System.lineSeparator());
            }
            analysisDetailArea.setText(resetText.toString());
            analysisDetailArea.positionCaret(0);
            return;
        }

        StringBuilder details = new StringBuilder();
        details.append("[")
                .append(String.format(Locale.ROOT, "%04d", item.sequenceIndex))
                .append("] ")
                .append(item.headline)
                .append(System.lineSeparator());
        if (!item.tagLabel.isBlank()) {
            details.append("Tag: ").append(item.tagLabel).append(System.lineSeparator());
        }
        details.append("APDU: ").append(item.commandApdu).append(System.lineSeparator());
        if (!item.responseApdu.equals("-")) {
            details.append("Response: ").append(item.responseApdu).append(System.lineSeparator());
        }
        if (!item.statusWord.equals("-")) {
            details.append("Severity: ").append(item.severity).append("  SW=").append(item.statusWord).append(System.lineSeparator());
        }
        if (!item.note.isBlank()) {
            details.append(item.note).append(System.lineSeparator());
        }
        if (item.sourceLine > 0) {
            details.append("Log line: ").append(item.sourceLine).append(System.lineSeparator());
        }
        analysisDetailArea.setText(details.toString());
        analysisDetailArea.positionCaret(0);
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
            appendConsole("Clear logs failed: " + ex.getMessage());
        }
    }

    private void removeImportedLog(Path file) {
        try {
            engine.deleteImportedFile(file);
            if (file.equals(selectedImportedFile)) {
                selectedImportedFile = null;
            }
            appendConsole("Removed imported log: " + file.getFileName());
            refreshAll();
        } catch (Exception ex) {
            appendConsole("Remove log failed: " + ex.getMessage());
        }
    }

    private void copyPreview() {
        String content = outputTabs.getSelectionModel().getSelectedIndex() == 0
                ? rawOutputArea.getText()
                : currentRenderedAnalysis;
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(content);
        Clipboard.getSystemClipboard().setContent(clipboardContent);
        statusLabel.setText("Copied current output view");
    }

    private void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception ex) {
            appendConsole("Open folder failed: " + ex.getMessage());
        }
    }

    private void appendConsole(String line) {
        consoleArea.appendText(line + System.lineSeparator());
        consoleArea.positionCaret(consoleArea.getText().length());
        statusLabel.setText(detectOnlyCheckbox.isSelected() ? "Detect-only mode" : "Execute mode");
    }

    private String buildSelectionSummary() {
        if (selectedImportedFile == null) {
            return "Select a log to inspect resets, errors, ES10 operations, and parser output.";
        }
        return "Selected: "
                + selectedImportedFile.getFileName()
                + "  |  Parser: "
                + parserMetric.getText()
                + "  |  View: "
                + selectedFilter.getLabel();
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
        }
        return extensions;
    }

    private static List<String> splitCsv(String text) {
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

    private static String joinFileNames(List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path file : files) {
            names.add(file.getFileName().toString());
        }
        return String.join(", ", names);
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

    private final class ImportedLogCell extends ListCell<Path> {
        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Label label = new Label(item.getFileName().toString());
            label.getStyleClass().add("list-row-label");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button deleteButton = ghostButton("Delete");
            deleteButton.getStyleClass().add("cell-delete-button");
            deleteButton.setOnAction(event -> removeImportedLog(item));
            row.getChildren().addAll(label, spacer, deleteButton);
            setGraphic(row);
            setText(null);
        }
    }

    private final class AnalysisItemCell extends ListCell<ApduOutputAnalyzer.AnalysisItem> {
        @Override
        protected void updateItem(ApduOutputAnalyzer.AnalysisItem item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("timeline-error", "timeline-reset", "timeline-es10", "timeline-proactive", "timeline-lsi", "timeline-normal");

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            VBox wrapper = new VBox(4);
            wrapper.getStyleClass().add("timeline-item");

            Label title = new Label(buildTimelineTitle(item));
            title.getStyleClass().add("timeline-title");

            Label subline = new Label(buildTimelineSubline(item));
            subline.getStyleClass().add("timeline-subline");
            subline.setWrapText(true);

            wrapper.getChildren().addAll(title, subline);
            setGraphic(wrapper);
            setText(null);

            if (item.isResetMarker()) {
                getStyleClass().add("timeline-reset");
            } else if ("ERROR".equals(item.severity) || "WARNING".equals(item.severity)) {
                getStyleClass().add("timeline-error");
            } else if (item.es10) {
                getStyleClass().add("timeline-es10");
            } else if (item.fetchOrTerminalResponse) {
                getStyleClass().add("timeline-proactive");
            } else if (item.isConfigureLsi() || "Manage LSI".equals(item.commandName)) {
                getStyleClass().add("timeline-lsi");
            } else {
                getStyleClass().add("timeline-normal");
            }
        }
    }

    private String buildTimelineTitle(ApduOutputAnalyzer.AnalysisItem item) {
        return "[" + String.format(Locale.ROOT, "%04d", item.sequenceIndex) + "] " + item.headline;
    }

    private String buildTimelineSubline(ApduOutputAnalyzer.AnalysisItem item) {
        if (item.isResetMarker()) {
            return item.sourceLine > 0 ? "Log line " + item.sourceLine : "Reset marker";
        }
        if (!item.tagLabel.isBlank()) {
            return "Tag " + item.tagLabel;
        }
        if (!item.statusWord.equals("-") && !"OK".equals(item.severity)) {
            return "SW=" + item.statusWord + "  |  " + item.severity;
        }
        return item.commandApdu;
    }
}
