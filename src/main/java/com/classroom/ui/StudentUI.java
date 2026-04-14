package com.classroom.ui;

import com.classroom.client.StudentClient;
import com.classroom.model.Message;
import com.classroom.model.ShapeData;
import com.classroom.model.StrokeData;
import com.classroom.ui.WhiteboardPane.FullState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class StudentUI {

    private final Stage stage;
    private StudentClient client;
    private final Label statusLabel;
    private WhiteboardPane whiteboardPane;

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

        // ── CENTER — WhiteboardPane (read-only student mode) ───────────────
        whiteboardPane = new WhiteboardPane(false, null);

        // Keep statusLabel visible as a small bottom bar
        HBox statusBar = new HBox(10);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 1 0 0 0;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button zoomInBtn = new Button("Zoom In");
        zoomInBtn.setOnAction(e -> whiteboardPane.setZoom(whiteboardPane.getZoom() + 0.1));

        Button zoomOutBtn = new Button("Zoom Out");
        zoomOutBtn.setOnAction(e -> whiteboardPane.setZoom(whiteboardPane.getZoom() - 0.1));

        statusBar.getChildren().addAll(statusLabel, spacer, zoomInBtn, zoomOutBtn);

        javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
        ScrollPane scroller = new ScrollPane(canvasGroup);
        scroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");

        // ── Root layout ────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(scroller);
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
        switch (msg.getType()) {
            case STUDENT_LIST_UPDATE:
                // No UI change needed in Phase 1 — log it
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
            case WHITEBOARD_STROKE:
                if (whiteboardPane != null)
                    whiteboardPane.applyStroke((StrokeData) msg.getPayload());
                break;
            case ANNOTATION_STROKE:
                if (whiteboardPane != null)
                    whiteboardPane.applyStroke((StrokeData) msg.getPayload());
                break;
            case WHITEBOARD_CLEAR:
                if (whiteboardPane != null)
                    whiteboardPane.clearWhiteboard();
                break;
            case ANNOTATION_CLEAR:
                if (whiteboardPane != null)
                    whiteboardPane.clearAnnotations();
                break;
            case UNDO:
                if (whiteboardPane != null)
                    whiteboardPane.undo();
                break;
            case REDO:
                if (whiteboardPane != null)
                    whiteboardPane.redo();
                break;
            case CANVAS_RESIZE:
                if (whiteboardPane != null) {
                    double[] size = (double[]) msg.getPayload();
                    whiteboardPane.setCanvasSize(size[0], size[1]);
                }
                break;
            case SHAPE_ADD:
                if (whiteboardPane != null)
                    whiteboardPane.addShape((ShapeData) msg.getPayload());
                break;
            case SHAPE_UPDATE:
                if (whiteboardPane != null)
                    whiteboardPane.updateShape((ShapeData) msg.getPayload());
                break;
            case SHAPE_REMOVE:
                if (whiteboardPane != null)
                    whiteboardPane.removeShape((String) msg.getPayload());
                break;
            case FULL_STATE:
                if (whiteboardPane != null)
                    whiteboardPane.applyFullState((FullState) msg.getPayload());
                break;

            default:
                System.out.println("[StudentUI] Unhandled message: " + msg.getType());
                // TODO: Phase 3+ will handle PPT, CODE messages here
                break;
        }
    }
}
