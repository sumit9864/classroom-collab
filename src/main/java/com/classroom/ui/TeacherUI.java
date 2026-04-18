package com.classroom.ui;

import com.classroom.model.CodeData;
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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

public class TeacherUI {

    private final Stage stage;
    private TeacherServer server;
    private final ListView<String> studentListView;
    private WhiteboardPane whiteboardPane;

    // Phase 3 — PPT fields
    private PptService pptService;
    private ImageView pptImageView;
    private Label slideCountLabel;
    private Button prevSlideBtn;
    private Button nextSlideBtn;

    // Promoted to instance fields so the tab-switch listener can show/hide them
    private HBox toolbar;
    private HBox shapeToolbar;
    
    // PPT Whiteboard overlay
    private WhiteboardPane pptWhiteboardPane;
    private TabPane tabPane;
    private Tab whiteboardTab;
    private Tab pptTab;

    // Phase 4 — Code Sharing fields
    private Tab      codeTab;
    private TextArea codeEditor;

    private WhiteboardPane getActivePane() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (pptTab  != null && selected == pptTab)  return pptWhiteboardPane;
        if (codeTab != null && selected == codeTab) return null; // no canvas on code tab
        return whiteboardPane;
    }

    private String getActiveSender() {
        if (tabPane != null && pptTab != null && tabPane.getSelectionModel().getSelectedItem() == pptTab) {
            return "Teacher_PPT";
        }
        return "Teacher";
    }

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
            if (pptService != null) pptService.shutdown();
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
                shape -> {
                    if (server != null) server.broadcastLatest(
                        "SHAPE_UPDATE_" + shape.getId(),
                        new Message(MessageType.SHAPE_UPDATE, shape, "Teacher")
                    );
                },
                id    -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_REMOVE, id, "Teacher")); }
        );

        whiteboardPane.setStrokeProgressCallback(stroke -> {
            if (server != null) {
                server.broadcastLatest(
                    "STROKE_PROGRESS_Teacher",
                    new Message(MessageType.STROKE_PROGRESS, stroke, "Teacher")
                );
            }
        });

        // ── PPT Whiteboard overlay ─────────────────────────────────────────
        pptWhiteboardPane = new WhiteboardPane(true, stroke -> {
            if (server != null) {
                MessageType type = stroke.isAnnotation()
                        ? MessageType.ANNOTATION_STROKE
                        : MessageType.WHITEBOARD_STROKE;
                server.broadcast(new Message(type, stroke, "Teacher_PPT"));
            }
        });
        pptWhiteboardPane.setShapeCallbacks(
                shape -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_ADD, shape, "Teacher_PPT")); },
                shape -> {
                    if (server != null) server.broadcastLatest(
                        "SHAPE_UPDATE_" + shape.getId(),
                        new Message(MessageType.SHAPE_UPDATE, shape, "Teacher_PPT")
                    );
                },
                id    -> { if (server != null) server.broadcast(new Message(MessageType.SHAPE_REMOVE, id, "Teacher_PPT")); }
        );
        pptWhiteboardPane.setTransparentBackground(true);

        pptWhiteboardPane.setStrokeProgressCallback(stroke -> {
            if (server != null) {
                server.broadcastLatest(
                    "STROKE_PROGRESS_Teacher_PPT",
                    new Message(MessageType.STROKE_PROGRESS, stroke, "Teacher_PPT")
                );
            }
        });

        // Wire state supplier so late-joining students get a full canvas snapshot
        if (server != null) {
            server.setStateSupplier(() ->
                new Message(MessageType.FULL_STATE, whiteboardPane.getFullState(), "Teacher"));
                
            server.setPptWhiteboardStateSupplier(() ->
                new Message(MessageType.FULL_STATE, pptWhiteboardPane.getFullState(), "Teacher_PPT"));
        }

        // Apply the default "Large" canvas size immediately
        whiteboardPane.setCanvasSize(1280, 720);
        pptWhiteboardPane.setCanvasSize(1280, 720);

        // ── Drawing toolbar (instance field) ──────────────────────────────
        ColorPicker colorPicker = new ColorPicker(Color.BLACK);
        colorPicker.setOnAction(e -> {
            whiteboardPane.setCurrentColor(colorPicker.getValue());
            pptWhiteboardPane.setCurrentColor(colorPicker.getValue());
        });

        Slider widthSlider = new Slider(1, 12, 2);
        widthSlider.setShowTickLabels(true);
        widthSlider.setMajorTickUnit(4);
        widthSlider.setPrefWidth(100);
        widthSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            whiteboardPane.setStrokeWidth(newVal.doubleValue());
            pptWhiteboardPane.setStrokeWidth(newVal.doubleValue());
        });

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
            if (pane == null) return;   // code tab active — no drawing action
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
        clearBoard.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.clearWhiteboard();
            if (server != null)
                server.broadcast(new Message(MessageType.WHITEBOARD_CLEAR, null, getActiveSender()));
        });

        Button clearAnnotations = new Button("Clear Annotations");
        clearAnnotations.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.clearAnnotations();
            if (server != null)
                server.broadcast(new Message(MessageType.ANNOTATION_CLEAR, null, getActiveSender()));
        });

        Button zoomInBtn = new Button("Zoom In");
        zoomInBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.setZoom(pane.getZoom() + 0.1);
        });

        Button zoomOutBtn = new Button("Zoom Out");
        zoomOutBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.setZoom(pane.getZoom() - 0.1);
        });

        // Promoted to instance field
        this.toolbar = new HBox(10,
                new Label("Size:"), sizeCombo,
                new Label("Color:"), colorPicker,
                new Label("Width:"), widthSlider,
                whiteboardMode, annotateMode, undoBtn, redoBtn, clearBoard, clearAnnotations, zoomInBtn, zoomOutBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #ececec; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        // ── Shape tools toolbar (instance field) ──────────────────────────
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
            DrawMode m = DrawMode.FREEHAND;
            if (newT == freehandTb)      m = DrawMode.FREEHAND;
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

        selectTb.setStyle("-fx-background-color: #2980b9; -fx-text-fill: white;");
        selectTb.selectedProperty().addListener((obs, old, selected) ->
                selectTb.setStyle(selected
                        ? "-fx-background-color: #1a5276; -fx-text-fill: white;"
                        : "-fx-background-color: #2980b9; -fx-text-fill: white;"));

        Button deleteShapeBtn = new Button("Delete Shape");
        deleteShapeBtn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        deleteShapeBtn.setOnAction(e -> {
            WhiteboardPane pane = getActivePane();
            if (pane == null) return;
            pane.deleteSelectedShape();
        });

        // Promoted to instance field
        this.shapeToolbar = new HBox(8,
                new Label("Draw:"),
                freehandTb, eraserTb, rectTb, ellipseTb, lineTb, arrowTb, textTb, selectTb,
                new Separator(),
                deleteShapeBtn);
        shapeToolbar.setAlignment(Pos.CENTER_LEFT);
        shapeToolbar.setPadding(new Insets(5, 10, 5, 10));
        shapeToolbar.setStyle("-fx-background-color: #e8e8f0; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        // ── Tab 1: Whiteboard ──────────────────────────────────────────────
        javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
        ScrollPane wbScroller = new ScrollPane(canvasGroup);
        wbScroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");
        whiteboardTab = new Tab("Whiteboard", wbScroller);
        whiteboardTab.setClosable(false);

        // ── Tab 2: PPT Sharing ─────────────────────────────────────────────
        Button loadPptBtn = new Button("Load PPTX…");
        Label pptFileLabel = new Label("No file loaded");
        pptFileLabel.setStyle("-fx-text-fill: #888;");

        prevSlideBtn = new Button("← Prev");
        prevSlideBtn.setDisable(true);
        slideCountLabel = new Label("—");
        nextSlideBtn = new Button("Next →");
        nextSlideBtn.setDisable(true);

        HBox pptControls = new HBox(10,
                loadPptBtn, pptFileLabel,
                new Separator(),
                prevSlideBtn, slideCountLabel, nextSlideBtn);
        pptControls.setAlignment(Pos.CENTER_LEFT);
        pptControls.setPadding(new Insets(8, 12, 8, 12));
        pptControls.setStyle("-fx-background-color: #ececec; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        pptImageView = new ImageView();
        pptImageView.setPreserveRatio(true);
        pptImageView.setSmooth(true);

        // Group the overlay to not mess with scaling bounds during resize
        javafx.scene.Group pptCanvasGroup = new javafx.scene.Group(pptWhiteboardPane);
        
        StackPane pptCenter = new StackPane(pptImageView, pptCanvasGroup);
        pptCenter.setStyle("-fx-background-color: white;");
        pptCenter.setAlignment(Pos.CENTER);

        // Bind ImageView size to container so it fills all available space
        pptImageView.fitWidthProperty().bind(pptCenter.widthProperty());
        pptImageView.fitHeightProperty().bind(pptCenter.heightProperty());

        VBox pptPanel = new VBox(pptControls, pptCenter);
        VBox.setVgrow(pptCenter, Priority.ALWAYS);

        pptTab = new Tab("PPT Sharing", pptPanel);
        pptTab.setClosable(false);

        // ── Tab 3: Code Sharing ────────────────────────────────────────────────

        Label codeStatusLabel = new Label("Changes broadcast automatically");
        codeStatusLabel.setStyle("-fx-text-fill: #888;");

        HBox codeControls = new HBox(10, codeStatusLabel);
        codeControls.setAlignment(Pos.CENTER_LEFT);
        codeControls.setPadding(new Insets(8, 12, 8, 12));
        codeControls.setStyle("-fx-background-color: #ececec; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        codeEditor = new TextArea();
        codeEditor.setPromptText("Type or paste code here, then click \"Share Code\" to broadcast to students\u2026");
        codeEditor.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        codeEditor.setWrapText(false);
        codeEditor.setStyle("-fx-control-inner-background: #1e1e1e; -fx-text-fill: #d4d4d4;");
        codeEditor.setTextFormatter(new TextFormatter<>(change -> {
            if (change.getText().contains("\t")) {
                change.setText(change.getText().replace("\t", "    "));
            }
            return change;
        }));
        codeEditor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.TAB) {
                e.consume(); // Prevent focus shift
                codeEditor.insertText(codeEditor.getCaretPosition(), "    ");
            }
        });

        // Debounced real-time code sharing: broadcast 300ms after the teacher stops typing.
        PauseTransition codeShareDebounce = new PauseTransition(Duration.millis(300));
        codeShareDebounce.setOnFinished(evt -> {
            if (server == null) return;
            String code = codeEditor.getText();
            if (code == null || code.isBlank()) return;
            server.broadcast(new Message(MessageType.CODE_SHARE, new CodeData(code, "Plain Text"), "Teacher"));
            codeStatusLabel.setText("Last synced: " + java.time.LocalTime.now().withNano(0));
            codeStatusLabel.setStyle("-fx-text-fill: #27ae60;");
        });
        codeEditor.textProperty().addListener((obs, oldVal, newVal) -> codeShareDebounce.playFromStart());

        VBox.setVgrow(codeEditor, Priority.ALWAYS);

        VBox codePanel = new VBox(codeControls, codeEditor);
        VBox.setVgrow(codePanel, Priority.ALWAYS);

        codeTab = new Tab("Code Sharing", codePanel);
        codeTab.setClosable(false);

        // ── TabPane ────────────────────────────────────────────────────────
        tabPane = new TabPane(whiteboardTab, pptTab, codeTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // Hide drawing toolbars when Code tab is active
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            boolean drawVisible = (newTab != codeTab);
            toolbar.setVisible(drawVisible);
            toolbar.setManaged(drawVisible);
            shapeToolbar.setVisible(drawVisible);
            shapeToolbar.setManaged(drawVisible);
        });
        // Initial state (whiteboard tab is selected by default)
        toolbar.setVisible(true);
        toolbar.setManaged(true);
        shapeToolbar.setVisible(true);
        shapeToolbar.setManaged(true);

        // ── Root layout ────────────────────────────────────────────────────
        VBox topSection = new VBox(topBar, toolbar, shapeToolbar);

        BorderPane root = new BorderPane();
        root.setTop(topSection);
        root.setLeft(leftPanel);
        root.setCenter(tabPane);

        // ── PptService init + supplier wiring ──────────────────────────────
        pptService = new PptService();
        if (server != null) {
            server.setPptStateSupplier(() ->
                pptService.isLoaded()
                    ? new Message(MessageType.PPT_SLIDE, pptService.getCurrentSlideData(), "Teacher")
                    : null);
        }

        // Wire code state supplier for late-join sync
        if (server != null) {
            server.setCodeStateSupplier(() -> {
                if (codeEditor == null) return null;
                String code = codeEditor.getText();
                if (code == null || code.isBlank()) return null;
                return new Message(MessageType.CODE_SHARE, new CodeData(code, "Plain Text"), "Teacher");
            });
        }

        // ── Load PPTX button ───────────────────────────────────────────────
        loadPptBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Open PowerPoint File");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PowerPoint Files", "*.pptx"));
            File file = fc.showOpenDialog(stage);
            if (file == null) return;

            loadPptBtn.setDisable(true);
            pptFileLabel.setText("Loading " + file.getName() + "…");
            pptFileLabel.setStyle("-fx-text-fill: #555;");

            pptService.loadAsync(file,
                // onSuccess — called on FX thread
                () -> {
                    loadPptBtn.setDisable(false);
                    pptFileLabel.setText(file.getName());
                    pptFileLabel.setStyle("-fx-text-fill: #222;");
                    displayAndBroadcastSlide(pptService.getCurrentSlideData());
                    updateNavButtons();
                },
                // onError — called on FX thread
                errorMsg -> {
                    loadPptBtn.setDisable(false);
                    pptFileLabel.setText("Failed to load");
                    pptFileLabel.setStyle("-fx-text-fill: #c0392b;");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("PPTX Load Error");
                    alert.setHeaderText("Could not load the selected file");
                    alert.setContentText(errorMsg);
                    alert.showAndWait();
                }
            );
        });

        prevSlideBtn.setOnAction(e -> {
            SlideData sd = pptService.prevSlide();
            if (sd != null) { displayAndBroadcastSlide(sd); updateNavButtons(); }
        });

        nextSlideBtn.setOnAction(e -> {
            SlideData sd = pptService.nextSlide();
            if (sd != null) { displayAndBroadcastSlide(sd); updateNavButtons(); }
        });

        // ── Close handler ──────────────────────────────────────────────────
        stage.setOnCloseRequest(e -> {
            if (pptService != null) pptService.shutdown();
            if (server != null) server.stop();
        });

        stage.setMinWidth(900);
        stage.setMinHeight(540);
        stage.setScene(new Scene(root, 1100, 620));
        stage.setTitle("Classroom Collaboration — Teacher");
        stage.show();

        // Initial list population
        refreshStudentList();
    }

    /** Displays a slide image locally and broadcasts it to all students. */
    private void displayAndBroadcastSlide(SlideData sd) {
        if (sd == null) return;
        
        // Clear PPT drawings upon setting a new slide
        pptWhiteboardPane.clearWhiteboard();
        pptWhiteboardPane.clearAnnotations();
        if (server != null) {
            server.broadcast(new Message(MessageType.WHITEBOARD_CLEAR, null, "Teacher_PPT"));
            server.broadcast(new Message(MessageType.ANNOTATION_CLEAR, null, "Teacher_PPT"));
        }

        Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
        pptImageView.setImage(fxImg);
        if (server != null) {
            server.broadcast(new Message(MessageType.PPT_SLIDE, sd, "Teacher"));
        }
    }

    /** Enables/disables Prev and Next buttons based on current slide position. */
    private void updateNavButtons() {
        int idx   = pptService.getCurrentIndex();
        int total = pptService.getTotalSlides();
        prevSlideBtn.setDisable(idx <= 0);
        nextSlideBtn.setDisable(idx >= total - 1);
        slideCountLabel.setText((idx + 1) + " / " + total);
    }

    /**
     * Refreshes the student ListView from the server's current connected list.
     * Safe to call from any thread — always dispatches to the FX thread.
     * This method is also passed as the onClientListChanged callback to TeacherServer.
     */
    public void refreshStudentList() {
        List<String> names = (server != null) ? server.getConnectedNames() : List.of();
        Platform.runLater(() -> studentListView.getItems().setAll(names));
    }
}
