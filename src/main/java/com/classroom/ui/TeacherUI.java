package com.classroom.ui;

import com.classroom.model.CodeData;
import com.classroom.model.FileShareData;
import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.model.SlideData;
import com.classroom.server.TeacherServer;
import com.classroom.ui.WhiteboardPane.DrawMode;
import com.classroom.util.PptService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

public class TeacherUI {

    // ── Theme constants ────────────────────────────────────────────────────
    private static final String THEME_DARK  = "/theme-dark.css";
    private static final String THEME_LIGHT = "/theme-light.css";
    private static final Color  DARK_CANVAS    = Color.web("#1a2035");
    private static final String DARK_CONTAINER = "#0d1117";
    private static final Color  LIGHT_CANVAS    = Color.WHITE;
    private static final String LIGHT_CONTAINER = "#e0e0e0";
    private static final String CODE_EDITOR_DARK  =
            "-fx-control-inner-background: #1e1e1e; -fx-text-fill: #d4d4d4; " +
            "-fx-background-color: #1e1e1e; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_NUMS_DARK    =
            "-fx-control-inner-background: #252526; -fx-text-fill: #858585; " +
            "-fx-background-color: #252526; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_AREA_DARK    = "-fx-background-color: #1e1e1e; -fx-border-width: 0;";
    private static final String CODE_EDITOR_LIGHT =
            "-fx-control-inner-background: #fafafa; -fx-text-fill: #1e1e1e; " +
            "-fx-background-color: #fafafa; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_NUMS_LIGHT   =
            "-fx-control-inner-background: #f0f0f0; -fx-text-fill: #888888; " +
            "-fx-background-color: #f0f0f0; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_AREA_LIGHT   = "-fx-background-color: #fafafa; -fx-border-width: 0;";

    // ── File-sharing constant ──────────────────────────────────────────────
    // Must match FileShareData.CHUNK_SIZE; referenced here for clarity.
    private static final int CHUNK_SIZE = FileShareData.CHUNK_SIZE;

    // ── Theme state ────────────────────────────────────────────────────────
    private boolean isDarkTheme = false;
    private Scene   mainScene;

    // ── Dynamic refs updated on theme switch ───────────────────────────────
    private TextArea codeEditor;
    private TextArea lineNumbers;
    private HBox     codeArea;

    // ── Core state ─────────────────────────────────────────────────────────
    private final Stage stage;
    private TeacherServer server;
    private final ListView<String> studentListView;
    private WhiteboardPane whiteboardPane;
    private WhiteboardPane pptWhiteboardPane;

    // Phase 3 — PPT
    private PptService pptService;
    private ImageView  pptImageView;
    private Label      slideCountLabel;
    private Button     prevSlideBtn;
    private Button     nextSlideBtn;

    // Toolbars (shown/hidden on tab switch)
    private HBox toolbar;
    private HBox shapeToolbar;

    private TabPane tabPane;
    private Tab     whiteboardTab;
    private Tab     pptTab;
    private Tab     codeTab;
    private Tab     fileTab;
    private Label   studentCountLabel;

    // Phase 5 — File sharing UI refs
    private VBox  fileListBox;       // holds one HBox per file transfer
    private Label fileEmptyLabel;    // shown when no files have been shared yet
    private boolean fileTabHasItems = false;

    // ── Helpers ────────────────────────────────────────────────────────────
    private WhiteboardPane getActivePane() {
        Tab sel = tabPane.getSelectionModel().getSelectedItem();
        if (pptTab  != null && sel == pptTab)  return pptWhiteboardPane;
        if (codeTab != null && sel == codeTab) return null;
        if (fileTab != null && sel == fileTab) return null;
        return whiteboardPane;
    }

    private String getActiveSender() {
        if (tabPane != null && pptTab != null &&
                tabPane.getSelectionModel().getSelectedItem() == pptTab) return "Teacher_PPT";
        return "Teacher";
    }

    public TeacherUI(Stage stage, TeacherServer server) {
        this.stage = stage;
        this.server = server;
        this.studentListView = new ListView<>();
        studentListView.setPlaceholder(new Label("No students connected"));
    }

    public void setServer(TeacherServer server) { this.server = server; }

    // ── Theme application ──────────────────────────────────────────────────
    private void applyTheme(boolean dark) {
        isDarkTheme = dark;
        if (mainScene == null) return;
        mainScene.getStylesheets().clear();
        mainScene.getStylesheets().add(
                getClass().getResource(dark ? THEME_DARK : THEME_LIGHT).toExternalForm());
        whiteboardPane.setCanvasBgColor(dark ? DARK_CANVAS : LIGHT_CANVAS,
                                        dark ? DARK_CONTAINER : LIGHT_CONTAINER);
        pptWhiteboardPane.setCanvasBgColor(dark ? DARK_CANVAS : LIGHT_CANVAS,
                                           dark ? DARK_CONTAINER : LIGHT_CONTAINER);
        if (codeEditor  != null) codeEditor .setStyle(dark ? CODE_EDITOR_DARK : CODE_EDITOR_LIGHT);
        if (lineNumbers != null) lineNumbers.setStyle(dark ? CODE_NUMS_DARK   : CODE_NUMS_LIGHT);
        if (codeArea    != null) codeArea   .setStyle(dark ? CODE_AREA_DARK   : CODE_AREA_LIGHT);
    }

    // ── show() ─────────────────────────────────────────────────────────────
    public void show() {

        // ── THEME TOGGLE ───────────────────────────────────────────────────
        Button themeBtn = new Button("\u263E  Dark Mode");
        themeBtn.getStyleClass().add("btn-theme");
        themeBtn.setOnAction(e -> {
            isDarkTheme = !isDarkTheme;
            applyTheme(isDarkTheme);
            themeBtn.setText(isDarkTheme ? "\u2600  Light Mode" : "\u263E  Dark Mode");
        });

        // ── TOP BAR ───────────────────────────────────────────────────────
        Label titleLabel = new Label("Classroom Collaboration");
        titleLabel.getStyleClass().add("lbl-topbar");

        Separator titleSep = new Separator(javafx.geometry.Orientation.VERTICAL);
        titleSep.setPadding(new Insets(0, 4, 0, 4));

        Label roleLabel = new Label("Teacher");
        roleLabel.setStyle("-fx-text-fill: #2563eb; -fx-font-size: 11px; -fx-font-weight: bold;");

        String localIp;
        try { localIp = InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception ex) { localIp = "Unknown"; }

        Label ipLabel = new Label("  " + localIp + "  :  " +
                (server != null ? server.getPort() : "\u2014"));
        ipLabel.getStyleClass().add("lbl-ip");
        HBox.setHgrow(ipLabel, Priority.ALWAYS);

        Button stopButton = new Button("Stop Session");
        stopButton.getStyleClass().add("btn-danger");
        stopButton.setOnAction(e -> {
            if (pptService != null) pptService.shutdown();
            if (server != null) server.stop();
            stage.close();
        });

        HBox topBar = new HBox(10, titleLabel, titleSep, roleLabel, ipLabel, themeBtn, stopButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");

        // ── LEFT PANEL ─────────────────────────────────────────────────────
        Label listHeader = new Label("Connected Students");
        listHeader.getStyleClass().add("lbl-panel-header");

        studentCountLabel = new Label("0 students");
        studentCountLabel.getStyleClass().add("lbl-count");

        HBox studentHeaderRow = new HBox(8, listHeader, studentCountLabel);
        studentHeaderRow.setAlignment(Pos.CENTER_LEFT);

        VBox leftPanel = new VBox(10, studentHeaderRow, studentListView);
        leftPanel.getStyleClass().add("left-panel");
        VBox.setVgrow(studentListView, Priority.ALWAYS);

        // ── WHITEBOARD PANE ────────────────────────────────────────────────
        whiteboardPane = new WhiteboardPane(true, stroke -> {
            if (server != null) {
                MessageType type = stroke.isAnnotation()
                        ? MessageType.ANNOTATION_STROKE : MessageType.WHITEBOARD_STROKE;
                server.broadcast(new Message(type, stroke, "Teacher"));
            }
        });
        whiteboardPane.setShapeCallbacks(
                shape -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_ADD, shape, "Teacher")); },
                shape -> { if (server != null) server.broadcastLatest("SHAPE_UPDATE_" + shape.getId(), new Message(MessageType.SHAPE_UPDATE, shape, "Teacher")); },
                id    -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_REMOVE, id, "Teacher")); }
        );
        whiteboardPane.setStrokeProgressCallback(stroke -> {
            if (server != null) server.broadcastLatest("STROKE_PROGRESS_Teacher",
                    new Message(MessageType.STROKE_PROGRESS, stroke, "Teacher"));
        });

        pptWhiteboardPane = new WhiteboardPane(true, stroke -> {
            if (server != null) {
                MessageType type = stroke.isAnnotation()
                        ? MessageType.ANNOTATION_STROKE : MessageType.WHITEBOARD_STROKE;
                server.broadcast(new Message(type, stroke, "Teacher_PPT"));
            }
        });
        pptWhiteboardPane.setShapeCallbacks(
                shape -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_ADD, shape, "Teacher_PPT")); },
                shape -> { if (server != null) server.broadcastLatest("SHAPE_UPDATE_" + shape.getId(), new Message(MessageType.SHAPE_UPDATE, shape, "Teacher_PPT")); },
                id    -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_REMOVE, id, "Teacher_PPT")); }
        );
        pptWhiteboardPane.setTransparentBackground(true);
        pptWhiteboardPane.setStrokeProgressCallback(stroke -> {
            if (server != null) server.broadcastLatest("STROKE_PROGRESS_Teacher_PPT",
                    new Message(MessageType.STROKE_PROGRESS, stroke, "Teacher_PPT"));
        });

        if (server != null) {
            server.setStateSupplier(() -> new Message(MessageType.FULL_STATE, whiteboardPane.getFullState(), "Teacher"));
            server.setPptWhiteboardStateSupplier(() -> new Message(MessageType.FULL_STATE, pptWhiteboardPane.getFullState(), "Teacher_PPT"));
        }
        whiteboardPane.setCanvasSize(1280, 720);
        pptWhiteboardPane.setCanvasSize(1280, 720);

        // ── MAIN TOOLBAR ───────────────────────────────────────────────────
        ColorPicker colorPicker = new ColorPicker(Color.BLACK);
        colorPicker.setTooltip(new Tooltip("Stroke Color"));
        colorPicker.setOnAction(e -> {
            whiteboardPane.setCurrentColor(colorPicker.getValue());
            pptWhiteboardPane.setCurrentColor(colorPicker.getValue());
        });

        Slider widthSlider = new Slider(1, 12, 2);
        widthSlider.setShowTickLabels(true);
        widthSlider.setMajorTickUnit(4);
        widthSlider.setPrefWidth(90);
        widthSlider.valueProperty().addListener((obs, o, n) -> {
            whiteboardPane.setStrokeWidth(n.doubleValue());
            pptWhiteboardPane.setStrokeWidth(n.doubleValue());
        });

        ComboBox<String> sizeCombo = new ComboBox<>();
        sizeCombo.getItems().addAll("800x500 (Default)", "1024x768 (Medium)", "1280x720 (Large)", "1920x1080 (HD)");
        sizeCombo.setValue("1280x720 (Large)");
        sizeCombo.setOnAction(e -> {
            String val = sizeCombo.getValue();
            double w = 800, h = 500;
            if (val.contains("1024x768"))  { w = 1024; h = 768; }
            else if (val.contains("1280x720"))  { w = 1280; h = 720; }
            else if (val.contains("1920x1080")) { w = 1920; h = 1080; }
            whiteboardPane.setCanvasSize(w, h);
            pptWhiteboardPane.setCanvasSize(w, h);
            if (server != null) {
                server.broadcast(new Message(MessageType.CANVAS_RESIZE, new double[]{w, h}, "Teacher"));
                server.broadcast(new Message(MessageType.CANVAS_RESIZE, new double[]{w, h}, "Teacher_PPT"));
            }
        });

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton whiteboardMode = new RadioButton("Whiteboard");
        whiteboardMode.setToggleGroup(modeGroup);
        whiteboardMode.setSelected(true);
        RadioButton annotateMode = new RadioButton("Annotate");
        annotateMode.setToggleGroup(modeGroup);
        modeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            whiteboardPane.setAnnotationMode(annotateMode.isSelected());
            pptWhiteboardPane.setAnnotationMode(annotateMode.isSelected());
        });

        Button undoBtn = new Button("Undo");
        undoBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.undo();
            if (server != null) server.broadcast(new Message(MessageType.UNDO, null, getActiveSender()));
        });
        Button redoBtn = new Button("Redo");
        redoBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.redo();
            if (server != null) server.broadcast(new Message(MessageType.REDO, null, getActiveSender()));
        });
        Button clearBoard = new Button("Clear Board");
        clearBoard.getStyleClass().add("btn-subtle");
        clearBoard.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.clearWhiteboard();
            if (server != null) server.broadcast(new Message(MessageType.WHITEBOARD_CLEAR, null, getActiveSender()));
        });
        Button clearAnnotations = new Button("Clear Annotations");
        clearAnnotations.getStyleClass().add("btn-subtle");
        clearAnnotations.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.clearAnnotations();
            if (server != null) server.broadcast(new Message(MessageType.ANNOTATION_CLEAR, null, getActiveSender()));
        });
        Button zoomInBtn  = new Button("Zoom +");
        Button zoomOutBtn = new Button("Zoom \u2212");
        zoomInBtn.setOnAction(e -> { WhiteboardPane p = getActivePane(); if (p != null) p.setZoom(p.getZoom() + 0.1); });
        zoomOutBtn.setOnAction(e -> { WhiteboardPane p = getActivePane(); if (p != null) p.setZoom(p.getZoom() - 0.1); });

        Label sizeLbl  = new Label("Canvas:"); sizeLbl.getStyleClass().add("lbl-section");
        Label colorLbl = new Label("Color:");  colorLbl.getStyleClass().add("lbl-section");
        Label widthLbl = new Label("Width:");  widthLbl.getStyleClass().add("lbl-section");
        Label modeLbl  = new Label("Mode:");   modeLbl.getStyleClass().add("lbl-section");

        this.toolbar = new HBox(8,
                sizeLbl, sizeCombo,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                colorLbl, colorPicker,
                widthLbl, widthSlider,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                modeLbl, whiteboardMode, annotateMode,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                undoBtn, redoBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                clearBoard, clearAnnotations,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                zoomInBtn, zoomOutBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("toolbar-main");

        // ── SHAPE TOOLS ────────────────────────────────────────────────────
        ToggleGroup shapeGroup = new ToggleGroup();
        ToggleButton freehandTb = shapeTool("Freehand",      shapeGroup);
        ToggleButton eraserTb   = shapeTool("Eraser",        shapeGroup);
        ToggleButton rectTb     = shapeTool("Rectangle",     shapeGroup);
        ToggleButton ellipseTb  = shapeTool("Ellipse",       shapeGroup);
        ToggleButton lineTb     = shapeTool("Line",          shapeGroup);
        ToggleButton arrowTb    = shapeTool("Arrow",         shapeGroup);
        ToggleButton textTb     = shapeTool("Text Box",      shapeGroup);
        ToggleButton selectTb   = shapeTool("Select/Resize", shapeGroup);
        freehandTb.setSelected(true);

        shapeGroup.selectedToggleProperty().addListener((obs, old, newT) -> {
            if (newT == null) { freehandTb.setSelected(true); return; }
            DrawMode m = DrawMode.FREEHAND;
            if      (newT == freehandTb) m = DrawMode.FREEHAND;
            else if (newT == eraserTb)   m = DrawMode.ERASER;
            else if (newT == rectTb)     m = DrawMode.SHAPE_RECT;
            else if (newT == ellipseTb)  m = DrawMode.SHAPE_ELLIPSE;
            else if (newT == lineTb)     m = DrawMode.SHAPE_LINE;
            else if (newT == arrowTb)    m = DrawMode.SHAPE_ARROW;
            else if (newT == textTb)     m = DrawMode.SHAPE_TEXT;
            else if (newT == selectTb)   m = DrawMode.SELECT;
            whiteboardPane.setDrawMode(m);
            pptWhiteboardPane.setDrawMode(m);
        });

        Button deleteShapeBtn = new Button("Delete Shape");
        deleteShapeBtn.getStyleClass().add("btn-danger");
        deleteShapeBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane != null) pane.deleteSelectedShape();
        });

        Label drawLbl = new Label("Draw:"); drawLbl.getStyleClass().add("lbl-section");
        this.shapeToolbar = new HBox(6,
                drawLbl,
                freehandTb, eraserTb, rectTb, ellipseTb, lineTb, arrowTb, textTb, selectTb,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                deleteShapeBtn);
        shapeToolbar.setAlignment(Pos.CENTER_LEFT);
        shapeToolbar.getStyleClass().add("toolbar-shape");

        // ── TAB 1: WHITEBOARD ──────────────────────────────────────────────
        javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
        ScrollPane wbScroller = new ScrollPane(canvasGroup);
        wbScroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");
        whiteboardTab = new Tab("  Whiteboard  ", wbScroller);
        whiteboardTab.setClosable(false);

        // ── TAB 2: PPT SHARING ─────────────────────────────────────────────
        Button loadPptBtn = new Button("Load PPTX...");
        loadPptBtn.getStyleClass().add("btn-primary");
        Label pptFileLabel = new Label("No file loaded");
        pptFileLabel.getStyleClass().add("lbl-subtitle");
        HBox.setHgrow(pptFileLabel, Priority.ALWAYS);

        prevSlideBtn = new Button("\u2190 Prev"); prevSlideBtn.setDisable(true);
        slideCountLabel = new Label("\u2014 / \u2014");
        slideCountLabel.getStyleClass().add("lbl-section");
        slideCountLabel.setPadding(new Insets(0, 6, 0, 6));
        nextSlideBtn = new Button("Next \u2192"); nextSlideBtn.setDisable(true);

        HBox pptControls = new HBox(10, loadPptBtn, pptFileLabel,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                prevSlideBtn, slideCountLabel, nextSlideBtn);
        pptControls.setAlignment(Pos.CENTER_LEFT);
        pptControls.getStyleClass().add("ppt-controls");

        pptImageView = new ImageView();
        pptImageView.setPreserveRatio(true);
        pptImageView.setSmooth(true);

        javafx.scene.Group pptCanvasGroup = new javafx.scene.Group(pptWhiteboardPane);
        StackPane pptCenter = new StackPane(pptImageView, pptCanvasGroup);
        pptCenter.getStyleClass().add("ppt-center");
        pptCenter.setAlignment(Pos.CENTER);
        pptImageView.fitWidthProperty().bind(pptCenter.widthProperty());
        pptImageView.fitHeightProperty().bind(pptCenter.heightProperty());

        VBox pptPanel = new VBox(pptControls, pptCenter);
        VBox.setVgrow(pptCenter, Priority.ALWAYS);
        pptTab = new Tab("  PPT Sharing  ", pptPanel);
        pptTab.setClosable(false);

        // ── TAB 3: CODE SHARING ────────────────────────────────────────────
        Label codeStatusLabel = new Label("Changes broadcast automatically");
        codeStatusLabel.getStyleClass().add("lbl-subtitle");
        HBox.setHgrow(codeStatusLabel, Priority.ALWAYS);

        Button clearCodeBtn = new Button("Clear");
        clearCodeBtn.getStyleClass().add("btn-danger");

        HBox codeControls = new HBox(10, codeStatusLabel, clearCodeBtn);
        codeControls.setAlignment(Pos.CENTER_LEFT);
        codeControls.getStyleClass().add("code-toolbar");

        codeEditor = new TextArea();
        codeEditor.setPromptText("Type or paste code here \u2014 it broadcasts to students automatically...");
        codeEditor.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        codeEditor.setWrapText(false);
        codeEditor.setStyle(CODE_EDITOR_LIGHT);
        HBox.setHgrow(codeEditor, Priority.ALWAYS);

        lineNumbers = new TextArea("1");
        lineNumbers.setEditable(false);
        lineNumbers.setFocusTraversable(false);
        lineNumbers.setPrefWidth(45);
        lineNumbers.setMinWidth(45);
        lineNumbers.setMaxWidth(45);
        lineNumbers.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        lineNumbers.setWrapText(false);
        lineNumbers.setStyle(CODE_NUMS_LIGHT);

        codeEditor.textProperty().addListener((obs, old, text) -> {
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= lines.length; i++) { if (i > 1) sb.append("\n"); sb.append(i); }
            lineNumbers.setText(sb.toString());
        });

        codeArea = new HBox(lineNumbers, codeEditor);
        codeArea.setStyle(CODE_AREA_LIGHT);
        VBox.setVgrow(codeArea, Priority.ALWAYS);

        codeEditor.setTextFormatter(new TextFormatter<>(change -> {
            if (change.getText().contains("\t")) change.setText(change.getText().replace("\t", "    "));
            return change;
        }));
        codeEditor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.TAB) {
                e.consume();
                codeEditor.insertText(codeEditor.getCaretPosition(), "    ");
            }
        });

        PauseTransition codeShareDebounce = new PauseTransition(Duration.millis(300));
        codeShareDebounce.setOnFinished(evt -> {
            if (server == null) return;
            String code = codeEditor.getText();
            if (code == null || code.isBlank()) return;
            server.broadcast(new Message(MessageType.CODE_SHARE, new CodeData(code, "Plain Text"), "Teacher"));
            codeStatusLabel.setText("Last synced: " + java.time.LocalTime.now().withNano(0));
            codeStatusLabel.setStyle("-fx-text-fill: #059669;");
        });
        codeEditor.textProperty().addListener((obs, oldVal, newVal) -> codeShareDebounce.playFromStart());

        clearCodeBtn.setOnAction(e -> {
            codeEditor.clear();
            if (server != null) server.broadcast(new Message(MessageType.CODE_SHARE, new CodeData("", "Plain Text"), "Teacher"));
            codeStatusLabel.setText("Code cleared");
            codeStatusLabel.setStyle("-fx-text-fill: #dc2626;");
        });

        VBox codePanel = new VBox(codeControls, codeArea);
        VBox.setVgrow(codePanel, Priority.ALWAYS);
        codeTab = new Tab("  Code Sharing  ", codePanel);
        codeTab.setClosable(false);

        // ── TAB 4: FILE SHARING ────────────────────────────────────────────
        fileTab = buildFileTab();

        // ── TABPANE ────────────────────────────────────────────────────────
        tabPane = new TabPane(whiteboardTab, pptTab, codeTab, fileTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            boolean drawVisible = (newTab != codeTab && newTab != fileTab);
            toolbar.setVisible(drawVisible);     toolbar.setManaged(drawVisible);
            shapeToolbar.setVisible(drawVisible); shapeToolbar.setManaged(drawVisible);
        });
        toolbar.setVisible(true);     toolbar.setManaged(true);
        shapeToolbar.setVisible(true); shapeToolbar.setManaged(true);

        // ── ROOT ───────────────────────────────────────────────────────────
        VBox topSection = new VBox(topBar, toolbar, shapeToolbar);
        BorderPane root = new BorderPane();
        root.setTop(topSection);
        root.setLeft(leftPanel);
        root.setCenter(tabPane);

        // ── PPT SERVICE ────────────────────────────────────────────────────
        pptService = new PptService();
        if (server != null) {
            server.setPptStateSupplier(() ->
                    pptService.isLoaded() ? new Message(MessageType.PPT_SLIDE, pptService.getCurrentSlideData(), "Teacher") : null);
            server.setCodeStateSupplier(() -> {
                if (codeEditor == null) return null;
                String code = codeEditor.getText();
                if (code == null || code.isBlank()) return null;
                return new Message(MessageType.CODE_SHARE, new CodeData(code, "Plain Text"), "Teacher");
            });
        }

        loadPptBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Open PowerPoint File");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PowerPoint Files", "*.pptx"));
            File file = fc.showOpenDialog(stage);
            if (file == null) return;
            loadPptBtn.setDisable(true);
            pptFileLabel.setText("Loading " + file.getName() + "...");
            pptFileLabel.setStyle("-fx-text-fill: #6b7280;");
            pptService.loadAsync(file,
                    () -> {
                        loadPptBtn.setDisable(false);
                        pptFileLabel.setText(file.getName());
                        pptFileLabel.setStyle("");
                        displayAndBroadcastSlide(pptService.getCurrentSlideData());
                        updateNavButtons();
                    },
                    errorMsg -> {
                        loadPptBtn.setDisable(false);
                        pptFileLabel.setText("Failed to load");
                        pptFileLabel.setStyle("-fx-text-fill: #dc2626;");
                        showAlert(Alert.AlertType.ERROR, "PPTX Load Error",
                                "Could not load the selected file", errorMsg);
                    }
            );
        });

        prevSlideBtn.setOnAction(e -> { SlideData sd = pptService.prevSlide(); if (sd != null) { displayAndBroadcastSlide(sd); updateNavButtons(); } });
        nextSlideBtn.setOnAction(e -> { SlideData sd = pptService.nextSlide(); if (sd != null) { displayAndBroadcastSlide(sd); updateNavButtons(); } });

        stage.setOnCloseRequest(e -> {
            if (pptService != null) pptService.shutdown();
            if (server != null) server.stop();
        });

        stage.setMinWidth(900);
        stage.setMinHeight(540);

        mainScene = new Scene(root, 1100, 640);
        mainScene.getStylesheets().add(getClass().getResource(THEME_LIGHT).toExternalForm());
        whiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);
        pptWhiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);

        stage.setScene(mainScene);
        stage.setTitle("Classroom Collaboration \u2014 Teacher");
        stage.show();

        stage.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (tabPane.getSelectionModel().getSelectedItem() != pptTab) return;
            if (pptService == null || !pptService.isLoaded()) return;
            SlideData sd = null;
            if (e.getCode() == javafx.scene.input.KeyCode.RIGHT || e.getCode() == javafx.scene.input.KeyCode.DOWN) { sd = pptService.nextSlide(); e.consume(); }
            else if (e.getCode() == javafx.scene.input.KeyCode.LEFT || e.getCode() == javafx.scene.input.KeyCode.UP) { sd = pptService.prevSlide(); e.consume(); }
            if (sd != null) { displayAndBroadcastSlide(sd); updateNavButtons(); }
        });

        refreshStudentList();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FILE SHARING — Tab builder + send logic
    // ════════════════════════════════════════════════════════════════════════

    /** Builds the complete File Sharing tab content. */
    private Tab buildFileTab() {
        // ── Top toolbar ────────────────────────────────────────────────────
        Button shareFileBtn = new Button("\uD83D\uDCC1  Share File...");
        shareFileBtn.getStyleClass().add("btn-primary");

        Label shareHintLabel = new Label("All connected students will receive the file and can save it.");
        shareHintLabel.getStyleClass().add("lbl-subtitle");
        HBox.setHgrow(shareHintLabel, Priority.ALWAYS);

        HBox fileToolbar = new HBox(12, shareFileBtn, shareHintLabel);
        fileToolbar.setAlignment(Pos.CENTER_LEFT);
        fileToolbar.getStyleClass().add("ppt-controls");

        // ── File list area ─────────────────────────────────────────────────
        fileEmptyLabel = new Label("No files shared yet.\nUse \"Share File...\" to send any file to all students.");
        fileEmptyLabel.getStyleClass().add("lbl-muted");
        fileEmptyLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        fileListBox = new VBox(6);
        fileListBox.setPadding(new Insets(10));
        fileListBox.getChildren().add(fileEmptyLabel);

        ScrollPane fileScroll = new ScrollPane(fileListBox);
        fileScroll.setFitToWidth(true);
        fileScroll.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent;");

        VBox filePanel = new VBox(fileToolbar, fileScroll);
        VBox.setVgrow(fileScroll, Priority.ALWAYS);

        // ── Wire share button ──────────────────────────────────────────────
        shareFileBtn.setOnAction(e -> {
            if (server == null) {
                showAlert(Alert.AlertType.WARNING, "Not Connected",
                        "No active session", "Start a session before sharing files.");
                return;
            }
            List<String> connected = server.getConnectedNames();
            if (connected.isEmpty()) {
                showAlert(Alert.AlertType.INFORMATION, "No Students Connected",
                        "No students are currently connected",
                        "Wait for students to join before sharing files.");
                return;
            }

            FileChooser fc = new FileChooser();
            fc.setTitle("Choose a file to share with all students");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            File chosen = fc.showOpenDialog(stage);
            if (chosen == null) return; // user cancelled

            // Validate the chosen file
            if (!chosen.exists() || !chosen.isFile()) {
                showAlert(Alert.AlertType.ERROR, "Invalid File",
                        "Cannot read file", "The selected file does not exist or is not accessible.");
                return;
            }
            if (chosen.length() == 0) {
                showAlert(Alert.AlertType.ERROR, "Empty File",
                        "File is empty", "Cannot share an empty file.");
                return;
            }

            // Warn for very large files (> 100 MB)
            long fileSize = chosen.length();
            if (fileSize > 100L * 1024 * 1024) {
                Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
                warn.setTitle("Large File Warning");
                warn.setHeaderText("File is larger than 100 MB");
                warn.setContentText("Sharing a " + formatFileSize(fileSize) +
                        " file over LAN may take some time and will temporarily slow down " +
                        "whiteboard updates.\n\nContinue?");
                warn.getDialogPane().getStylesheets().add(
                        getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
                java.util.Optional<ButtonType> result = warn.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.OK) return;
            }

            sendFileAsync(chosen);
        });

        Tab tab = new Tab("  \uD83D\uDCC1 Files  ", filePanel);
        tab.setClosable(false);
        return tab;
    }

    /**
     * Reads the file in 256 KB chunks on a background thread and enqueues
     * FILE_SHARE_START → FILE_CHUNK × N → FILE_SHARE_COMPLETE messages.
     *
     * server.broadcast() calls LinkedBlockingQueue.offer() which is thread-safe,
     * so calling it from this background thread is safe.
     *
     * Platform.runLater() is used for all UI updates (progress bar, status label).
     */
    private void sendFileAsync(File file) {
        String transferId = UUID.randomUUID().toString();
        String fileName   = file.getName();
        long   fileSize   = file.length();
        int    totalChunks = (int) Math.ceil((double) fileSize / CHUNK_SIZE);
        if (totalChunks == 0) totalChunks = 1; // safety for tiny files

        // ── Build UI row for this transfer ─────────────────────────────────
        Label nameLbl = new Label(fileName);
        nameLbl.setMaxWidth(300);
        nameLbl.setTooltip(new Tooltip(file.getAbsolutePath()));

        Label sizeLbl = new Label(formatFileSize(fileSize));
        sizeLbl.getStyleClass().add("lbl-section");
        sizeLbl.setMinWidth(75);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(140);

        Label statusLbl = new Label("Sending...");
        statusLbl.getStyleClass().add("lbl-subtitle");
        HBox.setHgrow(statusLbl, Priority.ALWAYS);

        HBox row = new HBox(12, nameLbl, sizeLbl, progressBar, statusLbl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        // Swap out empty-state label on first file
        if (!fileTabHasItems) {
            fileTabHasItems = true;
            fileListBox.getChildren().remove(fileEmptyLabel);
        }
        fileListBox.getChildren().add(row);

        // Switch to Files tab so teacher can see progress
        tabPane.getSelectionModel().select(fileTab);

        // ── Background sender thread ───────────────────────────────────────
        final int finalTotalChunks = totalChunks;
        Thread sender = new Thread(() -> {
            // 1. Broadcast FILE_SHARE_START (metadata)
            FileShareData startMeta = FileShareData.start(transferId, fileName, fileSize, finalTotalChunks);
            server.broadcast(new Message(MessageType.FILE_SHARE_START, startMeta, "Teacher"));

            // 2. Read and broadcast chunks
            byte[] buf = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            boolean errorOccurred = false;

            try (FileInputStream fis = new FileInputStream(file)) {
                int bytesRead;
                while ((bytesRead = fis.read(buf)) > 0) {
                    // Copy only the actual bytes read (last chunk may be smaller)
                    byte[] chunkBytes = new byte[bytesRead];
                    System.arraycopy(buf, 0, chunkBytes, 0, bytesRead);

                    FileShareData chunk = FileShareData.chunk(
                            transferId, fileName, fileSize, finalTotalChunks, chunkIndex, chunkBytes);
                    server.broadcast(new Message(MessageType.FILE_CHUNK, chunk, "Teacher"));

                    chunkIndex++;
                    final double progress = (double) chunkIndex / finalTotalChunks;
                    Platform.runLater(() -> progressBar.setProgress(progress));
                }
            } catch (IOException ex) {
                errorOccurred = true;
                final String errMsg = ex.getMessage() != null ? ex.getMessage() : "Unknown I/O error";
                Platform.runLater(() -> {
                    progressBar.setProgress(0);
                    statusLbl.setText("\u2717 Error: " + errMsg);
                    statusLbl.setStyle("-fx-text-fill: #dc2626;");
                    row.setStyle("-fx-background-color: #fff5f5; -fx-border-radius: 6; -fx-background-radius: 6;");
                });
            }

            // 3. Broadcast FILE_SHARE_COMPLETE (even on error so students clean up)
            FileShareData complete = FileShareData.complete(transferId, fileName, fileSize);
            server.broadcast(new Message(MessageType.FILE_SHARE_COMPLETE, complete, "Teacher"));

            if (!errorOccurred) {
                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    statusLbl.setText("\u2713 Shared to all students");
                    statusLbl.setStyle("-fx-text-fill: #059669; -fx-font-weight: bold;");
                });
            }
        }, "file-sender-" + transferId.substring(0, 8));
        sender.setDaemon(true);
        sender.start();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Human-readable file size string. */
    private static String formatFileSize(long bytes) {
        if (bytes < 1024L)                return bytes + " B";
        if (bytes < 1024L * 1024)         return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)  return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private ToggleButton shapeTool(String text, ToggleGroup group) {
        ToggleButton tb = new ToggleButton(text);
        tb.setToggleGroup(group);
        return tb;
    }

    private void displayAndBroadcastSlide(SlideData sd) {
        if (sd == null) return;
        pptWhiteboardPane.clearWhiteboard();
        pptWhiteboardPane.clearAnnotations();
        if (server != null) {
            server.broadcast(new Message(MessageType.WHITEBOARD_CLEAR, null, "Teacher_PPT"));
            server.broadcast(new Message(MessageType.ANNOTATION_CLEAR, null, "Teacher_PPT"));
        }
        Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
        pptImageView.setImage(fxImg);
        if (server != null) server.broadcast(new Message(MessageType.PPT_SLIDE, sd, "Teacher"));
    }

    private void updateNavButtons() {
        int idx = pptService.getCurrentIndex(), total = pptService.getTotalSlides();
        prevSlideBtn.setDisable(idx <= 0);
        nextSlideBtn.setDisable(idx >= total - 1);
        slideCountLabel.setText((idx + 1) + " / " + total);
    }

    /** Shows a themed alert dialog. Must be called on the FX thread. */
    private void showAlert(Alert.AlertType type, String title, String header, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
        alert.showAndWait();
    }

    public void refreshStudentList() {
        List<String> names = (server != null) ? server.getConnectedNames() : List.of();
        Platform.runLater(() -> {
            studentListView.getItems().setAll(names);
            if (studentCountLabel != null) {
                int count = names.size();
                studentCountLabel.setText(count + (count == 1 ? " student" : " students"));
            }
        });
    }
}
