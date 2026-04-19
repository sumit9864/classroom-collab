package com.classroom.ui;

import com.classroom.client.StudentClient;
import com.classroom.model.CodeData;
import com.classroom.model.Message;
import com.classroom.model.ShapeData;
import com.classroom.model.SlideData;
import com.classroom.model.StrokeData;
import com.classroom.ui.WhiteboardPane.FullState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

public class StudentUI {

    // ── Theme constants ────────────────────────────────────────────────────
    private static final String THEME_DARK  = "/theme-dark.css";
    private static final String THEME_LIGHT = "/theme-light.css";
    private static final Color  DARK_CANVAS    = Color.web("#1a2035");
    private static final String DARK_CONTAINER = "#0d1117";
    private static final Color  LIGHT_CANVAS    = Color.WHITE;
    private static final String LIGHT_CONTAINER = "#e0e0e0";
    private static final String CODE_VIEWER_DARK  =
            "-fx-control-inner-background: #1e1e1e; -fx-text-fill: #d4d4d4; " +
            "-fx-background-color: #1e1e1e; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_NUMS_DARK    =
            "-fx-control-inner-background: #252526; -fx-text-fill: #858585; " +
            "-fx-background-color: #252526; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_AREA_DARK    = "-fx-background-color: #1e1e1e; -fx-border-width: 0;";
    private static final String CODE_VIEWER_LIGHT =
            "-fx-control-inner-background: #fafafa; -fx-text-fill: #1e1e1e; " +
            "-fx-background-color: #fafafa; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_NUMS_LIGHT   =
            "-fx-control-inner-background: #f0f0f0; -fx-text-fill: #888888; " +
            "-fx-background-color: #f0f0f0; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_AREA_LIGHT   = "-fx-background-color: #fafafa; -fx-border-width: 0;";

    // ── State ──────────────────────────────────────────────────────────────
    private boolean isDarkTheme = false;
    private Scene   mainScene;

    // ── Dynamic refs ───────────────────────────────────────────────────────
    private TextArea codeViewer;
    private TextArea lineNumbers;
    private HBox     codeArea;

    private final Stage stage;
    private StudentClient client;
    private final Label statusLabel;
    private WhiteboardPane whiteboardPane;

    // Phase 3
    private ImageView  pptImageView;
    private Tab        pptTab;
    private TabPane    tabPane;
    private StackPane  pptSlidePanel;
    private WhiteboardPane pptWhiteboardPane;

    // Phase 4
    private Tab codeTab;

    public StudentUI(Stage stage, StudentClient client) {
        this.stage = stage;
        this.client = client;
        this.statusLabel = new Label("Connected");
        this.statusLabel.getStyleClass().add("lbl-status-ok");
    }

    public void setClient(StudentClient client) { this.client = client; }

    // ── Theme switching ────────────────────────────────────────────────────
    private void applyTheme(boolean dark) {
        isDarkTheme = dark;
        if (mainScene == null) return;
        mainScene.getStylesheets().clear();
        mainScene.getStylesheets().add(getClass().getResource(dark ? THEME_DARK : THEME_LIGHT).toExternalForm());
        Color canvas     = dark ? DARK_CANVAS    : LIGHT_CANVAS;
        String container = dark ? DARK_CONTAINER : LIGHT_CONTAINER;
        whiteboardPane.setCanvasBgColor(canvas, container);
        pptWhiteboardPane.setCanvasBgColor(canvas, container);
        if (codeViewer  != null) codeViewer .setStyle(dark ? CODE_VIEWER_DARK : CODE_VIEWER_LIGHT);
        if (lineNumbers != null) lineNumbers.setStyle(dark ? CODE_NUMS_DARK   : CODE_NUMS_LIGHT);
        if (codeArea    != null) codeArea   .setStyle(dark ? CODE_AREA_DARK   : CODE_AREA_LIGHT);
    }

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

        Label roleLabel = new Label("Student");
        roleLabel.setStyle("-fx-text-fill: #059669; -fx-font-size: 11px; -fx-font-weight: bold;");
        HBox.setHgrow(roleLabel, Priority.ALWAYS);

        Button disconnectButton = new Button("Disconnect");
        disconnectButton.getStyleClass().add("btn-danger");
        disconnectButton.setOnAction(e -> {
            if (client != null) client.disconnect();
            stage.close();
        });

        HBox topBar = new HBox(10, titleLabel, titleSep, roleLabel, themeBtn, disconnectButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");

        // ── WHITEBOARDS ────────────────────────────────────────────────────
        whiteboardPane    = new WhiteboardPane(false, null);
        pptWhiteboardPane = new WhiteboardPane(false, null);
        pptWhiteboardPane.setTransparentBackground(true);

        // ── TAB 1: WHITEBOARD ──────────────────────────────────────────────
        javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
        ScrollPane wbScroller = new ScrollPane(canvasGroup);
        wbScroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");
        Tab whiteboardTab = new Tab("  Whiteboard  ", wbScroller);
        whiteboardTab.setClosable(false);

        // ── TAB 2: PPT SLIDE ───────────────────────────────────────────────
        pptImageView = new ImageView();
        pptImageView.setPreserveRatio(true);
        pptImageView.setSmooth(true);

        Label waitingLabel = new Label("Waiting for teacher to share a slide...");
        waitingLabel.getStyleClass().add("lbl-muted");

        pptSlidePanel = new StackPane();
        pptSlidePanel.getStyleClass().add("ppt-center");
        pptSlidePanel.setAlignment(Pos.CENTER);
        pptSlidePanel.getChildren().add(waitingLabel);
        pptImageView.fitWidthProperty().bind(pptSlidePanel.widthProperty());
        pptImageView.fitHeightProperty().bind(pptSlidePanel.heightProperty());

        pptTab = new Tab("  PPT Slide  ", pptSlidePanel);
        pptTab.setClosable(false);

        // ── TAB 3: CODE ────────────────────────────────────────────────────
        codeViewer = new TextArea();
        codeViewer.setEditable(false);
        codeViewer.setPromptText("Waiting for teacher to share code...");
        codeViewer.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        codeViewer.setWrapText(false);
        codeViewer.setStyle(CODE_VIEWER_LIGHT);
        HBox.setHgrow(codeViewer, Priority.ALWAYS);

        lineNumbers = new TextArea("1");
        lineNumbers.setEditable(false);
        lineNumbers.setFocusTraversable(false);
        lineNumbers.setPrefWidth(45);
        lineNumbers.setMinWidth(45);
        lineNumbers.setMaxWidth(45);
        lineNumbers.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        lineNumbers.setWrapText(false);
        lineNumbers.setStyle(CODE_NUMS_LIGHT);

        codeViewer.textProperty().addListener((obs, old, text) -> {
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= lines.length; i++) { if (i > 1) sb.append("\n"); sb.append(i); }
            lineNumbers.setText(sb.toString());
        });

        codeArea = new HBox(lineNumbers, codeViewer);
        codeArea.setStyle(CODE_AREA_LIGHT);
        VBox.setVgrow(codeArea, Priority.ALWAYS);

        // Copy button
        Button copyBtn = new Button("\u2398  Copy to Clipboard");
        copyBtn.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #374151; " +
                "-fx-border-color: #d1d5db; -fx-border-width: 1; -fx-background-radius: 5; -fx-border-radius: 5;");

        PauseTransition copyReset = new PauseTransition(Duration.seconds(2));
        copyReset.setOnFinished(ev -> {
            copyBtn.setText("\u2398  Copy to Clipboard");
            copyBtn.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #374151; " +
                    "-fx-border-color: #d1d5db; -fx-border-width: 1; -fx-background-radius: 5; -fx-border-radius: 5;");
        });
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent clip = new javafx.scene.input.ClipboardContent();
            clip.putString(codeViewer.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
            copyBtn.setText("\u2713  Copied!");
            copyBtn.setStyle("-fx-background-color: #d1fae5; -fx-text-fill: #065f46; " +
                    "-fx-border-color: #059669; -fx-border-width: 1; -fx-background-radius: 5; -fx-border-radius: 5;");
            copyReset.playFromStart();
        });

        HBox codeToolbar = new HBox(copyBtn);
        codeToolbar.setAlignment(Pos.CENTER_LEFT);
        codeToolbar.getStyleClass().add("code-toolbar");

        VBox codeTabContent = new VBox(codeToolbar, codeArea);
        VBox.setVgrow(codeTabContent, Priority.ALWAYS);

        codeTab = new Tab("  Code  ", codeTabContent);
        codeTab.setClosable(false);

        // ── TABPANE ────────────────────────────────────────────────────────
        tabPane = new TabPane(whiteboardTab, pptTab, codeTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ── BOTTOM STATUS BAR ──────────────────────────────────────────────
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button zoomInBtn  = new Button("Zoom +");
        Button zoomOutBtn = new Button("Zoom \u2212");
        zoomInBtn.setOnAction(e -> {
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel == codeTab) return;
            WhiteboardPane active = (sel == pptTab) ? pptWhiteboardPane : whiteboardPane;
            active.setZoom(active.getZoom() + 0.1);
        });
        zoomOutBtn.setOnAction(e -> {
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel == codeTab) return;
            WhiteboardPane active = (sel == pptTab) ? pptWhiteboardPane : whiteboardPane;
            active.setZoom(active.getZoom() - 0.1);
        });

        Label dotLabel = new Label("\u25cf");
        dotLabel.setStyle("-fx-text-fill: #059669; -fx-font-size: 10px;");

        HBox statusBar = new HBox(8, dotLabel, statusLabel, spacer, zoomInBtn, zoomOutBtn);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        // ── ROOT ───────────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(tabPane);
        root.setBottom(statusBar);

        stage.setOnCloseRequest(e -> { if (client != null) client.disconnect(); });
        stage.setMinWidth(800);
        stage.setMinHeight(540);

        if (client != null) {
            client.setOnDisconnect(() -> {
                if (stage.isShowing()) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Connection Lost");
                    alert.setHeaderText("Disconnected from teacher");
                    alert.setContentText("The session has ended or the network connection was lost.");
                    alert.getDialogPane().getStylesheets().add(
                            getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
                    alert.showAndWait();
                    stage.close();
                }
            });
        }

        mainScene = new Scene(root, 1000, 640);
        mainScene.getStylesheets().add(getClass().getResource(THEME_LIGHT).toExternalForm());
        // Light canvas (default)
        whiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);
        pptWhiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);

        stage.setScene(mainScene);
        stage.setTitle("Classroom Collaboration \u2014 Student");
        stage.show();
    }

    public void handleMessage(Message msg) {
        boolean isPpt = "Teacher_PPT".equals(msg.getSenderName());
        WhiteboardPane targetPane = isPpt ? pptWhiteboardPane : whiteboardPane;

        switch (msg.getType()) {
            case STUDENT_LIST_UPDATE:
                System.out.println("[StudentUI] Student list updated: " + msg.getPayload()); break;
            case HEARTBEAT: break;
            case DISCONNECT:
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Session Ended");
                alert.setHeaderText(null);
                alert.setContentText("Session ended by teacher.");
                alert.getDialogPane().getStylesheets().add(
                        getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
                alert.showAndWait();
                stage.close(); break;
            case STROKE_PROGRESS:
                if (targetPane != null) targetPane.applyStrokeProgress((StrokeData) msg.getPayload()); break;
            case WHITEBOARD_STROKE:
                if (targetPane != null) targetPane.applyStroke((StrokeData) msg.getPayload()); break;
            case ANNOTATION_STROKE:
                if (targetPane != null) targetPane.applyStroke((StrokeData) msg.getPayload()); break;
            case WHITEBOARD_CLEAR:
                if (targetPane != null) targetPane.clearWhiteboard(); break;
            case ANNOTATION_CLEAR:
                if (targetPane != null) targetPane.clearAnnotations(); break;
            case UNDO:
                if (targetPane != null) targetPane.undo(); break;
            case REDO:
                if (targetPane != null) targetPane.redo(); break;
            case CANVAS_RESIZE:
                if (targetPane != null) { double[] size = (double[]) msg.getPayload(); targetPane.setCanvasSize(size[0], size[1]); } break;
            case SHAPE_ADD:
                if (targetPane != null) targetPane.addShape((ShapeData) msg.getPayload()); break;
            case SHAPE_UPDATE:
                if (targetPane != null) targetPane.updateShape((ShapeData) msg.getPayload()); break;
            case SHAPE_REMOVE:
                if (targetPane != null) targetPane.removeShape((String) msg.getPayload()); break;
            case FULL_STATE:
                if (targetPane != null) targetPane.applyFullState((FullState) msg.getPayload()); break;
            case PPT_SLIDE:
                SlideData sd = (SlideData) msg.getPayload();
                Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
                pptImageView.setImage(fxImg);
                if (!pptSlidePanel.getChildren().contains(pptImageView)) {
                    pptSlidePanel.getChildren().clear();
                    javafx.scene.Group overlayGroup = new javafx.scene.Group(pptWhiteboardPane);
                    pptSlidePanel.getChildren().addAll(pptImageView, overlayGroup);
                }
                tabPane.getSelectionModel().select(pptTab); break;
            case CODE_SHARE:
                CodeData cd = (CodeData) msg.getPayload();
                codeViewer.setText(cd.getCode());
                if (!cd.getCode().isBlank()) tabPane.getSelectionModel().select(codeTab); break;
            default:
                System.out.println("[StudentUI] Unhandled message: " + msg.getType()); break;
        }
    }
}
