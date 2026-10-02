package com.classroom.ui;

import com.classroom.model.CodeData;
import com.classroom.model.FileShareData;
import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.model.ShapeData;
import com.classroom.model.SlideData;
import com.classroom.model.StrokeData;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

public class TeacherUI {

    // ── Theme constants ────────────────────────────────────────────────────
    private static final String THEME_DARK  = "/theme-dark.css";
    private static final String THEME_LIGHT = "/theme-light.css";
    private static final Color  DARK_CANVAS    = Color.web("#1a2035");
    private static final String DARK_CONTAINER = "#0d1117";
    private static final Color  LIGHT_CANVAS    = Color.WHITE;
    private static final String LIGHT_CONTAINER = "#F8F9FA";
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
    private javafx.scene.layout.StackPane toastPane;
    private Tooltip statusDotTooltip;
    private Label ipLabel;
    private String tooltipIps = "Unknown";

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

    private Label      slideCountLabel;
    private Button     prevSlideBtn;
    private Button     nextSlideBtn;

    // Toolbars (shown/hidden on tab switch)
    private VBox toolbar;
    private VBox shapeToolbar;

    private TabPane tabPane;
    private Tab     whiteboardTab;
    private Tab     pptTab;
    private Tab     codeTab;
    private Tab     fileTab;
    private Label   studentCountLabel;
    // wbScroller removed

    // ── Per-slide markings store (PPT export) ─────────────────────────────────
    private final Map<Integer, SavedSlideMarkings> slideMarkingsMap = new HashMap<>();

    private static class SavedSlideMarkings {
        final List<StrokeData> strokes;
        final List<ShapeData>  shapes;
        SavedSlideMarkings(List<StrokeData> strokes, List<ShapeData> shapes) {
            this.strokes = strokes;
            this.shapes  = shapes;
        }
    }

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
        mainScene.getStylesheets().add(getClass().getResource(dark ? THEME_DARK : THEME_LIGHT).toExternalForm());
        mainScene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
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

        // ── TOP BAR / HEADER ITEMS ───────────────────────────────────────────────
        javafx.scene.shape.SVGPath titleIcon = createIcon("M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-1 9H9V9h10v2zm-4 4H9v-2h6v2zm4-8H9V5h10v2z");
        Label titleLabel = new Label("Classroom Collaboration", titleIcon);
        titleLabel.getStyleClass().add("lbl-topbar");

        Label roleLabel = new Label("Teacher");
        roleLabel.getStyleClass().add("role-teacher");

        List<String> candidates = com.classroom.util.NetworkUtil.findBestLocalIp();
        String primaryIp = candidates.isEmpty() ? "Unknown" : candidates.get(0);
        tooltipIps = candidates.isEmpty() ? "Unknown" : String.join("\n", candidates);

        ipLabel = new Label("  " + primaryIp + "  :  " +
                (server != null ? server.getPort() : "\u2014"));
        ipLabel.getStyleClass().add("lbl-ip");
        if (candidates.size() > 1) {
            ipLabel.setTooltip(new Tooltip(tooltipIps));
        }

        Label dotLabel = new Label("●");
        dotLabel.getStyleClass().add("text-success");
        statusDotTooltip = new Tooltip(tooltipIps + "\n0 connected");
        statusDotTooltip.setShowDelay(javafx.util.Duration.millis(400));
        dotLabel.setTooltip(statusDotTooltip);

        Button stopButton = new Button("Stop Session");
        stopButton.getStyleClass().add("btn-outline-danger");
        stopButton.setOnAction(e -> {
            int count = (server != null) ? server.getConnectedNames().size() : 0;
            if (confirmDestructive("Stop Session", "Stop the session for " + count + " connected students?", "This will disconnect everyone.", "Stop session")) {
                if (pptService != null) pptService.shutdown();
                if (server != null) server.stop();
                stage.close();
            }
        });

        javafx.scene.control.CheckMenuItem themeItem = new javafx.scene.control.CheckMenuItem("Dark Mode");
        themeItem.setSelected(isDarkTheme);
        themeItem.setOnAction(e -> {
            isDarkTheme = themeItem.isSelected();
            applyTheme(isDarkTheme);
        });

        javafx.scene.control.CheckMenuItem syncItem = new javafx.scene.control.CheckMenuItem("Lock Tabs");
        syncItem.setSelected(false);
        syncItem.setOnAction(e -> {
            if (syncItem.isSelected()) {
                if (server != null) {
                    server.broadcast(new Message(MessageType.TAB_SWITCH, tabPane.getSelectionModel().getSelectedIndex(), "Teacher"));
                }
                showToast("Tabs locked");
            } else {
                if (server != null) {
                    server.broadcast(new Message(MessageType.TAB_SWITCH, -1, "Teacher")); // -1 to unlock
                }
                showToast("Tabs unlocked");
            }
        });

        javafx.scene.control.MenuButton overflowMenu = new javafx.scene.control.MenuButton("⋯");
        overflowMenu.getStyleClass().add("btn-subtle");
        overflowMenu.getItems().addAll(themeItem, syncItem);

        // ── LEFT PANEL ─────────────────────────────────────────────────────
        Label listHeader = new Label("Connected Students");
        listHeader.getStyleClass().add("lbl-panel-header");

        studentCountLabel = new Label("0 students");
        studentCountLabel.getStyleClass().add("lbl-count");

        VBox studentHeaderBox = new VBox(2, listHeader, studentCountLabel);
        studentHeaderBox.setAlignment(Pos.CENTER_LEFT);

        VBox leftPanel = new VBox(10, studentHeaderBox, studentListView);
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
        // Action will be set later to also trigger text formatting updates

        Slider widthSlider = new Slider(1, 12, 2);
        widthSlider.setShowTickLabels(true);
        widthSlider.setMajorTickUnit(4);
        widthSlider.setPrefWidth(90);
        widthSlider.valueProperty().addListener((obs, o, n) -> {
            whiteboardPane.setStrokeWidth(n.doubleValue());
            pptWhiteboardPane.setStrokeWidth(n.doubleValue());
        });

        ComboBox<String> sizeCombo = new ComboBox<>();
        sizeCombo.getItems().addAll("800x500", "1024x768", "1280x720", "1920x1080");
        sizeCombo.setValue("1280x720");
        sizeCombo.setStyle("-fx-pref-height: 28px; -fx-font-size: 12px;");
        sizeCombo.setOnAction(e -> {
            String val = sizeCombo.getValue();
            double w = 800, h = 500;
            if (val.contains("1024x768"))  { w = 1024; h = 768; }
            else if (val.contains("1280x720"))  { w = 1280; h = 720; }
            else if (val.contains("1920x1080")) { w = 1920; h = 1080; }
            whiteboardPane.setCanvasSize(w, h);
            pptWhiteboardPane.setCanvasSize(w, h);
            whiteboardPane.zoomToFit();
            pptWhiteboardPane.zoomToFit();
            if (server != null) {
                server.broadcast(new Message(MessageType.CANVAS_RESIZE, new double[]{w, h}, "Teacher"));
                server.broadcast(new Message(MessageType.CANVAS_RESIZE, new double[]{w, h}, "Teacher_PPT"));
            }
        });

        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton whiteboardMode = new ToggleButton("Whiteboard");
        whiteboardMode.getStyleClass().addAll("segmented", "segmented-left");
        whiteboardMode.setToggleGroup(modeGroup);
        whiteboardMode.setSelected(true);
        ToggleButton annotateMode = new ToggleButton("Annotate");
        annotateMode.getStyleClass().addAll("segmented", "segmented-right");
        annotateMode.setToggleGroup(modeGroup);
        modeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            boolean ann = (newT == annotateMode);
            whiteboardPane.setAnnotationMode(ann);
            pptWhiteboardPane.setAnnotationMode(ann);
        });

        Button zoomOutBtn = new Button("−"); zoomOutBtn.getStyleClass().add("btn-subtle");
        Button zoomInBtn = new Button("+"); zoomInBtn.getStyleClass().add("btn-subtle");
        Label zoomLabel = new Label("100%");
        zoomLabel.setPrefWidth(45);
        zoomLabel.setAlignment(Pos.CENTER);
        Button zoomFitBtn = new Button("Fit"); zoomFitBtn.getStyleClass().add("btn-subtle");

        zoomInBtn.setOnAction(e -> {
            WhiteboardPane p = getActivePane();
            if (p != null) p.setZoom(p.getZoom() * 1.1);
        });
        zoomOutBtn.setOnAction(e -> {
            WhiteboardPane p = getActivePane();
            if (p != null) p.setZoom(p.getZoom() / 1.1);
        });
        zoomFitBtn.setOnAction(e -> {
            WhiteboardPane p = getActivePane();
            if (p != null) p.zoomToFit();
        });

        whiteboardPane.zoomProperty().addListener((obs, oldV, newV) -> {
            zoomLabel.setText(String.format("%.0f%%", newV.doubleValue() * 100));
        });
        pptWhiteboardPane.zoomProperty().addListener((obs, oldV, newV) -> {
            zoomLabel.setText(String.format("%.0f%%", newV.doubleValue() * 100));
        });

        HBox contextBar = new HBox(12);
        contextBar.setAlignment(Pos.CENTER_LEFT);
        contextBar.getStyleClass().add("context-bar");
        contextBar.setPadding(new Insets(0, 12, 0, 12));
        contextBar.setPrefHeight(40);
        contextBar.setMinHeight(40);

        Label contextSizeLbl = new Label("Canvas:");
        
        javafx.scene.layout.Region spacerContext = new javafx.scene.layout.Region();
        HBox.setHgrow(spacerContext, Priority.ALWAYS);

        HBox modeBox = new HBox(whiteboardMode, annotateMode);
        modeBox.setAlignment(Pos.CENTER);
        
        contextBar.getChildren().addAll(
            modeBox,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            contextSizeLbl, sizeCombo,
            spacerContext,
            zoomOutBtn, zoomLabel, zoomInBtn, zoomFitBtn
        );


        Button undoBtn = iconButton("Undo", "fth-rotate-ccw");
        undoBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            WhiteboardPane.FullState afterUndo = pane.undo();
            if (afterUndo != null && server != null) {
                server.broadcast(new Message(MessageType.UNDO, afterUndo, getActiveSender()));
            }
        });
        Button redoBtn = iconButton("Redo", "fth-rotate-cw");
        redoBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            WhiteboardPane.FullState afterRedo = pane.redo();
            if (afterRedo != null && server != null) {
                server.broadcast(new Message(MessageType.REDO, afterRedo, getActiveSender()));
            }
        });
        Button clearBoard = iconButton("Clear Board", "fth-layers");
        clearBoard.getStyleClass().add("btn-subtle");
        clearBoard.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            if (confirmDestructive("Clear Board", "This removes all content for everyone.", "This can't be undone.", "Clear")) {
                pane.clearWhiteboard();
                if (server != null) server.broadcast(new Message(MessageType.WHITEBOARD_CLEAR, null, getActiveSender()));
                showToast("Board cleared");
            }
        });
        Button clearAnnotations = iconButton("Clear Annotations", "fth-edit-3");
        clearAnnotations.getStyleClass().add("btn-subtle");
        clearAnnotations.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            if (confirmDestructive("Clear Annotations", "This removes all annotations for everyone.", "This can't be undone.", "Clear")) {
                pane.clearAnnotations();
                if (server != null) server.broadcast(new Message(MessageType.ANNOTATION_CLEAR, null, getActiveSender()));
                showToast("Annotations cleared");
            }
        });


        ToggleGroup shapeGroup = new ToggleGroup();
        ToggleButton freehandTb = shapeTool("Freehand", "fth-edit-2", shapeGroup);
        ToggleButton eraserTb   = shapeTool("Eraser", "M15.14 3c-.51 0-1.02.2-1.41.59L2.59 14.73c-.78.77-.78 2.04 0 2.83L5.43 20.4c.39.39.9.59 1.41.59h14.16v-2H12.6l7.85-7.85c.78-.77.78-2.04 0-2.83l-3.9-3.9A1.97 1.97 0 0 0 15.14 3z", shapeGroup);
        ToggleButton rectTb     = shapeTool("Rectangle", "fth-square", shapeGroup);
        ToggleButton ellipseTb  = shapeTool("Ellipse", "fth-circle", shapeGroup);
        ToggleButton lineTb     = shapeTool("Line", "M3 19L19 3L21 5L5 21Z", shapeGroup);
        ToggleButton arrowTb    = shapeTool("Arrow", "fth-arrow-up-right", shapeGroup);
        ToggleButton textTb     = shapeTool("Text", "fth-type", shapeGroup);
        ToggleButton selectTb   = shapeTool("Select/Resize", "fth-mouse-pointer", shapeGroup);
        freehandTb.setSelected(true);

        Button deleteShapeBtn = iconButton("Delete Shape", "fth-trash-2");
        deleteShapeBtn.getStyleClass().add("btn-danger");
        deleteShapeBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane != null) pane.deleteSelectedShape();
        });

        this.toolbar = new VBox(2,
                freehandTb, eraserTb, rectTb, ellipseTb, lineTb, arrowTb, textTb, selectTb,
                new Separator(javafx.geometry.Orientation.HORIZONTAL),
                undoBtn, redoBtn, deleteShapeBtn, clearBoard, clearAnnotations);
        toolbar.setAlignment(Pos.TOP_CENTER);
        toolbar.setPadding(new Insets(10, 0, 10, 0));
        toolbar.getStyleClass().add("toolbar-vertical");
        toolbar.setPrefWidth(56);
        toolbar.setMinWidth(56);
        toolbar.setMaxWidth(56);

        javafx.scene.control.MenuButton moreBtn = new javafx.scene.control.MenuButton();
        org.kordamp.ikonli.javafx.FontIcon moreIcon = new org.kordamp.ikonli.javafx.FontIcon("fth-more-horizontal");
        moreIcon.setIconSize(18);
        moreIcon.getStyleClass().add("icon-svg");
        moreBtn.setGraphic(moreIcon);
        moreBtn.getStyleClass().addAll("tool-btn", "btn-subtle");
        moreBtn.setPrefSize(38, 38);
        
        javafx.scene.control.MenuItem miClearBoard = new javafx.scene.control.MenuItem("Clear Board"); miClearBoard.setOnAction(clearBoard.getOnAction());
        javafx.scene.control.MenuItem miClearAnn = new javafx.scene.control.MenuItem("Clear Annotations"); miClearAnn.setOnAction(clearAnnotations.getOnAction());
        moreBtn.getItems().addAll(miClearBoard, miClearAnn);

        stage.heightProperty().addListener((obs, oldV, newV) -> {
            toolbar.getChildren().clear();
            toolbar.getChildren().addAll(freehandTb, eraserTb, rectTb, ellipseTb, lineTb, arrowTb, textTb, selectTb,
                    new Separator(javafx.geometry.Orientation.HORIZONTAL),
                    undoBtn, redoBtn, deleteShapeBtn);
            if (newV.doubleValue() < 750) {
                toolbar.getChildren().add(new Separator(javafx.geometry.Orientation.HORIZONTAL));
                toolbar.getChildren().add(moreBtn);
            } else {
                toolbar.getChildren().addAll(clearBoard, clearAnnotations);
            }
        });

        // We use shapeToolbar as a dummy container to prevent null errors or hide/show logic issues
        this.shapeToolbar = new VBox();
        this.shapeToolbar.setVisible(false);
        this.shapeToolbar.setManaged(false);

        // ── TEXT FORMAT TOOLBAR ────────────────────────────────────────────
        ComboBox<String> fontFamCombo = new ComboBox<>();
        fontFamCombo.getItems().addAll("System", "Arial", "Courier New", "Times New Roman", "Verdana");
        fontFamCombo.setValue("System");
        fontFamCombo.setPrefWidth(120);

        ComboBox<Double> fontSizeCombo = new ComboBox<>();
        fontSizeCombo.getItems().addAll(12.0, 14.0, 16.0, 18.0, 24.0, 32.0, 48.0, 64.0);
        fontSizeCombo.setValue(24.0);
        fontSizeCombo.setPrefWidth(80);

        ToggleButton boldTb = new ToggleButton("B");
        boldTb.setStyle("-fx-font-weight: bold;");
        ToggleButton italicTb = new ToggleButton("I");
        italicTb.setStyle("-fx-font-style: italic;");
        ToggleButton underlineTb = new ToggleButton("U");
        underlineTb.setStyle("-fx-underline: true;");

        ToggleGroup alignGroup = new ToggleGroup();
        ToggleButton alignLeft = new ToggleButton("Left"); alignLeft.setToggleGroup(alignGroup); alignLeft.setSelected(true);
        ToggleButton alignCenter = new ToggleButton("Center"); alignCenter.setToggleGroup(alignGroup);
        ToggleButton alignRight = new ToggleButton("Right"); alignRight.setToggleGroup(alignGroup);

        VBox textFormatToolbar = new VBox(8,
                new Label("Font:"),
                fontFamCombo,
                fontSizeCombo,
                new HBox(5, boldTb, italicTb, underlineTb),
                new HBox(5, alignLeft, alignCenter, alignRight));
        textFormatToolbar.setAlignment(Pos.CENTER_LEFT);
        textFormatToolbar.getStyleClass().add("toolbar-text-format");
        textFormatToolbar.setVisible(false);
        textFormatToolbar.setManaged(false);

        Runnable applyFormatting = () -> {
            String fontFam = fontFamCombo.getValue();
            double size = fontSizeCombo.getValue();
            boolean isBold = boldTb.isSelected();
            boolean isItalic = italicTb.isSelected();
            boolean isUnderline = underlineTb.isSelected();
            String align = "LEFT";
            if (alignCenter.isSelected()) align = "CENTER";
            if (alignRight.isSelected()) align = "RIGHT";
            
            Color c = colorPicker.getValue();
            String hex = String.format("#%02X%02X%02X", (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));

            WhiteboardPane active = getActivePane();
            if (active != null) {
                active.applyTextFormatting(fontFam, size, isBold, isItalic, isUnderline, align, hex);
            }
        };

        fontFamCombo.setOnAction(e -> applyFormatting.run());
        fontSizeCombo.setOnAction(e -> applyFormatting.run());
        boldTb.setOnAction(e -> applyFormatting.run());
        italicTb.setOnAction(e -> applyFormatting.run());
        underlineTb.setOnAction(e -> applyFormatting.run());
        alignGroup.selectedToggleProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) applyFormatting.run();
        });

        colorPicker.setOnAction(e -> {
            whiteboardPane.setCurrentColor(colorPicker.getValue());
            pptWhiteboardPane.setCurrentColor(colorPicker.getValue());
            applyFormatting.run();
        });

        // ── PROPERTIES POPUP (Prompt 5) ────────────────────────────────────
        Label colorLbl = new Label("Color:");  colorLbl.getStyleClass().add("lbl-section");
        Label widthLbl = new Label("Width:");  widthLbl.getStyleClass().add("lbl-section");
        Label widthValLbl = new Label();
        widthValLbl.getStyleClass().add("lbl-subtitle");
        widthValLbl.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() ->
            String.format("%.0f px", widthSlider.getValue()), widthSlider.valueProperty()));

        HBox colorWidthRow = new HBox(15);
        colorWidthRow.setAlignment(Pos.CENTER_LEFT);
        colorWidthRow.getChildren().addAll(new HBox(5, colorLbl, colorPicker), new HBox(5, widthLbl, widthSlider, widthValLbl));

        VBox propertiesPopup = new VBox(10);
        propertiesPopup.getStyleClass().add("floating-panel");
        propertiesPopup.setAlignment(Pos.CENTER_LEFT);
        propertiesPopup.getChildren().addAll(colorWidthRow, textFormatToolbar);
        propertiesPopup.setOpacity(0.0);
        propertiesPopup.setVisible(false);
        propertiesPopup.setManaged(false);

        Runnable updatePropertiesVisibility = () -> {
            Toggle newT = shapeGroup.getSelectedToggle();
            boolean showProps = false;
            boolean showText = false;
            
            if (newT == freehandTb || newT == rectTb || newT == ellipseTb || newT == lineTb || newT == arrowTb) {
                showProps = true;
            } else if (newT == textTb) {
                showProps = true;
                showText = true;
            }
            
            WhiteboardPane active = getActivePane();
            if (newT == selectTb && active != null && active.getSelectedShapeId() != null && active.getShapeDataMap().get(active.getSelectedShapeId()) != null && active.getShapeDataMap().get(active.getSelectedShapeId()).getType() == ShapeData.ShapeType.TEXT) {
                showProps = true;
                showText = true;
            }

            textFormatToolbar.setVisible(showText);
            textFormatToolbar.setManaged(showText);
            
            if (showText) {
                propertiesPopup.getChildren().setAll(colorWidthRow, textFormatToolbar);
            } else {
                propertiesPopup.getChildren().setAll(colorWidthRow);
            }
            
            if (showProps && !propertiesPopup.isVisible()) {
                propertiesPopup.setVisible(true);
                propertiesPopup.setManaged(true);
                javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(150), propertiesPopup);
                ft.setToValue(1.0);
                ft.play();
            } else if (!showProps && propertiesPopup.isVisible()) {
                javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(150), propertiesPopup);
                ft.setToValue(0.0);
                ft.setOnFinished(e -> {
                    propertiesPopup.setVisible(false);
                    propertiesPopup.setManaged(false);
                });
                ft.play();
            }
        };

        java.util.function.Consumer<String> selectionHandler = id -> {
            WhiteboardPane active = getActivePane();
            if (active == null) return;
            
            boolean isTextSelected = false;
            if (id != null && id.equals("NEW_TEXT")) {
                isTextSelected = true;
            } else if (id != null) {
                ShapeData sd = active.getShapeDataMap().get(id);
                if (sd != null && sd.getType() == ShapeData.ShapeType.TEXT) {
                    isTextSelected = true;
                    // Temporarily disable actions while updating UI
                    fontFamCombo.setOnAction(null);
                    fontSizeCombo.setOnAction(null);
                    
                    fontFamCombo.setValue(sd.getFontFamily());
                    fontSizeCombo.setValue(sd.getFontSize());
                    boldTb.setSelected(sd.isBold());
                    italicTb.setSelected(sd.isItalic());
                    underlineTb.setSelected(sd.isUnderline());
                    if ("CENTER".equals(sd.getTextAlignment())) alignCenter.setSelected(true);
                    else if ("RIGHT".equals(sd.getTextAlignment())) alignRight.setSelected(true);
                    else alignLeft.setSelected(true);
                    colorPicker.setValue(Color.web(sd.getStrokeHex()));
                    
                    // Re-enable actions
                    fontFamCombo.setOnAction(e -> applyFormatting.run());
                    fontSizeCombo.setOnAction(e -> applyFormatting.run());
                }
            } else if (shapeGroup.getSelectedToggle() == textTb) {
                isTextSelected = true;
            }
            
            updatePropertiesVisibility.run();
        };
        
        whiteboardPane.setOnSelectionChanged(selectionHandler);
        pptWhiteboardPane.setOnSelectionChanged(selectionHandler);
        
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
            whiteboardPane.notifyToolPicked();
            pptWhiteboardPane.notifyToolPicked();
            
            updatePropertiesVisibility.run();
        });

        // ── TAB 1: WHITEBOARD ──────────────────────────────────────────────
        javafx.scene.layout.BorderPane whiteboardLayout = new javafx.scene.layout.BorderPane();
        whiteboardLayout.setTop(contextBar);
        whiteboardLayout.setCenter(whiteboardPane);
        whiteboardTab = new Tab("  Whiteboard  ", whiteboardLayout);
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
        Button exportPptBtn = new Button("Export PPT");
        exportPptBtn.setDisable(true); // enabled only when a PPT is loaded

        HBox pptControls = new HBox(10, loadPptBtn, pptFileLabel,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                prevSlideBtn, slideCountLabel, nextSlideBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                exportPptBtn);
        pptControls.setAlignment(Pos.CENTER_LEFT);
        pptControls.getStyleClass().add("ppt-controls");

        VBox pptPanel = new VBox(pptControls, pptWhiteboardPane);
        VBox.setVgrow(pptWhiteboardPane, Priority.ALWAYS);
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
            codeStatusLabel.getStyleClass().setAll("text-success");
        });
        codeEditor.textProperty().addListener((obs, oldVal, newVal) -> codeShareDebounce.playFromStart());

        clearCodeBtn.setOnAction(e -> {
            codeEditor.clear();
            if (server != null) server.broadcast(new Message(MessageType.CODE_SHARE, new CodeData("", "Plain Text"), "Teacher"));
            codeStatusLabel.setText("Code cleared");
            codeStatusLabel.getStyleClass().setAll("text-error");
        });

        VBox codePanel = new VBox(codeControls, codeArea);
        VBox.setVgrow(codePanel, Priority.ALWAYS);
        codeTab = new Tab("  Code Sharing  ", codePanel);
        codeTab.setClosable(false);

        // ── TAB 4: FILE SHARING ────────────────────────────────────────────
        fileTab = buildFileTab();

        // ── TABPANE ────────────────────────────────────────────────────────
        tabPane = new TabPane(whiteboardTab, pptTab, codeTab, fileTab);
        
        undoBtn.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return true;
            return !pane.canUndoProperty().get();
        }, whiteboardPane.canUndoProperty(), pptWhiteboardPane.canUndoProperty(), tabPane.getSelectionModel().selectedIndexProperty()));

        redoBtn.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return true;
            return !pane.canRedoProperty().get();
        }, whiteboardPane.canRedoProperty(), pptWhiteboardPane.canRedoProperty(), tabPane.getSelectionModel().selectedIndexProperty()));
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            boolean drawVisible = (newTab != codeTab && newTab != fileTab);
            toolbar.setVisible(drawVisible);     toolbar.setManaged(drawVisible);
            shapeToolbar.setVisible(drawVisible); shapeToolbar.setManaged(drawVisible);
            
            if (!drawVisible) {
                propertiesPopup.setVisible(false);
                propertiesPopup.setManaged(false);
                propertiesPopup.setOpacity(0.0);
            } else {
                updatePropertiesVisibility.run();
            }
            
            if (syncItem.isSelected() && server != null) {
                server.broadcast(new Message(MessageType.TAB_SWITCH, tabPane.getSelectionModel().getSelectedIndex(), "Teacher"));
            }
        });
        toolbar.setVisible(true);     toolbar.setManaged(true);
        shapeToolbar.setVisible(true); shapeToolbar.setManaged(true);

        ToggleButton rosterToggle = new ToggleButton("Students (0)");
        rosterToggle.getStyleClass().add("btn-subtle");

        // Ensure buttons have enough width
        rosterToggle.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        roleLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        ipLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        dotLabel.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        overflowMenu.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        stopButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        
        // Dynamic student count update
        studentListView.getItems().addListener((javafx.collections.ListChangeListener.Change<? extends String> c) -> {
            int count = studentListView.getItems().size();
            rosterToggle.setText("Students (" + count + ")");
        });

        // ── ROOT ───────────────────────────────────────────────────────────
        javafx.scene.layout.BorderPane mainLayout = new javafx.scene.layout.BorderPane();

        // 1. Top Header
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topHeader = new HBox(8, titleLabel, spacer, rosterToggle, roleLabel, ipLabel, dotLabel, overflowMenu, stopButton);
        topHeader.setAlignment(Pos.CENTER);
        topHeader.setPadding(new Insets(10, 16, 10, 16));
        topHeader.setStyle("-fx-border-color: lightgray; -fx-border-width: 0 0 1 0;");
        topHeader.getStyleClass().add("top-bar");

        mainLayout.setTop(topHeader);

        // 2. Center Content (TabPane)
        mainLayout.setCenter(tabPane);
        
        toastPane = new javafx.scene.layout.StackPane(mainLayout);

        StackPane.setAlignment(propertiesPopup, Pos.TOP_LEFT);
        StackPane.setMargin(propertiesPopup, new Insets(120, 0, 0, 70)); // right of the 56px tool rail
        propertiesPopup.setPickOnBounds(false);
        toastPane.getChildren().add(propertiesPopup);

        // 3. Left Toolbar
        javafx.scene.layout.BorderPane leftSide = new javafx.scene.layout.BorderPane();
        leftSide.setCenter(toolbar);
        
        leftPanel.setPrefWidth(250);
        leftSide.setRight(leftPanel); // Roster sits to the right of the toolbar if visible
        leftPanel.setVisible(false);
        leftPanel.setManaged(false);
        rosterToggle.setSelected(false);
        
        rosterToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            leftPanel.setVisible(newVal);
            leftPanel.setManaged(newVal);
        });

        mainLayout.setLeft(leftSide);

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
            pptFileLabel.getStyleClass().setAll("text-muted");
            pptService.loadAsync(file,
                    () -> {
                        slideMarkingsMap.clear();  // new file — discard any previous slide markings
                        loadPptBtn.setDisable(false);
                        pptFileLabel.setText(file.getName());
                        pptFileLabel.getStyleClass().clear();
                        prevSlideBtn.setDisable(false);
                        nextSlideBtn.setDisable(false);
                        exportPptBtn.setDisable(false);
                        displayAndBroadcastSlide(pptService.getCurrentSlideData());
                        updateNavButtons();
                    },
                    errorMsg -> {
                        loadPptBtn.setDisable(false);
                        pptFileLabel.setText("Failed to load");
                        pptFileLabel.getStyleClass().setAll("text-error");
                        showAlert(Alert.AlertType.ERROR, "PPTX Load Error",
                                "Could not load the selected file", errorMsg);
                    }
            );
        });

        prevSlideBtn.setOnAction(e -> { saveCurrentSlideMarkings(); SlideData sd = pptService.prevSlide(); if (sd != null) { displayAndBroadcastSlide(sd); restoreCurrentSlideMarkings(); updateNavButtons(); } });
        nextSlideBtn.setOnAction(e -> { saveCurrentSlideMarkings(); SlideData sd = pptService.nextSlide(); if (sd != null) { displayAndBroadcastSlide(sd); restoreCurrentSlideMarkings(); updateNavButtons(); } });

        exportPptBtn.setOnAction(e -> {
            if (!pptService.isLoaded()) return;
            saveCurrentSlideMarkings();  // capture the slide currently on screen

            FileChooser fc = new FileChooser();
            fc.setTitle("Export PPT with Markings");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PowerPoint Files", "*.pptx"));
            fc.setInitialFileName("exported_with_markings.pptx");
            File outputFile = fc.showSaveDialog(exportPptBtn.getScene().getWindow());
            if (outputFile == null) return;

            // Flatten per-slide maps (SavedSlideMarkings is private to TeacherUI)
            Map<Integer, List<StrokeData>> strokesPerSlide = new HashMap<>();
            Map<Integer, List<ShapeData>>  shapesPerSlide  = new HashMap<>();
            slideMarkingsMap.forEach((idx, m) -> {
                strokesPerSlide.put(idx, m.strokes);
                shapesPerSlide.put(idx, m.shapes);
            });

            exportPptBtn.setDisable(true);
            exportPptBtn.setText("Exporting...");

            pptService.exportAllSlidesWithMarkings(
                    strokesPerSlide,
                    shapesPerSlide,
                    pptWhiteboardPane.getCanvasW(),
                    pptWhiteboardPane.getCanvasH(),
                    outputFile,
                    () -> {
                        exportPptBtn.setDisable(false);
                        exportPptBtn.setText("Export PPT");
                        showAlert(Alert.AlertType.INFORMATION, "Export Complete",
                                "PPT exported successfully.", outputFile.getAbsolutePath());
                    },
                    err -> {
                        exportPptBtn.setDisable(false);
                        exportPptBtn.setText("Export PPT");
                        showAlert(Alert.AlertType.ERROR, "Export Failed",
                                "Could not export PPT.", err);
                    }
            );
        });

        stage.setOnCloseRequest(e -> {
            if (pptService != null) pptService.shutdown();
            if (server != null) server.stop();
        });

        stage.setMinWidth(1000);
        stage.setMinHeight(650);

        mainScene = stage.getScene();
        if (mainScene != null) {
            mainScene.setRoot(toastPane);
            mainScene.getStylesheets().clear();
            mainScene.getStylesheets().add(getClass().getResource(THEME_LIGHT).toExternalForm());
            mainScene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        } else {
            javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
            mainScene = new Scene(toastPane, screenBounds.getWidth(), screenBounds.getHeight());
            mainScene.getStylesheets().add(getClass().getResource(THEME_LIGHT).toExternalForm());
            mainScene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
            stage.setScene(mainScene);
        }

        whiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);
        pptWhiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);

        stage.setTitle("Classroom Collaboration — Teacher");
        stage.setMaximized(true);
        stage.show();
        Platform.runLater(() -> {
            whiteboardPane.zoomToFit();
            pptWhiteboardPane.zoomToFit();
        });

        stage.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            javafx.scene.Node focusOwner = stage.getScene().getFocusOwner();
            boolean typing = focusOwner instanceof javafx.scene.control.TextInputControl || 
                             (focusOwner != null && focusOwner.getClass().getName().contains("WebView"));
            
            if (tabPane.getSelectionModel().getSelectedItem() == pptTab) {
                if (pptService != null && pptService.isLoaded() && !typing) {
                    SlideData sd = null;
                    if (e.getCode() == javafx.scene.input.KeyCode.RIGHT || e.getCode() == javafx.scene.input.KeyCode.DOWN) { saveCurrentSlideMarkings(); sd = pptService.nextSlide(); e.consume(); }
                    else if (e.getCode() == javafx.scene.input.KeyCode.LEFT || e.getCode() == javafx.scene.input.KeyCode.UP) { saveCurrentSlideMarkings(); sd = pptService.prevSlide(); e.consume(); }
                    if (sd != null) { displayAndBroadcastSlide(sd); restoreCurrentSlideMarkings(); updateNavButtons(); }
                }
            }
            
            if (!typing) {
                switch (e.getCode()) {
                    case P: freehandTb.fire(); e.consume(); break;
                    case E: eraserTb.fire(); e.consume(); break;
                    case R: rectTb.fire(); e.consume(); break;
                    case O: ellipseTb.fire(); e.consume(); break;
                    case L: lineTb.fire(); e.consume(); break;
                    case A: arrowTb.fire(); e.consume(); break;
                    case T: textTb.fire(); e.consume(); break;
                    case V: selectTb.fire(); e.consume(); break;
                    case DELETE: deleteShapeBtn.fire(); e.consume(); break;
                    case Z: 
                        if (e.isControlDown() && !e.isShiftDown()) { undoBtn.fire(); e.consume(); }
                        else if (e.isControlDown() && e.isShiftDown()) { redoBtn.fire(); e.consume(); }
                        break;
                    case Y: 
                        if (e.isControlDown()) { redoBtn.fire(); e.consume(); }
                        break;
                    case EQUALS:
                    case ADD:
                        if (e.isControlDown()) { zoomInBtn.fire(); e.consume(); }
                        break;
                    case MINUS:
                    case SUBTRACT:
                        if (e.isControlDown()) { zoomOutBtn.fire(); e.consume(); }
                        break;
                    case DIGIT0:
                    case NUMPAD0:
                        if (e.isControlDown()) { zoomFitBtn.fire(); e.consume(); }
                        break;
                }
            }
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
            fc.setTitle("Choose one or more files to share with all students");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("All Files", "*.*"));
            List<File> chosen = fc.showOpenMultipleDialog(stage);
            if (chosen == null || chosen.isEmpty()) return; // user cancelled

            // Validate every selected file; collect valid ones and report skipped ones
            List<File> valid    = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (File f : chosen) {
                if (!f.exists() || !f.isFile()) {
                    skipped.add(f.getName() + " \u2014 does not exist or is not accessible");
                } else if (f.length() == 0) {
                    skipped.add(f.getName() + " \u2014 file is empty");
                } else {
                    valid.add(f);
                }
            }
            if (!skipped.isEmpty()) {
                showAlert(Alert.AlertType.WARNING,
                        "Some Files Skipped",
                        skipped.size() + " file(s) could not be shared",
                        String.join("\n", skipped));
            }
            if (valid.isEmpty()) return;

            // Single aggregate large-transfer warning (> 100 MB total)
            long totalSize = 0L;
            for (File f : valid) totalSize += f.length();
            if (totalSize > 100L * 1024 * 1024) {
                Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
                warn.setTitle("Large Transfer Warning");
                warn.setHeaderText(valid.size() + " file(s) \u2014 total " + formatFileSize(totalSize));
                warn.setContentText("Sharing " + formatFileSize(totalSize) +
                        " over LAN may take some time and will temporarily slow down " +
                        "whiteboard updates.\n\nContinue?");
                warn.getDialogPane().getStylesheets().add(
                        getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
                java.util.Optional<ButtonType> result = warn.showAndWait();
                if (result.isEmpty() || result.get() != ButtonType.OK) return;
            }

            // Launch one independent background thread per valid file.
            // Each sendFileAsync() call is self-contained — parallel sends are safe
            // because every transfer has a unique transferId and its own progress row.
            for (File f : valid) {
                sendFileAsync(f);
            }
        });

        Tab tab = new Tab("  \uD83D\uDCC1 Files  ", filePanel);
        tab.setClosable(false);
        return tab;
    }

    /**
     * Reads the file in 256 KB chunks on a background thread and enqueues
     * FILE_SHARE_START → FILE_CHUNK × N → FILE_SHARE_COMPLETE messages.
     *
     * FILE_SHARE_START and FILE_SHARE_COMPLETE use server.broadcast() (high-priority queue).
     * FILE_CHUNK uses server.broadcastFileChunk() (low-priority, BLOCKING queue) so the
     * sender thread stalls on disk reads when the file chunk queue fills, preventing OOM
     * during concurrent large-file transfers. This is safe here because this method always
     * runs on a background thread, never the FX thread.
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
        row.getStyleClass().clear();

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
            // 1. Broadcast FILE_SHARE_START (metadata) — high-priority queue
            FileShareData startMeta = FileShareData.start(transferId, fileName, fileSize, finalTotalChunks);
            server.broadcast(new Message(MessageType.FILE_SHARE_START, startMeta, "Teacher"));

            // 2. Read and broadcast chunks — low-priority blocking queue
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
                    // BLOCKING put — stalls this thread if the file chunk queue is full,
                    // providing backpressure without dropping chunks.
                    try {
                        server.broadcastFileChunk(new Message(MessageType.FILE_CHUNK, chunk, "Teacher"));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        errorOccurred = true;
                        break;
                    }

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
                    statusLbl.getStyleClass().setAll("text-error");
                    row.getStyleClass().setAll("row-error");
                });
            }

            // 3. Broadcast FILE_SHARE_COMPLETE (low-priority queue, ensures ordered delivery AFTER all chunks)
            FileShareData complete = FileShareData.complete(transferId, fileName, fileSize);
            try {
                server.broadcastFileChunk(new Message(MessageType.FILE_SHARE_COMPLETE, complete, "Teacher"));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            if (!errorOccurred) {
                Platform.runLater(() -> {
                    progressBar.setProgress(1.0);
                    statusLbl.setText("\u2713 Shared to all students");
                    statusLbl.getStyleClass().setAll("text-success-bold");
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

    private javafx.scene.shape.SVGPath createIcon(String path) {
        javafx.scene.shape.SVGPath svg = new javafx.scene.shape.SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("icon-svg");
        return svg;
    }

    private Button iconButton(String tooltipText, String iconCode) {
        Button b = new Button();
        b.getStyleClass().add("tool-btn");
        b.setPrefSize(38, 38);
        Tooltip tt = new Tooltip(tooltipText);
        tt.setShowDelay(javafx.util.Duration.millis(400));
        b.setTooltip(tt);
        b.setAccessibleText(tooltipText);
        if (iconCode != null && !iconCode.isEmpty()) {
            if (iconCode.startsWith("M")) {
                b.setGraphic(createIcon(iconCode));
            } else {
                org.kordamp.ikonli.javafx.FontIcon icon = new org.kordamp.ikonli.javafx.FontIcon(iconCode);
                icon.setIconSize(18);
                icon.getStyleClass().add("icon-svg");
                b.setGraphic(icon);
            }
        }
        return b;
    }

    private ToggleButton shapeTool(String tooltipText, String iconCode, ToggleGroup group) {
        ToggleButton tb = new ToggleButton();
        tb.getStyleClass().add("tool-btn");
        tb.setPrefSize(38, 38);
        Tooltip tt = new Tooltip(tooltipText);
        tt.setShowDelay(javafx.util.Duration.millis(400));
        tb.setTooltip(tt);
        tb.setAccessibleText(tooltipText);
        tb.setToggleGroup(group);
        if (iconCode != null && !iconCode.isEmpty()) {
            if (iconCode.startsWith("M")) {
                tb.setGraphic(createIcon(iconCode));
            } else {
                org.kordamp.ikonli.javafx.FontIcon icon = new org.kordamp.ikonli.javafx.FontIcon(iconCode);
                icon.setIconSize(18);
                icon.getStyleClass().add("icon-svg");
                tb.setGraphic(icon);
            }
        }
        return tb;
    }

    private void saveCurrentSlideMarkings() {
        if (pptService == null || !pptService.isLoaded()) return;
        int idx = pptService.getCurrentIndex();
        WhiteboardPane.FullState state = pptWhiteboardPane.getFullState();
        List<StrokeData> strokes = new ArrayList<>(state.strokes);
        List<ShapeData> shapes = new ArrayList<>(pptWhiteboardPane.getShapeDataMap().values());
        if (!strokes.isEmpty() || !shapes.isEmpty()) {
            slideMarkingsMap.put(idx, new SavedSlideMarkings(strokes, shapes));
        } else {
            slideMarkingsMap.remove(idx); // Clear if user erased everything
        }
    }

    private void restoreCurrentSlideMarkings() {
        if (pptService == null || !pptService.isLoaded()) return;
        int idx = pptService.getCurrentIndex();
        SavedSlideMarkings saved = slideMarkingsMap.get(idx);
        if (saved != null) {
            WhiteboardPane.FullState state = new WhiteboardPane.FullState(
                    pptWhiteboardPane.getCanvasW(),
                    pptWhiteboardPane.getCanvasH(),
                    saved.strokes,
                    saved.shapes
            );
            pptWhiteboardPane.applyFullState(state);
            
            // Sync the restored state to all students
            if (server != null) {
                server.broadcast(new Message(MessageType.FULL_STATE, state, "Teacher_PPT"));
            }
        }
    }

    private void displayAndBroadcastSlide(SlideData sd) {
        if (sd == null) return;
        // Note: saveCurrentSlideMarkings() is called by each navigation caller
        // BEFORE nextSlide()/prevSlide() so getCurrentIndex() still has the old index.
        pptWhiteboardPane.clearWhiteboard();
        pptWhiteboardPane.clearAnnotations();
        if (server != null) {
            server.broadcast(new Message(MessageType.WHITEBOARD_CLEAR, null, "Teacher_PPT"));
            server.broadcast(new Message(MessageType.ANNOTATION_CLEAR, null, "Teacher_PPT"));
        }
        Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
        pptWhiteboardPane.setBackgroundImage(fxImg);
        pptWhiteboardPane.zoomToFit();
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
                if (statusDotTooltip != null) {
                    statusDotTooltip.setText(tooltipIps + "\n" + count + " connected");
                }
            }
        });
    }

    private boolean confirmDestructive(String title, String header, String content, String actionBtnText) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        
        javafx.scene.control.DialogPane dialogPane = alert.getDialogPane();
        dialogPane.getStylesheets().add(getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
        dialogPane.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        
        javafx.scene.control.ButtonType actionType = new javafx.scene.control.ButtonType(actionBtnText, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancelType = new javafx.scene.control.ButtonType("Cancel", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancelType, actionType);
        
        javafx.scene.control.Button actionBtn = (javafx.scene.control.Button) dialogPane.lookupButton(actionType);
        if (actionBtn != null) actionBtn.getStyleClass().addAll("btn-danger", "button");
        
        java.util.Optional<javafx.scene.control.ButtonType> res = alert.showAndWait();
        return res.isPresent() && res.get() == actionType;
    }

    private void showToast(String message) {
        if (toastPane == null) return;
        Label toastLabel = new Label(message);
        toastLabel.getStyleClass().add("toast-label");
        
        javafx.scene.layout.StackPane.setAlignment(toastLabel, Pos.BOTTOM_CENTER);
        javafx.scene.layout.StackPane.setMargin(toastLabel, new javafx.geometry.Insets(0, 0, 40, 0));
        
        toastPane.getChildren().add(toastLabel);
        
        javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(300), toastLabel);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.setDelay(javafx.util.Duration.millis(100));
        ft.play();
        
        javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2.5));
        pt.setOnFinished(e -> {
            javafx.animation.FadeTransition fadeOut = new javafx.animation.FadeTransition(javafx.util.Duration.millis(300), toastLabel);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e2 -> toastPane.getChildren().remove(toastLabel));
            fadeOut.play();
        });
        pt.play();
    }
}
