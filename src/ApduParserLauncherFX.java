import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class ApduParserLauncherFX extends Application {

    private ApduParserEngine engine;
    private final ObservableList<Path> inputItems = FXCollections.observableArrayList();
    private Label detectionValue;
    private Label statusValue;
    private Label filesMetric;
    private Label parsedMetric;
    private Label unknownMetric;
    private Label detectionMetric;
    private Label dropHint;
    private CheckBox dryRunCheckbox;
    private TextArea previewArea;
    private TextArea consoleArea;
    private ListView<Path> inputListView;
    private ToggleButton themeToggle;
    private BorderPane root;
    private Label parserDetailLabel;

    @Override
    public void start(Stage stage) throws Exception {
        engine = new ApduParserEngine();

        root = new BorderPane();
        root.getStyleClass().addAll("app-root", "theme-dark");
        root.setPadding(new Insets(14));
        root.getStyleClass().add("window-shell");
        installGlobalDropSupport(root);

        root.setTop(buildHeader(stage));
        root.setCenter(buildCenter(stage));
        root.setBottom(buildFooter());

        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(engine.getLauncherRoot().resolve("src").resolve("apdu-launcher.css").toUri().toString());

        stage.setTitle("APDU Parser Launcher");
        stage.setMinWidth(1080);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.show();

        appendConsole("JavaFX UI ready.");
        appendConsole("Launcher root: " + engine.getLauncherRoot());
        refreshAll();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private VBox buildHeader(Stage stage) {
        VBox header = new VBox(12);
        header.getStyleClass().add("header-wrap");

        HBox introRow = new HBox(14);
        introRow.setAlignment(Pos.CENTER_LEFT);

        HBox traffic = new HBox(8);
        traffic.getStyleClass().add("traffic-lights");
        traffic.getChildren().addAll(trafficDot("traffic-red"), trafficDot("traffic-yellow"), trafficDot("traffic-green"));

        VBox titleBox = new VBox(2);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        titleBox.setMaxWidth(Double.MAX_VALUE);
        Label eyebrow = new Label("QA / eSIM APDU WORKBENCH");
        eyebrow.getStyleClass().add("eyebrow-label");
        Label title = new Label("APDU Parser Launcher");
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label(
                "Import customer logs, detect parser types, preview APDUs, inspect console traces, and export clean output."
        );
        subtitle.getStyleClass().add("hero-subtitle");
        subtitle.setWrapText(true);
        subtitle.setMaxWidth(Double.MAX_VALUE);
        titleBox.getChildren().addAll(eyebrow, title, subtitle);

        themeToggle = new ToggleButton("Dark Mode");
        themeToggle.getStyleClass().add("ghost-button");
        themeToggle.setOnAction(event -> {
            boolean light = themeToggle.isSelected();
            root.getStyleClass().removeAll("theme-dark", "theme-light");
            root.getStyleClass().add(light ? "theme-light" : "theme-dark");
            themeToggle.setText(light ? "Light Mode" : "Dark Mode");
        });

        dryRunCheckbox = new CheckBox("Detect Only");
        dryRunCheckbox.getStyleClass().add("mode-checkbox");
        dryRunCheckbox.setSelected(true);

        Button addFilesButton = createGhostButton("Import Logs");
        addFilesButton.setOnAction(event -> importFiles(stage));

        Button refreshButton = createGhostButton("Refresh");
        refreshButton.setOnAction(event -> {
            appendConsole("Manual refresh requested.");
            refreshAll();
        });

        Button addParserButton = createGhostButton("Register Log Type");
        addParserButton.setOnAction(event -> showAddParserDialog(stage));

        Button openInputButton = createGhostButton("Open Input Folder");
        openInputButton.setOnAction(event -> openFolder(engine.getInputDir()));

        Button openOutputButton = createGhostButton("Open Output Folder");
        openOutputButton.setOnAction(event -> openFolder(engine.getOutputDir()));

        Button runButton = createPrimaryButton("Parse Logs");
        runButton.setOnAction(event -> runEngine(runButton));

        HBox quickActions = new HBox(10);
        quickActions.setAlignment(Pos.CENTER_RIGHT);
        quickActions.getStyleClass().add("toolbar-inline");
        quickActions.getChildren().addAll(
                themeToggle,
                dryRunCheckbox,
                runButton
        );

        introRow.getChildren().addAll(traffic, titleBox, new RegionSpacer(), quickActions);

        FlowPane actionFlow = new FlowPane(8, 8);
        actionFlow.setHgap(8);
        actionFlow.setVgap(8);
        actionFlow.setAlignment(Pos.CENTER_LEFT);
        actionFlow.getStyleClass().add("toolbar-flow");
        actionFlow.getChildren().addAll(
                addFilesButton,
                refreshButton,
                addParserButton,
                openInputButton,
                openOutputButton
        );

        FlowPane metricFlow = new FlowPane();
        metricFlow.setHgap(10);
        metricFlow.setVgap(10);
        metricFlow.setPrefWrapLength(1040);
        metricFlow.getChildren().addAll(
                metricCard("Input Files", "0"),
                metricCard("Parsed Outputs", "0"),
                metricCard("Unmatched", "0"),
                metricCard("Detected Parser", "Select a file")
        );

        header.getChildren().addAll(introRow, actionFlow, metricFlow);
        return header;
    }

    private SplitPane buildCenter(Stage stage) {
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(Orientation.HORIZONTAL);
        splitPane.setDividerPositions(0.25, 0.78);
        splitPane.getStyleClass().add("main-split");

        VBox leftRail = new VBox(12);
        leftRail.getChildren().addAll(buildDropZone(stage), buildInputCard());

        VBox rightRail = new VBox(12);
        VBox.setVgrow(rightRail, Priority.ALWAYS);
        HBox middleRow = new HBox(12);
        VBox.setVgrow(middleRow, Priority.ALWAYS);
        VBox previewWrap = buildPreviewCard();
        VBox.setVgrow(previewWrap, Priority.ALWAYS);
        VBox sideStack = new VBox(12);
        sideStack.setPrefWidth(240);
        sideStack.getChildren().addAll(buildModeCard(), buildExportCard());
        middleRow.getChildren().addAll(previewWrap, sideStack);
        HBox.setHgrow(previewWrap, Priority.ALWAYS);
        rightRail.getChildren().addAll(buildDetectionCard(), middleRow, buildConsoleCard());

        splitPane.getItems().addAll(leftRail, rightRail);
        return splitPane;
    }

    private VBox buildDropZone(Stage stage) {
        VBox dropZone = card();
        dropZone.getStyleClass().add("drop-zone");
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setSpacing(8);
        dropZone.setPadding(new Insets(18));
        dropZone.setOnMouseClicked(event -> importFiles(stage));

        Label title = new Label("Drop Customer Logs");
        title.getStyleClass().add("card-title");
        dropHint = new Label("Drag .txt, .log, .html files here or click Import Logs.");
        dropHint.getStyleClass().add("muted-text");
        dropHint.setWrapText(true);
        dropHint.setMaxWidth(220);

        Button addFiles = createPrimaryButton("Choose Files");
        addFiles.setOnAction(event -> importFiles(stage));

        dropZone.getChildren().addAll(title, dropHint, addFiles);
        installDropSupport(dropZone);
        return dropZone;
    }

    private VBox buildInputCard() {
        VBox card = card();
        card.setSpacing(8);
        Label title = new Label("Imported Logs");
        title.getStyleClass().add("card-title");

        inputListView = new ListView<>(inputItems);
        inputListView.setPlaceholder(new Label("No input files yet."));
        inputListView.setCellFactory(list -> new PathCell());
        VBox.setVgrow(inputListView, Priority.ALWAYS);
        inputListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> updateSelectionState());

        card.getChildren().addAll(title, inputListView);
        VBox.setVgrow(card, Priority.ALWAYS);
        return card;
    }

    private VBox buildDetectionCard() {
        VBox card = card();
        card.setSpacing(8);

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(5);
        Label title = new Label("Parser Detection");
        title.getStyleClass().add("card-title");
        parserDetailLabel = new Label("Auto-detected parser based on configurable text signatures.");
        parserDetailLabel.getStyleClass().add("muted-text");
        parserDetailLabel.setWrapText(true);
        left.getChildren().addAll(title, parserDetailLabel);

        detectionValue = new Label("Waiting for selection");
        detectionValue.getStyleClass().add("badge-label");
        row.getChildren().addAll(left, new RegionSpacer(), detectionValue);
        card.getChildren().add(row);
        return card;
    }

    private VBox buildPreviewCard() {
        VBox card = card();
        card.setSpacing(10);
        VBox.setVgrow(card, Priority.ALWAYS);

        HBox titleRow = new HBox(10);
        Label title = new Label("APDU Output Preview");
        title.getStyleClass().add("card-title");
        Button copyButton = createGhostButton("Copy Preview");
        copyButton.setOnAction(event -> copyPreview());
        Button exportButton = createGhostButton("Export TXT");
        exportButton.setOnAction(event -> openFolder(engine.getOutputDir()));
        titleRow.getChildren().addAll(title, new RegionSpacer(), copyButton, exportButton);

        previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setWrapText(false);
        previewArea.getStyleClass().add("mono-area");
        VBox.setVgrow(previewArea, Priority.ALWAYS);

        card.getChildren().addAll(titleRow, previewArea);
        return card;
    }

    private VBox buildModeCard() {
        VBox card = card();
        card.getStyleClass().add("compact-card");
        card.setSpacing(10);

        Label title = new Label("Run Mode");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("Use detection-only preview before running a real extractor.");
        subtitle.getStyleClass().add("muted-text");
        subtitle.setWrapText(true);

        HBox modePill = new HBox(8);
        modePill.getStyleClass().add("segmented-pill");
        Label detect = new Label("Detect Only");
        detect.getStyleClass().add("segmented-item-active");
        Label execute = new Label("Execute Parser");
        execute.getStyleClass().add("segmented-item");
        modePill.getChildren().addAll(detect, execute);

        card.getChildren().addAll(title, subtitle, modePill);
        return card;
    }

    private VBox buildExportCard() {
        VBox card = card();
        card.getStyleClass().add("compact-card");
        card.setSpacing(10);

        Label title = new Label("Export & Delivery");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("Open results, copy the APDU preview, or register a new parser rule when a log type is new.");
        subtitle.getStyleClass().add("muted-text");
        subtitle.setWrapText(true);

        Button outputButton = createGhostButton("Open Output Folder");
        outputButton.setMaxWidth(Double.MAX_VALUE);
        outputButton.setOnAction(event -> openFolder(engine.getOutputDir()));

        Button typeButton = createGhostButton("Register Log Type");
        typeButton.setMaxWidth(Double.MAX_VALUE);
        typeButton.setOnAction(event -> showAddParserDialog((Stage) root.getScene().getWindow()));

        card.getChildren().addAll(title, subtitle, outputButton, typeButton);
        return card;
    }

    private VBox buildConsoleCard() {
        VBox card = card();
        card.setSpacing(10);
        VBox.setVgrow(card, Priority.ALWAYS);

        HBox titleRow = new HBox(10);
        Label title = new Label("Console Logs");
        title.getStyleClass().add("card-title");
        Button clearButton = createGhostButton("Clear");
        clearButton.setOnAction(event -> consoleArea.clear());
        titleRow.getChildren().addAll(title, new RegionSpacer(), clearButton);

        consoleArea = new TextArea();
        consoleArea.setEditable(false);
        consoleArea.setWrapText(true);
        consoleArea.getStyleClass().add("mono-area");
        consoleArea.setPrefRowCount(8);

        card.getChildren().addAll(titleRow, consoleArea);
        return card;
    }

    private HBox buildFooter() {
        HBox footer = new HBox(12);
        footer.setPadding(new Insets(14, 0, 0, 0));
        footer.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label("Mockup reference: Figma page APDU Parser Redesign / frame APDU Parser macOS Mockup");
        note.getStyleClass().add("muted-text");

        statusValue = new Label("Ready");
        statusValue.getStyleClass().add("footer-status");

        footer.getChildren().addAll(note, new RegionSpacer(), statusValue);
        return footer;
    }

    private VBox card() {
        VBox box = new VBox();
        box.getStyleClass().add("glass-card");
        box.setPadding(new Insets(18));
        return box;
    }

    private VBox metricCard(String label, String initialValue) {
        VBox card = card();
        card.getStyleClass().add("metric-card");
        card.setSpacing(8);

        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("metric-label");

        Label valueNode = new Label(initialValue);
        valueNode.getStyleClass().add("metric-value");

        if ("Input Files".equals(label)) {
            filesMetric = valueNode;
        } else if ("Parsed Outputs".equals(label)) {
            parsedMetric = valueNode;
        } else if ("Unmatched".equals(label)) {
            unknownMetric = valueNode;
        } else if ("Detected Parser".equals(label)) {
            detectionMetric = valueNode;
        }

        card.getChildren().addAll(labelNode, valueNode);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private Button createPrimaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }

    private Button createGhostButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("ghost-button");
        return button;
    }

    private void installDropSupport(VBox node) {
        node.addEventFilter(DragEvent.DRAG_OVER, event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
                event.consume();
            }
        });

        node.addEventFilter(DragEvent.DRAG_ENTERED, event -> {
            if (event.getDragboard().hasFiles() && !node.getStyleClass().contains("drop-zone-active")) {
                node.getStyleClass().add("drop-zone-active");
                event.consume();
            }
        });

        node.addEventFilter(DragEvent.DRAG_EXITED, event -> {
            node.getStyleClass().remove("drop-zone-active");
            if (event.getDragboard().hasFiles()) {
                event.consume();
            }
        });

        node.addEventFilter(DragEvent.DRAG_DROPPED, this::handleDrop);
    }

    private void installGlobalDropSupport(BorderPane node) {
        node.addEventFilter(DragEvent.DRAG_OVER, event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
                event.consume();
            }
        });
        node.addEventFilter(DragEvent.DRAG_DROPPED, this::handleDrop);
    }

    private void handleDrop(DragEvent event) {
        Dragboard dragboard = event.getDragboard();
        boolean success = false;
        if (dragboard.hasFiles()) {
            List<Path> files = new ArrayList<>();
            for (File file : dragboard.getFiles()) {
                files.add(file.toPath());
            }
            try {
                List<Path> accepted = filterSupportedImportFiles(files);
                List<Path> rejected = rejectUnsupportedImportFiles(files);
                if (!accepted.isEmpty()) {
                    engine.importFiles(accepted);
                    appendConsole("Imported " + accepted.size() + " file(s) via drag and drop.");
                    success = true;
                }
                if (!rejected.isEmpty()) {
                    appendConsole("Skipped unsupported file(s): " + joinFileNames(rejected));
                }
                if (accepted.isEmpty() && !rejected.isEmpty()) {
                    statusValue.setText("No supported log files were imported");
                }
                refreshAll();
            } catch (Exception ex) {
                appendConsole("Import failed: " + ex.getMessage());
                statusValue.setText("Import failed");
            }
        }
        event.setDropCompleted(success);
        event.consume();
    }

    private void importFiles(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select log files");
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) {
            return;
        }

        try {
            List<Path> paths = new ArrayList<>();
            for (File file : files) {
                paths.add(file.toPath());
            }
            List<Path> accepted = filterSupportedImportFiles(paths);
            List<Path> rejected = rejectUnsupportedImportFiles(paths);
            if (!accepted.isEmpty()) {
                engine.importFiles(accepted);
                appendConsole("Imported " + accepted.size() + " file(s).");
            }
            if (!rejected.isEmpty()) {
                appendConsole("Skipped unsupported file(s): " + joinFileNames(rejected));
            }
            if (accepted.isEmpty() && !rejected.isEmpty()) {
                statusValue.setText("No supported log files were imported");
            }
            refreshAll();
        } catch (Exception ex) {
            appendConsole("Import failed: " + ex.getMessage());
            statusValue.setText("Import failed");
        }
    }

    private List<Path> filterSupportedImportFiles(List<Path> files) {
        List<Path> accepted = new ArrayList<>();
        for (Path file : files) {
            if (isSupportedImportFile(file)) {
                accepted.add(file);
            }
        }
        return accepted;
    }

    private List<Path> rejectUnsupportedImportFiles(List<Path> files) {
        List<Path> rejected = new ArrayList<>();
        for (Path file : files) {
            if (!isSupportedImportFile(file)) {
                rejected.add(file);
            }
        }
        return rejected;
    }

    private boolean isSupportedImportFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".txt")
                || name.endsWith(".log")
                || name.endsWith(".html")
                || name.endsWith(".htm");
    }

    private String joinFileNames(List<Path> files) {
        List<String> names = new ArrayList<>();
        for (Path file : files) {
            names.add(file.getFileName() == null ? file.toString() : file.getFileName().toString());
        }
        return String.join(", ", names);
    }

    private void showAddParserDialog(Stage stage) {
        Dialog<ApduParserEngine.ParserDefinition> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Register Log Type");
        dialog.setHeaderText("Register a new log type and existing extractor script without editing config.json manually.");

        ButtonType saveType = new ButtonType("Save Parser", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));

        TextField nameField = new TextField();
        nameField.setPromptText("example_new_vendor");

        TextField folderField = new TextField();
        folderField.setPromptText("../new_vendor_extractor");

        TextField scriptField = new TextField();
        scriptField.setPromptText("script.java");

        TextField stagedScriptField = new TextField();
        stagedScriptField.setPromptText("VendorExtractor.java");

        TextField stagedInputField = new TextField();
        stagedInputField.setPromptText("input.log");

        TextField stagedOutputField = new TextField();
        stagedOutputField.setPromptText("output.txt");

        TextField outputExtensionField = new TextField(".txt");

        ComboBox<String> detectionModeBox = new ComboBox<>();
        detectionModeBox.getItems().addAll("all", "any");
        detectionModeBox.setValue("all");

        TextArea patternsArea = new TextArea();
        patternsArea.setPromptText("One detection pattern per line");
        patternsArea.setPrefRowCount(4);

        TextField extensionsField = new TextField(".txt,.log");
        extensionsField.setPromptText(".txt,.log");

        TextField regexField = new TextField();
        regexField.setPromptText("Optional file name regex");

        TextField commandArgsField = new TextField();
        commandArgsField.setPromptText("{input},{output}");

        int row = 0;
        grid.add(new Label("Parser name"), 0, row);
        grid.add(nameField, 1, row++);
        grid.add(new Label("Extractor folder"), 0, row);
        grid.add(folderField, 1, row++);
        grid.add(new Label("Script file"), 0, row);
        grid.add(scriptField, 1, row++);
        grid.add(new Label("Staged script file"), 0, row);
        grid.add(stagedScriptField, 1, row++);
        grid.add(new Label("Staged input file"), 0, row);
        grid.add(stagedInputField, 1, row++);
        grid.add(new Label("Staged output file"), 0, row);
        grid.add(stagedOutputField, 1, row++);
        grid.add(new Label("Output extension"), 0, row);
        grid.add(outputExtensionField, 1, row++);
        grid.add(new Label("Detection mode"), 0, row);
        grid.add(detectionModeBox, 1, row++);
        grid.add(new Label("Patterns"), 0, row);
        grid.add(patternsArea, 1, row++);
        grid.add(new Label("Extensions"), 0, row);
        grid.add(extensionsField, 1, row++);
        grid.add(new Label("File regex"), 0, row);
        grid.add(regexField, 1, row++);
        grid.add(new Label("Command args"), 0, row);
        grid.add(commandArgsField, 1, row);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(buttonType -> {
            if (buttonType != saveType) {
                return null;
            }
            return new ApduParserEngine.ParserDefinition(
                    nameField.getText().trim(),
                    folderField.getText().trim(),
                    scriptField.getText().trim(),
                    stagedScriptField.getText().trim(),
                    stagedInputField.getText().trim(),
                    stagedOutputField.getText().trim(),
                    outputExtensionField.getText().trim(),
                    detectionModeBox.getValue(),
                    splitLines(patternsArea.getText()),
                    splitCsv(extensionsField.getText()),
                    regexField.getText().trim(),
                    splitCsv(commandArgsField.getText())
            );
        });

        dialog.showAndWait().ifPresent(definition -> {
            try {
                engine.addParserDefinition(definition);
                appendConsole("Added parser type: " + definition.getName());
                statusValue.setText("Saved parser type");
            } catch (Exception ex) {
                appendConsole("Add parser failed: " + ex.getMessage());
                statusValue.setText("Add parser failed");
            }
        });
    }

    private void refreshAll() {
        try {
            inputItems.setAll(engine.listInputFiles());
            filesMetric.setText(String.valueOf(inputItems.size()));
            int unknownCount = countVisibleFiles(engine.getUnknownDir());
            int outputCount = countVisibleFiles(engine.getOutputDir());
            parsedMetric.setText(String.valueOf(Math.max(0, outputCount - unknownCount)));
            unknownMetric.setText(String.valueOf(unknownCount));
            statusValue.setText("Input: " + engine.getInputDir());
            if (!inputItems.isEmpty() && inputListView.getSelectionModel().getSelectedItem() == null) {
                inputListView.getSelectionModel().select(0);
            } else {
                updateSelectionState();
            }
        } catch (Exception ex) {
            appendConsole("Refresh failed: " + ex.getMessage());
            statusValue.setText("Refresh failed");
        }
    }

    private void updateSelectionState() {
        Path selected = inputListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            detectionValue.setText("Waiting for selection");
            detectionMetric.setText("Select a file");
            previewArea.clear();
            return;
        }

        try {
            ApduParserEngine.DetectionResult detection = engine.detectParser(selected);
            String detectedText = detection.matched() ? detection.getParserName() : "No match";
            detectionValue.setText(detectedText);
            detectionMetric.setText(detectedText);
            parserDetailLabel.setText(detection.matched()
                    ? "Matched using configured file markers and extension filters for " + detectedText + "."
                    : "No configured parser matched this file yet. Register a new log type if needed.");

            Path previewTarget = resolvePreviewTarget(selected, detection);
            if (previewTarget != null && Files.exists(previewTarget)) {
                previewArea.setText(engine.readFilePreview(previewTarget, 24000));
            } else {
                previewArea.setText("No output yet. Run the parser to generate APDU output.");
            }
        } catch (Exception ex) {
            detectionValue.setText("Detection failed");
            detectionMetric.setText("Detection failed");
            parserDetailLabel.setText("Detection failed while reading the selected file.");
            previewArea.setText(ex.getMessage());
        }
    }

    private Path resolvePreviewTarget(Path selected, ApduParserEngine.DetectionResult detection) {
        if (!detection.matched()) {
            return engine.getUnknownDir().resolve(selected.getFileName());
        }

        String fileName = selected.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return engine.getOutputDir().resolve(detection.getParserName()).resolve(base + ".txt");
    }

    private void runEngine(Button runButton) {
        runButton.setDisable(true);
        statusValue.setText("Running...");
        appendConsole("==================================================");
        appendConsole("Starting parser. dryRun=" + dryRunCheckbox.isSelected());

        Thread worker = new Thread(() -> {
            try {
                engine.processAll(dryRunCheckbox.isSelected(), this::appendConsoleFromWorker);
                Platform.runLater(() -> {
                    refreshAll();
                    statusValue.setText("Completed");
                    runButton.setDisable(false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    appendConsole("Run failed: " + ex.getMessage());
                    statusValue.setText("Run failed");
                    runButton.setDisable(false);
                });
            }
        }, "apdu-parser-runner");
        worker.setDaemon(true);
        worker.start();
    }

    private void appendConsoleFromWorker(String line) {
        Platform.runLater(() -> appendConsole(line));
    }

    private void appendConsole(String line) {
        consoleArea.appendText(line + System.lineSeparator());
        consoleArea.positionCaret(consoleArea.getText().length());
    }

    private void copyPreview() {
        ClipboardContent content = new ClipboardContent();
        content.putString(previewArea.getText());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        statusValue.setText("Preview copied");
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

    private static List<String> splitLines(String text) {
        List<String> values = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return values;
        }
        String[] parts = text.split("\\r?\\n");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception ex) {
            appendConsole("Open folder failed: " + ex.getMessage());
            statusValue.setText("Open folder failed");
        }
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

    private Region trafficDot(String styleClass) {
        Region dot = new Region();
        dot.getStyleClass().addAll("traffic-dot", styleClass);
        return dot;
    }

    private static final class RegionSpacer extends javafx.scene.layout.Region {
        private RegionSpacer() {
            HBox.setHgrow(this, Priority.ALWAYS);
        }
    }

    private static final class PathCell extends javafx.scene.control.ListCell<Path> {
        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
            } else {
                setText(item.getFileName().toString());
            }
        }
    }
}
