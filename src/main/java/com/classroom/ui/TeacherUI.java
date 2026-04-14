package com.classroom.ui;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.server.TeacherServer;
import com.classroom.ui.WhiteboardPane.DrawMode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.util.List;

public class TeacherUI {

    private final Stage stage;
    private TeacherServer server;
    private final ListView<String> studentListView;
    private WhiteboardPane whiteboardPane;

    public TeacherUI(Stage stage, TeacherServer server) {
        this.stage = stage;
        this.server = server;
        this.studentListView = new ListView<>();
        studentListView.setPrefWidth(200);
        studentListView.setPlaceholder(new Label("No students connected"));
    }

    /** Called by LoginScreen after server is created (two-step wiring). */
    public void setServer(TeacherServer server) {
        this.server = server;
    }

    /** Builds the teacher window and sets it on the existing primary stage. */
    public void show() {
        // ── TOP bar ────────────────────────────────────────────────────────
        Label titleLabel = new Label("LAN Classroom — Teacher");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 16));
        HBox.setHgrow(titleLabel, Priority.ALWAYS);

        Button stopButton = new Button("Stop Session");
        stopButton.setOnAction(e -> {
            if (server != null) server.stop();
            stage.close();
        });

        HBox topBar = new HBox(10, titleLabel, stopButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(10));
        topBar.setStyle("-fx-background-color: #2b2b2b;");
        titleLabel.setStyle("-fx-text-fill: white;");
        stopButton.setStyle("-fx-background-color: #c0392b; -fx-text-fill: white;");

        // ── LEFT panel — student list ──────────────────────────────────────
        Label listHeader = new Label("Connected Students");
        listHeader.setFont(Font.font("System", FontWeight.BOLD, 13));
        listHeader.setPadding(new Insets(0, 0, 6, 0));

        VBox leftPanel = new VBox(8, listHeader, studentListView);
        leftPanel.setPadding(new Insets(12));
        leftPanel.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-border-width: 0 1 0 0;");

        // ── CENTER — WhiteboardPane (teacher mode with broadcast callback) ─
        whiteboardPane = new WhiteboardPane(true, stroke -> {
            if (server != null) {
                MessageType type = stroke.isAnnotation()
                        ? MessageType.ANNOTATION_STROKE
                        : MessageType.WHITEBOARD_STROKE;
                server.broadcast(new Message(type, stroke, "Teacher"));
            }
        });

        // Wire shape broadcast callbacks
        whiteboardPane.setShapeCallbacks(
                shape -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_ADD, shape, "Teacher")); },
                shape -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_UPDATE, shape, "Teacher")); },
                id    -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_REMOVE, id, "Teacher")); }
        );

        // Wire state supplier so late-joining students get a full canvas snapshot
        if (server != null) {
            server.setStateSupplier(() ->
                new Message(MessageType.FULL_STATE, whiteboardPane.getFullState(), "Teacher"));
        }

        // Apply the default "Large" canvas size immediately
        whiteboardPane.setCanvasSize(1280, 720);

        // ── Drawing toolbar ────────────────────────────────────────────────
        ColorPicker colorPicker = new ColorPicker(Color.BLACK);
        colorPicker.setOnAction(e -> whiteboardPane.setCurrentColor(colorPicker.getValue()));

        Slider widthSlider = new Slider(1, 12, 2);
        widthSlider.setShowTickLabels(true);
        widthSlider.setMajorTickUnit(4);
        widthSlider.setPrefWidth(100);
        widthSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                whiteboardPane.setStrokeWidth(newVal.doubleValue()));

        ComboBox<String> sizeCombo = new ComboBox<>();
        sizeCombo.getItems().addAll(
                "800x500 (Default)",
                "1024x768 (Medium)",
                "1280x720 (Large)",
                "1920x1080 (HD)"
        );
        sizeCombo.setValue("1280x720 (Large)");
        sizeCombo.setOnAction(e -> {
            String val = sizeCombo.getValue();
            double w = 800, h = 500;
            if (val.contains("1024x768")) { w = 1024; h = 768; }
            else if (val.contains("1280x720")) { w = 1280; h = 720; }
            else if (val.contains("1920x1080")) { w = 1920; h = 1080; }
            
            whiteboardPane.setCanvasSize(w, h);
            if (server != null) {
                server.broadcast(new Message(MessageType.CANVAS_RESIZE, new double[]{w, h}, "Teacher"));
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
        });

        Button undoBtn = new Button("Undo");
        undoBtn.setOnAction(e -> {
            whiteboardPane.undo();
            if (server != null) server.broadcast(new Message(MessageType.UNDO, null, "Teacher"));
        });

        Button redoBtn = new Button("Redo");
        redoBtn.setOnAction(e -> {
            whiteboardPane.redo();
            if (server != null) server.broadcast(new Message(MessageType.REDO, null, "Teacher"));
        });

        Button clearBoard = new Button("Clear Board");
        clearBoard.setOnAction(e -> {
            whiteboardPane.clearWhiteboard();
            if (server != null)
                server.broadcast(new Message(MessageType.WHITEBOARD_CLEAR, null, "Teacher"));
        });

        Button clearAnnotations = new Button("Clear Annotations");
        clearAnnotations.setOnAction(e -> {
            whiteboardPane.clearAnnotations();
            if (server != null)
                server.broadcast(new Message(MessageType.ANNOTATION_CLEAR, null, "Teacher"));
        });

        Button zoomInBtn = new Button("Zoom In");
        zoomInBtn.setOnAction(e -> whiteboardPane.setZoom(whiteboardPane.getZoom() + 0.1));

        Button zoomOutBtn = new Button("Zoom Out");
        zoomOutBtn.setOnAction(e -> whiteboardPane.setZoom(whiteboardPane.getZoom() - 0.1));

        HBox toolbar = new HBox(10,
                new Label("Size:"), sizeCombo,
                new Label("Color:"), colorPicker,
                new Label("Width:"), widthSlider,
                whiteboardMode, annotateMode, undoBtn, redoBtn, clearBoard, clearAnnotations, zoomInBtn, zoomOutBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #ececec; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        // ── Shape tools toolbar ────────────────────────────────────────────
        ToggleGroup shapeGroup = new ToggleGroup();
        ToggleButton freehandTb  = new ToggleButton("Freehand");
        ToggleButton eraserTb    = new ToggleButton("Eraser");
        ToggleButton rectTb      = new ToggleButton("Rectangle");
        ToggleButton ellipseTb   = new ToggleButton("Ellipse");
        ToggleButton lineTb      = new ToggleButton("Line");
        ToggleButton arrowTb     = new ToggleButton("Arrow");
        ToggleButton textTb      = new ToggleButton("Text Box");
        ToggleButton selectTb    = new ToggleButton("Select/Resize");

        for (ToggleButton tb : java.util.List.of(freehandTb, eraserTb, rectTb, ellipseTb, lineTb, arrowTb, textTb, selectTb)) {
            tb.setToggleGroup(shapeGroup);
        }
        freehandTb.setSelected(true);

        shapeGroup.selectedToggleProperty().addListener((obs, old, newT) -> {
            if (newT == null) { freehandTb.setSelected(true); return; }
            if (newT == freehandTb) whiteboardPane.setDrawMode(DrawMode.FREEHAND);
            else if (newT == eraserTb)  whiteboardPane.setDrawMode(DrawMode.ERASER);
            else if (newT == rectTb)    whiteboardPane.setDrawMode(DrawMode.SHAPE_RECT);
            else if (newT == ellipseTb) whiteboardPane.setDrawMode(DrawMode.SHAPE_ELLIPSE);
            else if (newT == lineTb)    whiteboardPane.setDrawMode(DrawMode.SHAPE_LINE);
            else if (newT == arrowTb)   whiteboardPane.setDrawMode(DrawMode.SHAPE_ARROW);
            else if (newT == textTb)    whiteboardPane.setDrawMode(DrawMode.SHAPE_TEXT);
            else if (newT == selectTb)  whiteboardPane.setDrawMode(DrawMode.SELECT);
        });

        selectTb.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white;");
        selectTb.selectedProperty().addListener((obs, old, selected) ->
                selectTb.setStyle(selected
                        ? "-fx-background-color: #1a5276; -fx-text-fill: white;"
                        : "-fx-background-color: #2980b9; -fx-text-fill: white;"));

        Button deleteShapeBtn = new Button("Delete Shape");
        deleteShapeBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        deleteShapeBtn.setOnAction(e -> whiteboardPane.deleteSelectedShape());

        HBox shapeToolbar = new HBox(8,
                new Label("Draw:"),
                freehandTb, eraserTb, rectTb, ellipseTb, lineTb, arrowTb, textTb, selectTb,
                new Separator(),
                deleteShapeBtn);
        shapeToolbar.setAlignment(Pos.CENTER_LEFT);
        shapeToolbar.setPadding(new Insets(5, 10, 5, 10));
        shapeToolbar.setStyle("-fx-background-color: #e8e8f0; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        // ── Root layout ────────────────────────────────────────────────────
        VBox topSection = new VBox(topBar, toolbar, shapeToolbar);

        javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
        ScrollPane scroller = new ScrollPane(canvasGroup);
        scroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");

        BorderPane root = new BorderPane();
        root.setTop(topSection);
        root.setLeft(leftPanel);
        root.setCenter(scroller);

        // Close handler
        stage.setOnCloseRequest(e -> {
            if (server != null) server.stop();
        });

        stage.setScene(new Scene(root, 1100, 620));
        stage.setTitle("Classroom Collaboration — Teacher");
        stage.show();

        // Initial list population
        refreshStudentList();
    }

    /**
     * Refreshes the student ListView from the server's current connected list.
     * Safe to call from any thread — always dispatches to the FX thread.
     * This method is also passed as the onClientListChanged callback to TeacherServer.
     */
    public void refreshStudentList() {
        List<String> names = (server != null) ? server.getConnectedNames() : List.of();
        Platform.runLater(() -> {
            studentListView.getItems().setAll(names);
        });
    }
}
