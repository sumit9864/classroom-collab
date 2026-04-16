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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;

public class StudentUI {

    private final Stage stage;
    private StudentClient client;
    private final Label statusLabel;
    private WhiteboardPane whiteboardPane;

    // Phase 3 — PPT instance fields
    private ImageView pptImageView;
    private Tab pptTab;
    private TabPane tabPane;
    private StackPane pptSlidePanel;
    private WhiteboardPane pptWhiteboardPane;

    // Phase 4 — Code Sharing fields
    private Tab      codeTab;
    private TextArea codeViewer;

    public StudentUI(Stage stage, StudentClient client) {
        this.stage = stage;
        this.client = client;
        this.statusLabel = new Label("Connected");
        this.statusLabel.setFont(Font.font("System", 14));
        this.statusLabel.setStyle("-fx-text-fill: #27ae60;");
    }

    /** Called by LoginScreen after client is created (two-step wiring). */
    public void setClient(StudentClient client) {
        this.client = client;
    }

    /** Builds the student window on the existing primary stage. */
    public void show() {
        // ── TOP bar ────────────────────────────────────────────────────────
        Label titleLabel = new Label("LAN Classroom — Student");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        titleLabel.setStyle("-fx-text-fill: white;");
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        Button disconnectButton = new Button("Disconnect");
        disconnectButton.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");
        disconnectButton.setOnAction(e -> {
            if (client != null) client.disconnect();
            stage.close();
        });

        HBox topBar = new HBox(10, titleLabel, disconnectButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10));
        topBar.setStyle("-fx-background-color: #2b2b2b;");

        // ── Whiteboard pane (read-only student mode) ───────────────────────
        whiteboardPane = new WhiteboardPane(false, null);

        // ── Bottom status bar ──────────────────────────────────────────────
        HBox statusBar = new HBox(10);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 1 0 0 0;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button zoomInBtn = new Button("Zoom In");
        zoomInBtn.setOnAction(e -> {
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            if (selected == codeTab) return;                     // no zoom on code tab
            WhiteboardPane active = (selected == pptTab) ? pptWhiteboardPane : whiteboardPane;
            active.setZoom(active.getZoom() + 0.1);
        });

        Button zoomOutBtn = new Button("Zoom Out");
        zoomOutBtn.setOnAction(e -> {
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            if (selected == codeTab) return;
            WhiteboardPane active = (selected == pptTab) ? pptWhiteboardPane : whiteboardPane;
            active.setZoom(active.getZoom() - 0.1);
        });

        statusBar.getChildren().addAll(statusLabel, spacer, zoomInBtn, zoomOutBtn);

        // ── Tab 1: Whiteboard ──────────────────────────────────────────────
        javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
        ScrollPane wbScroller = new ScrollPane(canvasGroup);
        wbScroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");
        Tab whiteboardTab = new Tab("Whiteboard", wbScroller);
        whiteboardTab.setClosable(false);

        // ── Tab 2: PPT Slide ───────────────────────────────────────────────
        pptImageView = new ImageView();
        pptImageView.setPreserveRatio(true);
        pptImageView.setSmooth(true);

        pptWhiteboardPane = new WhiteboardPane(false, null);
        pptWhiteboardPane.setTransparentBackground(true);

        Label waitingLabel = new Label("Waiting for PPT slide from teacher…");
        waitingLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 15;");

        pptSlidePanel = new StackPane();
        pptSlidePanel.setStyle("-fx-background-color: white;");
        pptSlidePanel.setAlignment(Pos.CENTER);
        pptSlidePanel.getChildren().add(waitingLabel);

        // Bind ImageView size to container so it fills all available space
        pptImageView.fitWidthProperty().bind(pptSlidePanel.widthProperty());
        pptImageView.fitHeightProperty().bind(pptSlidePanel.heightProperty());

        pptTab = new Tab("PPT Slide", pptSlidePanel);
        pptTab.setClosable(false);

        // ── Tab 3: Code ────────────────────────────────────────────────────────────
        codeViewer = new TextArea();
        codeViewer.setEditable(false);
        codeViewer.setPromptText("Waiting for teacher to share code\u2026");
        codeViewer.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        codeViewer.setWrapText(false);
        codeViewer.setStyle("-fx-control-inner-background: #1e1e1e; " +
                "-fx-text-fill: #d4d4d4;");  // dark editor theme for contrast

        codeTab = new Tab("Code", codeViewer);
        codeTab.setClosable(false);

        // ── TabPane ────────────────────────────────────────────────────────
        tabPane = new TabPane(whiteboardTab, pptTab, codeTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // ── Root layout ────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(tabPane);
        root.setBottom(statusBar);

        stage.setOnCloseRequest(e -> {
            if (client != null) client.disconnect();
        });

        stage.setScene(new Scene(root, 1000, 620));
        stage.setTitle("Classroom Collaboration — Student");
        stage.show();
    }

    /**
     * Handles incoming messages from StudentClient's listener thread.
     * Already on the FX thread (dispatched via Platform.runLater in StudentClient).
     */
    public void handleMessage(Message msg) {
        boolean isPpt = "Teacher_PPT".equals(msg.getSenderName());
        WhiteboardPane targetPane = isPpt ? pptWhiteboardPane : whiteboardPane;

        switch (msg.getType()) {
            case STUDENT_LIST_UPDATE:
                System.out.println("[StudentUI] Student list updated: " + msg.getPayload());
                break;
            case DISCONNECT:
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Session Ended");
                alert.setHeaderText(null);
                alert.setContentText("Session ended by teacher.");
                alert.showAndWait();
                stage.close();
                break;

            // Phase 2 — Whiteboard & Annotation messages
            case STROKE_PROGRESS:
                if (targetPane != null) {
                    targetPane.applyStrokeProgress((StrokeData) msg.getPayload());
                }
                break;
            case WHITEBOARD_STROKE:
                if (targetPane != null)
                    targetPane.applyStroke((StrokeData) msg.getPayload());
                break;
            case ANNOTATION_STROKE:
                if (targetPane != null)
                    targetPane.applyStroke((StrokeData) msg.getPayload());
                break;
            case WHITEBOARD_CLEAR:
                if (targetPane != null)
                    targetPane.clearWhiteboard();
                break;
            case ANNOTATION_CLEAR:
                if (targetPane != null)
                    targetPane.clearAnnotations();
                break;
            case UNDO:
                if (targetPane != null)
                    targetPane.undo();
                break;
            case REDO:
                if (targetPane != null)
                    targetPane.redo();
                break;
            case CANVAS_RESIZE:
                if (targetPane != null) {
                    double[] size = (double[]) msg.getPayload();
                    targetPane.setCanvasSize(size[0], size[1]);
                }
                break;
            case SHAPE_ADD:
                if (targetPane != null)
                    targetPane.addShape((ShapeData) msg.getPayload());
                break;
            case SHAPE_UPDATE:
                if (targetPane != null)
                    targetPane.updateShape((ShapeData) msg.getPayload());
                break;
            case SHAPE_REMOVE:
                if (targetPane != null)
                    targetPane.removeShape((String) msg.getPayload());
                break;
            case FULL_STATE:
                if (targetPane != null)
                    targetPane.applyFullState((FullState) msg.getPayload());
                break;

            // Phase 3 — PPT Sharing
            case PPT_SLIDE:
                SlideData sd = (SlideData) msg.getPayload();
                Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
                pptImageView.setImage(fxImg);
                // Replace waiting label with image and overlay
                if (!pptSlidePanel.getChildren().contains(pptImageView)) {
                    pptSlidePanel.getChildren().clear();
                    javafx.scene.Group overlayGroup = new javafx.scene.Group(pptWhiteboardPane);
                    pptSlidePanel.getChildren().addAll(pptImageView, overlayGroup);
                }
                // Auto-switch student to PPT tab
                tabPane.getSelectionModel().select(pptTab);
                break;

            // Phase 4 — Code Sharing
            case CODE_SHARE:
                CodeData cd = (CodeData) msg.getPayload();
                codeViewer.setText(cd.getCode());
                tabPane.getSelectionModel().select(codeTab);
                break;

            default:
                System.out.println("[StudentUI] Unhandled message: " + msg.getType());
                break;
        }
    }
}
