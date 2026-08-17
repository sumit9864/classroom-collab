package com.classroom.ui;

import com.classroom.model.ShapeData;
import com.classroom.model.ShapeData.ShapeType;
import com.classroom.model.StrokeData;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.FontPosture;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.Text;
import javafx.scene.input.KeyCode;
import javafx.geometry.VPos;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class WhiteboardPane extends StackPane {

    // ── Drawing mode ──────────────────────────────────────────────────────────
    public enum DrawMode { FREEHAND, ERASER, SHAPE_RECT, SHAPE_ELLIPSE, SHAPE_LINE, SHAPE_ARROW, SHAPE_TEXT, SELECT }

    private static final double HANDLE_SZ = 8.0;

    // ── Canvas layers ─────────────────────────────────────────────────────────
    private final Canvas          whiteboardCanvas;
    private final Canvas          annotationCanvas;
    private final GraphicsContext wbGc;
    private final GraphicsContext annGc;

    // Student-side temporary canvas that renders the teacher's in-progress stroke.
    // Lives on top of all other layers. Cleared atomically when the final stroke commits.
    private final Canvas progressOverlayCanvas;
    private final GraphicsContext progressGc;

    // ── Shape overlay (JavaFX nodes, on top of canvases) ─────────────────────
    private final Pane shapeOverlayPane;

    // ── Mode & drawing state ──────────────────────────────────────────────────
    private final boolean teacherMode;
    private boolean annotationMode = false;
    private DrawMode drawMode      = DrawMode.FREEHAND;
    private Color  currentColor       = Color.BLACK;
    private Color  canvasBgColor      = Color.WHITE;        // canvas fill (theme-aware)
    private String containerBgStyle   = "#e0e0e0";          // outer pane bg (theme-aware)
    private double strokeWidth     = 2.0;
    private double zoomLevel       = 1.0;
    // Scale transform with pivot at (0,0) — keeps the Group's bounds non-negative
    // so the centering StackPane positions the canvas symmetrically (no left/top bias).
    private final javafx.scene.transform.Scale scaleTransform =
            new javafx.scene.transform.Scale(1, 1, 0, 0);
    private boolean isTransparentBackground = false;

    // ── Unified Action History ────────────────────────────────────────────────
    public static class BoardAction {
        public enum Type { STROKE, SHAPE_ADD, SHAPE_UPDATE, SHAPE_REMOVE }
        public final Type type;
        public final StrokeData stroke;
        public final ShapeData shape;
        public final ShapeData oldShape;

        public BoardAction(Type type, StrokeData stroke, ShapeData shape, ShapeData oldShape) {
            this.type = type; this.stroke = stroke; this.shape = shape; this.oldShape = oldShape;
        }

        public boolean isAnnotation() {
            if (type == Type.STROKE && stroke != null) return stroke.isAnnotation();
            if (shape != null) return shape.isAnnotation();
            if (oldShape != null) return oldShape.isAnnotation();
            return false;
        }
    }

    private final LinkedList<BoardAction> history   = new LinkedList<>();
    private final LinkedList<BoardAction> redoStack = new LinkedList<>();
    private boolean isUndoRedo = false;

    // ── Serializable full-state snapshot for late-join sync ───────────────────
    public static class FullState implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final double canvasW;
        public final double canvasH;
        public final List<StrokeData> strokes;
        public final List<ShapeData>  shapes;

        public FullState(double canvasW, double canvasH,
                         List<StrokeData> strokes, List<ShapeData> shapes) {
            this.canvasW = canvasW;
            this.canvasH = canvasH;
            this.strokes = strokes;
            this.shapes  = shapes;
        }
    }

    // ── Freehand Stroke State ──────────────────────────────────────────────────
    private final List<double[]>         currentPoints = new ArrayList<>();
    private double lastX, lastY;

    // ── Shape data & nodes ────────────────────────────────────────────────────
    // Insertion-ordered so z-order is preserved
    private final Map<String, ShapeData> shapeDataMap = new LinkedHashMap<>();
    private final Map<String, Group>     shapeNodeMap = new LinkedHashMap<>();

    // ── Selection state (teacher only) ────────────────────────────────────────
    private String               selectedShapeId = null;

    public String getSelectedShapeId() {
        return selectedShapeId;
    }
    private final List<Rectangle> handles        = new ArrayList<>();

    // Shape creation drag
    private double       shapeDragX, shapeDragY;
    private javafx.scene.Node previewNode = null;

    // ID of the shape currently being drawn via mousePressed → mouseDragged → mouseReleased.
    // Null when not drawing a shape. Used to stream SHAPE_UPDATE in real time during draw.
    private String currentDragShapeId = null;

    // SELECT mode drag
    private enum SelectAction { NONE, MOVING, RESIZING }
    private SelectAction selectAction  = SelectAction.NONE;
    private int          activeHandle  = -1;
    private double       sDragX, sDragY;
    private double       origX, origY, origW, origH;
    private ShapeData    origSdCopy;

    // ── Text Inline Editing State ─────────────────────────────────────────────
    private String editingTextId = null;
    private TextArea editingTextArea = null;
    private boolean textClickPending = false;
    private double textPressX, textPressY;
    private Consumer<String> onSelectionChanged;
    private long lastMeasureNs = 0;

    // Default formatting for new TEXT shapes
    private String  defaultFontFamily    = "System";
    private double  defaultFontSize      = 24.0;
    private boolean defaultBold          = false;
    private boolean defaultItalic        = false;
    private boolean defaultUnderline     = false;
    private String  defaultTextAlignment = "LEFT";
    
    // Active formatting during inline editing
    private String  activeFontFamily;
    private double  activeFontSize;
    private boolean activeBold, activeItalic, activeUnderline;
    private String  activeAlignment;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    private final Consumer<StrokeData> onStrokeDrawn;
    private Consumer<ShapeData> onShapeAdded;
    private Consumer<ShapeData> onShapeUpdated;
    private Consumer<String>    onShapeRemoved;

    // Teacher-side only: fired continuously during freehand drag with current in-progress stroke.
    // Never called on the student side (set to null in student constructor).
    private Consumer<StrokeData> onStrokeProgress;

    // Throttle STROKE_PROGRESS to ~60fps so we do not fire a network message for every pixel.
    // nanoTime() is used instead of currentTimeMillis() because currentTimeMillis() has
    // ~10–15 ms resolution on Windows, which would effectively cap updates at ~33–67fps.
    // nanoTime() is a monotonic, high-resolution counter with nanosecond resolution
    // that is not subject to system-time adjustments.
    private long lastStrokeProgressNs = 0L;
    private static final long STROKE_PROGRESS_INTERVAL_NS = 16_000_000L; // 16 ms ≈ 60 fps

    // Index into currentPoints of the first point NOT yet sent in a STROKE_PROGRESS message.
    // Reset to 0 on mousePressed. Advanced to currentPoints.size() after each progress send.
    // This allows each STROKE_PROGRESS to carry only the NEW delta points, keeping the
    // payload constant-sized (≈1–3 points) regardless of how long the stroke has been
    // in progress — preventing quadratic serialization growth that causes lag after ~1s.
    private int lastProgressPointIndex = 0;

    // Throttle SHAPE_UPDATE-during-drag to ~60fps (same rationale as STROKE_PROGRESS above)
    private long lastShapeDragNs = 0L;
    private static final long SHAPE_DRAG_INTERVAL_NS = 16_000_000L;

    // ── Constructor ───────────────────────────────────────────────────────────
    public WhiteboardPane(boolean teacherMode, Consumer<StrokeData> onStrokeDrawn) {
        this.teacherMode   = teacherMode;
        this.onStrokeDrawn = onStrokeDrawn;

        whiteboardCanvas = new Canvas(800, 500);
        annotationCanvas = new Canvas(800, 500);
        wbGc  = whiteboardCanvas.getGraphicsContext2D();
        annGc = annotationCanvas.getGraphicsContext2D();

        progressOverlayCanvas = new Canvas(800, 500);
        progressGc = progressOverlayCanvas.getGraphicsContext2D();
        progressOverlayCanvas.setMouseTransparent(true); // never captures mouse events

        shapeOverlayPane = new Pane();
        shapeOverlayPane.setMinSize(800, 500);
        shapeOverlayPane.setPrefSize(800, 500);
        shapeOverlayPane.setMaxSize(800, 500);
        // FREEHAND mode: overlay is transparent so canvas receives events
        shapeOverlayPane.setMouseTransparent(true);

        getChildren().addAll(whiteboardCanvas, annotationCanvas, shapeOverlayPane, progressOverlayCanvas);
        setStyle("-fx-background-color: " + containerBgStyle + ";");
        setMinSize(800, 500);
        setPrefSize(800, 500);
        setMaxSize(800, 500);
        // Register the pivot-(0,0) Scale transform once; setZoom() only updates its x/y values.
        this.getTransforms().add(scaleTransform);

        redrawAll();

        if (teacherMode) {
            setupCanvasHandlers();
            setupOverlayHandlers();
        }
    }

    // ── Shape callback wiring (called from TeacherUI after construction) ──────
    public void setShapeCallbacks(Consumer<ShapeData> onAdded,
                                   Consumer<ShapeData> onUpdated,
                                   Consumer<String>    onRemoved) {
        this.onShapeAdded   = onAdded;
        this.onShapeUpdated = onUpdated;
        this.onShapeRemoved = onRemoved;
    }

    public void setStrokeProgressCallback(Consumer<StrokeData> callback) {
        this.onStrokeProgress = callback;
    }

    public void setOnSelectionChanged(Consumer<String> callback) {
        this.onSelectionChanged = callback;
    }

    // ── FREEHAND canvas mouse handlers ────────────────────────────────────────
    private void setupCanvasHandlers() {
        annotationCanvas.setOnMousePressed(e -> {
            boolean isFree = (drawMode == DrawMode.FREEHAND || drawMode == DrawMode.ERASER);
            if (!isFree || !e.isPrimaryButtonDown()) return;
            e.consume(); // prevent ScrollPane from capturing the drag
            double cw = getCanvasW(), ch = getCanvasH();
            if (cw == 0 || ch == 0) return;
            currentPoints.clear();
            lastProgressPointIndex = 0;  // reset delta index on every new stroke
            double px = e.getX(), py = e.getY();
            currentPoints.add(new double[]{px, py});   // absolute pixels
            lastX = px; lastY = py;
            GraphicsContext gc = activeGc();
            if (drawMode == DrawMode.ERASER) {
                if (annotationMode) {
                    gc.clearRect(px - strokeWidth, py - strokeWidth, strokeWidth * 2, strokeWidth * 2);
                } else {
                    gc.setStroke(canvasBgColor);
                    gc.setLineWidth(strokeWidth * 2);
                    gc.setLineCap(StrokeLineCap.ROUND); gc.setLineJoin(StrokeLineJoin.ROUND);
                    gc.beginPath(); gc.moveTo(px, py);
                }
            } else {
                gc.setStroke(currentColor);
                gc.setLineWidth(strokeWidth);
                gc.setLineCap(StrokeLineCap.ROUND); gc.setLineJoin(StrokeLineJoin.ROUND);
                gc.beginPath(); gc.moveTo(px, py);
            }
        });
        annotationCanvas.setOnMouseDragged(e -> {
            boolean isFree = (drawMode == DrawMode.FREEHAND || drawMode == DrawMode.ERASER);
            if (!isFree || !e.isPrimaryButtonDown()) return;
            e.consume(); // prevent ScrollPane from panning
            double cw = getCanvasW(), ch = getCanvasH();
            if (cw == 0 || ch == 0) return;
            double px = e.getX(), py = e.getY();
            currentPoints.add(new double[]{px, py});   // absolute pixels
            GraphicsContext gc = activeGc();
            if (drawMode == DrawMode.ERASER && annotationMode) {
                double steps = Math.max(Math.abs(px - lastX), Math.abs(py - lastY));
                for(int i=1; i<=steps; i++) {
                    double stepX = lastX + (px - lastX) * (i / steps);
                    double stepY = lastY + (py - lastY) * (i / steps);
                    gc.clearRect(stepX - strokeWidth, stepY - strokeWidth, strokeWidth * 2, strokeWidth * 2);
                }
            } else {
                gc.lineTo(px, py); gc.stroke(); gc.beginPath(); gc.moveTo(px, py);
            }
            lastX = px; lastY = py;
            // Fire STROKE_PROGRESS callback — throttled to ~60fps.
            // Sends only the NEW delta points since the last progress message so that
            // payload size stays constant (≈1–3 points) no matter how long the stroke is.
            if (onStrokeProgress != null) {
                long now = System.nanoTime();
                if (now - lastStrokeProgressNs >= STROKE_PROGRESS_INTERVAL_NS) {
                    lastStrokeProgressNs = now;
                    // Include the last already-sent point as index 0 of the delta so the
                    // student's drawOnGc() does moveTo(anchor) → lineTo(new points...).
                    // Without this one-point overlap every delta starts with a fresh moveTo()
                    // at the new position, leaving a visible gap and producing the dotted
                    // broken-stroke artifact. Cost: exactly 1 extra point per message.
                    int fromIndex = (lastProgressPointIndex > 0)
                            ? lastProgressPointIndex - 1   // one-point overlap for continuity
                            : 0;
                    List<double[]> delta = new ArrayList<>(
                            currentPoints.subList(fromIndex, currentPoints.size()));
                    lastProgressPointIndex = currentPoints.size();
                    if (delta.size() >= 2) {   // need at least anchor + 1 new point to draw
                        StrokeData progressStroke = new StrokeData(
                            delta,
                            drawMode == DrawMode.ERASER ? "#00000000" : toHex(currentColor),
                            strokeWidth,
                            annotationMode
                        );
                        onStrokeProgress.accept(progressStroke);
                    }
                }
            }
        });
        annotationCanvas.setOnMouseReleased(e -> {
            boolean isFree = (drawMode == DrawMode.FREEHAND || drawMode == DrawMode.ERASER);
            if (!isFree || currentPoints.isEmpty()) return;
            e.consume(); // prevent ScrollPane from capturing the event
            // Stroke width stored as absolute pixels; canvas size is always in sync
            // across the network via CANVAS_RESIZE, so absolute coords are safe.
            StrokeData stroke = new StrokeData(new ArrayList<>(currentPoints),
                    drawMode == DrawMode.ERASER ? "#00000000" : toHex(currentColor),
                    strokeWidth, annotationMode);
            recordStroke(stroke);
            if (onStrokeDrawn != null) onStrokeDrawn.accept(stroke);
            currentPoints.clear();
            activeGc().setGlobalBlendMode(javafx.scene.effect.BlendMode.SRC_OVER);
        });
    }

    // ── Shape overlay mouse handlers ──────────────────────────────────────────
    private void setupOverlayHandlers() {
        shapeOverlayPane.setOnMousePressed(e -> {
            if (!e.isPrimaryButtonDown()) return;
            e.consume(); // prevent ScrollPane from capturing the drag
            if (drawMode == DrawMode.SHAPE_TEXT) {
                if (editingTextId != null) commitEditing();
                textClickPending = true;
                textPressX = e.getX();
                textPressY = e.getY();
                return;
            }
            if (drawMode == DrawMode.SELECT) {
                clearHandles();
                selectedShapeId = null;
                if (onSelectionChanged != null) onSelectionChanged.accept(null);
            } else if (drawMode != DrawMode.FREEHAND && drawMode != DrawMode.ERASER) {
                shapeDragX = e.getX();
                shapeDragY = e.getY();
                startPreview(e.getX(), e.getY());

                // For non-TEXT shapes: create a zero-size shape immediately and broadcast SHAPE_ADD.
                ShapeData earlyShape = createShapeFromBounds(
                    shapeDragX, shapeDragY, shapeDragX, shapeDragY);
                if (earlyShape != null) {
                    shapeDataMap.put(earlyShape.getId(), earlyShape);
                    Group g = buildGroup(earlyShape);
                    shapeNodeMap.put(earlyShape.getId(), g);
                    shapeOverlayPane.getChildren().add(g);
                    currentDragShapeId = earlyShape.getId();
                    if (onShapeAdded != null) onShapeAdded.accept(earlyShape);
                }
            }
        });
        shapeOverlayPane.setOnMouseDragged(e -> {
            if (!e.isPrimaryButtonDown()) return;
            e.consume(); // prevent ScrollPane from panning
            if (drawMode != DrawMode.FREEHAND && drawMode != DrawMode.ERASER && drawMode != DrawMode.SELECT && drawMode != DrawMode.SHAPE_TEXT) {
                updatePreview(e.getX(), e.getY());

                if (currentDragShapeId != null) {
                    ShapeData sd = shapeDataMap.get(currentDragShapeId);
                    if (sd != null) {
                        updateShapeGeometry(sd, shapeDragX, shapeDragY, e.getX(), e.getY());
                        syncNodeFromData(sd);
                        long now = System.nanoTime();
                        if (now - lastShapeDragNs >= SHAPE_DRAG_INTERVAL_NS) {
                            lastShapeDragNs = now;
                            if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
                        }
                    }
                }
            }
        });
        shapeOverlayPane.setOnMouseReleased(e -> {
            e.consume(); // prevent ScrollPane from capturing the event
            if (drawMode == DrawMode.SHAPE_TEXT) {
                if (textClickPending) {
                    textClickPending = false;
                    double dx = e.getX() - textPressX;
                    double dy = e.getY() - textPressY;
                    if (Math.hypot(dx, dy) < 5) {
                        startInlineEditing(textPressX, textPressY, null);
                    }
                }
                return;
            }
            if (drawMode != DrawMode.FREEHAND && drawMode != DrawMode.ERASER && drawMode != DrawMode.SELECT) {
                finalizeShape(e.getX(), e.getY());
            }
        });
    }

    // ── Shape preview (dashed outline while dragging) ─────────────────────────
    private void startPreview(double x, double y) {
        if (previewNode != null) shapeOverlayPane.getChildren().remove(previewNode);
        Color c = currentColor;
        switch (drawMode) {
            case SHAPE_RECT: {
                Rectangle r = new Rectangle(x, y, 1, 1);
                r.setStroke(c); r.setFill(Color.TRANSPARENT); r.setStrokeWidth(strokeWidth);
                r.getStrokeDashArray().addAll(6.0, 3.0);
                previewNode = r; break;
            }
            case SHAPE_ELLIPSE: {
                Ellipse el = new Ellipse(x, y, 0.5, 0.5);
                el.setStroke(c); el.setFill(Color.TRANSPARENT); el.setStrokeWidth(strokeWidth);
                el.getStrokeDashArray().addAll(6.0, 3.0);
                previewNode = el; break;
            }
            case SHAPE_LINE: {
                Line ln = new Line(x, y, x, y);
                ln.setStroke(c); ln.setStrokeWidth(strokeWidth);
                ln.getStrokeDashArray().addAll(6.0, 3.0);
                previewNode = ln; break;
            }
            case SHAPE_ARROW: {
                Group grp = new Group();
                Line ln = new Line(x, y, x, y);
                ln.setStroke(c); ln.setStrokeWidth(strokeWidth);
                ln.getStrokeDashArray().addAll(6.0, 3.0);
                Polygon head = new Polygon();
                head.setFill(c); head.setStroke(c); head.setStrokeWidth(1);
                grp.getChildren().addAll(ln, head);
                previewNode = grp; break;
            }
            default: return;
        }
        shapeOverlayPane.getChildren().add(previewNode);
    }

    private void updatePreview(double x, double y) {
        if (previewNode == null) return;
        double x0 = Math.min(shapeDragX, x), y0 = Math.min(shapeDragY, y);
        double w  = Math.abs(x - shapeDragX),   h  = Math.abs(y - shapeDragY);
        if (previewNode instanceof Rectangle) {
            Rectangle r = (Rectangle) previewNode;
            r.setX(x0); r.setY(y0); r.setWidth(w); r.setHeight(h);
        } else if (previewNode instanceof Ellipse) {
            Ellipse el = (Ellipse) previewNode;
            el.setCenterX(x0 + w / 2); el.setCenterY(y0 + h / 2);
            el.setRadiusX(w / 2); el.setRadiusY(h / 2);
        } else if (previewNode instanceof Line) {
            Line ln = (Line) previewNode;
            ln.setEndX(x); ln.setEndY(y);
        } else if (previewNode instanceof Group) {
            Group grp = (Group) previewNode;
            Line ln = (Line) grp.getChildren().get(0);
            Polygon head = (Polygon) grp.getChildren().get(1);
            double size = 14 + strokeWidth * 1.5;
            double angle = Math.atan2(y - shapeDragY, x - shapeDragX);
            double lineEndX = x - (size * 0.5) * Math.cos(angle);
            double lineEndY = y - (size * 0.5) * Math.sin(angle);
            ln.setEndX(lineEndX); ln.setEndY(lineEndY);
            head.getPoints().setAll(computeArrowhead(shapeDragX, shapeDragY, x, y, size));
        }
    }

    private void finalizeShape(double x, double y) {
        // Remove preview outline
        if (previewNode != null) {
            shapeOverlayPane.getChildren().remove(previewNode);
            previewNode = null;
        }

        // For shapes tracked via currentDragShapeId (RECT, ELLIPSE, LINE, ARROW)
        if (currentDragShapeId != null) {
            ShapeData sd = shapeDataMap.get(currentDragShapeId);
            if (sd != null) {
                // Apply final geometry
                updateShapeGeometry(sd, shapeDragX, shapeDragY, x, y);

                // Min-size guard: silently remove tiny accidental shapes (click without drag)
                boolean tooSmall = (sd.getType() == ShapeType.RECT || sd.getType() == ShapeType.ELLIPSE)
                        && (sd.getW() < 5 || sd.getH() < 5);

                if (tooSmall) {
                    // Remove locally
                    Group g = shapeNodeMap.remove(sd.getId());
                    if (g != null) shapeOverlayPane.getChildren().remove(g);
                    shapeDataMap.remove(sd.getId());
                    // Tell students to remove the zero-size shape they received on mousePressed
                    if (onShapeRemoved != null) onShapeRemoved.accept(sd.getId());
                } else {
                    syncNodeFromData(sd);
                    // Record to history NOW (single undo action covers the entire draw gesture)
                    recordAction(new BoardAction(BoardAction.Type.SHAPE_ADD, null, sd.copy(), null));
                    // Broadcast final update (committed geometry)
                    if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
                }
            }
            currentDragShapeId = null;
        }
    }

    // ── Inline Text Editing ───────────────────────────────────────────────────
    private void startInlineEditing(double x, double y, String existingId) {
        editingTextId = existingId;
        editingTextArea = new TextArea();
        editingTextArea.getStyleClass().add("canvas-text-editor");
        editingTextArea.setWrapText(true);
        editingTextArea.setLayoutX(x);
        editingTextArea.setLayoutY(y);

        ShapeData sd = existingId != null ? shapeDataMap.get(existingId) : null;
        
        activeFontFamily = sd != null ? sd.getFontFamily() : defaultFontFamily;
        activeFontSize   = sd != null ? sd.getFontSize() : defaultFontSize;
        activeBold       = sd != null ? sd.isBold() : defaultBold;
        activeItalic     = sd != null ? sd.isItalic() : defaultItalic;
        activeUnderline  = sd != null ? sd.isUnderline() : defaultUnderline;
        activeAlignment  = sd != null ? sd.getTextAlignment() : defaultTextAlignment;
        
        updateEditingTextAreaStyle();

        if (sd != null) {
            editingTextArea.setText(sd.getText() != null ? sd.getText() : "");
            if (!sd.isAutoWidth()) {
                editingTextArea.setPrefWidth(sd.getW());
                editingTextArea.setPrefHeight(sd.getH());
            } else {
                measureTextWidth();
            }
            Group g = shapeNodeMap.get(existingId);
            if (g != null) g.setVisible(false);
        } else {
            editingTextArea.setText("");
            measureTextWidth();
        }

        editingTextArea.textProperty().addListener((obs, oldVal, newVal) -> {
            long now = System.nanoTime();
            if (now - lastMeasureNs >= 16_000_000L) {
                lastMeasureNs = now;
                measureTextWidth();
            } else {
                javafx.application.Platform.runLater(this::measureTextWidth);
            }
        });

        editingTextArea.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) commitEditing();
        });

        editingTextArea.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                commitEditing();
            }
        });

        shapeOverlayPane.getChildren().add(editingTextArea);
        editingTextArea.requestFocus();
        
        if (onSelectionChanged != null) onSelectionChanged.accept(existingId != null ? existingId : "NEW_TEXT");
    }

    private void updateEditingTextAreaStyle() {
        if (editingTextArea == null) return;
        editingTextArea.setStyle(String.format(
            "-fx-font-family: '%s'; -fx-font-size: %.1fpx; -fx-font-weight: %s; -fx-font-style: %s; -fx-text-alignment: %s;",
            activeFontFamily, activeFontSize, activeBold ? "bold" : "normal", activeItalic ? "italic" : "normal", activeAlignment.toLowerCase()
        ));
    }

    private void measureTextWidth() {
        if (editingTextArea == null) return;
        boolean isAutoWidth = true;
        if (editingTextId != null) {
            ShapeData sd = shapeDataMap.get(editingTextId);
            if (sd != null && !sd.isAutoWidth()) isAutoWidth = false;
        }
        if (!isAutoWidth) return;

        Text temp = new Text(editingTextArea.getText() + "W"); 
        temp.setFont(Font.font(
                activeFontFamily,
                activeBold ? FontWeight.BOLD : FontWeight.NORMAL,
                activeItalic ? FontPosture.ITALIC : FontPosture.REGULAR,
                activeFontSize
        ));
        double width = Math.max(20, temp.getLayoutBounds().getWidth());
        editingTextArea.setPrefWidth(width);
    }

    public void commitEditing() {
        if (editingTextArea == null) return;
        
        String content = editingTextArea.getText();
        String id = editingTextId;
        boolean isExisting = (id != null);
        
        ShapeData oldSdCopy = null;
        if (isExisting) {
            ShapeData sd = shapeDataMap.get(id);
            if (sd != null) {
                oldSdCopy = sd.copy();
                Group g = shapeNodeMap.get(id);
                if (g != null) g.setVisible(true); 
            }
        }
        
        shapeOverlayPane.getChildren().remove(editingTextArea);
        double finalW = editingTextArea.getWidth();
        double finalH = editingTextArea.getHeight();
        editingTextArea = null;
        editingTextId = null;

        if (content == null || content.isBlank()) {
            if (onSelectionChanged != null) onSelectionChanged.accept(selectedShapeId);
            return; 
        }

        ShapeData sd;
        if (isExisting && oldSdCopy != null) {
            sd = shapeDataMap.get(id);
            sd.setText(content);
            sd.setFontFamily(activeFontFamily);
            sd.setFontSize(activeFontSize);
            sd.setBold(activeBold);
            sd.setItalic(activeItalic);
            sd.setUnderline(activeUnderline);
            sd.setTextAlignment(activeAlignment);
            
            syncNodeFromData(sd);
            Group g = shapeNodeMap.get(id);
            if (g != null) {
                for (javafx.scene.Node n : g.getChildren()) {
                    if (n instanceof Text) {
                        sd.setH(((Text)n).getLayoutBounds().getHeight());
                        if (sd.isAutoWidth()) {
                             sd.setW(Math.max(20, ((Text)n).getLayoutBounds().getWidth()));
                        }
                    }
                }
            }
            syncNodeFromData(sd); 
            recordAction(new BoardAction(BoardAction.Type.SHAPE_UPDATE, null, sd.copy(), oldSdCopy));
            if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
            if (onSelectionChanged != null) onSelectionChanged.accept(selectedShapeId);
        } else {
            sd = new ShapeData(ShapeType.TEXT, textPressX, textPressY, finalW, finalH,
                    toHex(currentColor), strokeWidth, content, activeFontSize, annotationMode);
            sd.setFontFamily(activeFontFamily);
            sd.setBold(activeBold);
            sd.setItalic(activeItalic);
            sd.setUnderline(activeUnderline);
            sd.setTextAlignment(activeAlignment);
            sd.setAutoWidth(true);
            
            addShapeInternal(sd); 
            
            Group g = shapeNodeMap.get(sd.getId());
            if (g != null) {
                for (javafx.scene.Node n : g.getChildren()) {
                    if (n instanceof Text) {
                        sd.setW(Math.max(20, ((Text)n).getLayoutBounds().getWidth()));
                        sd.setH(((Text)n).getLayoutBounds().getHeight());
                    }
                }
            }
            syncNodeFromData(sd);
            if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
            if (onSelectionChanged != null) onSelectionChanged.accept(selectedShapeId); 
        }
    }

    public void applyTextFormatting(String fontFamily, double fontSize, boolean bold, boolean italic, boolean underline, String alignment, String colorHex) {
        if (editingTextArea != null) {
            activeFontFamily = fontFamily;
            activeFontSize = fontSize;
            activeBold = bold;
            activeItalic = italic;
            activeUnderline = underline;
            activeAlignment = alignment;
            currentColor = Color.web(colorHex);
            updateEditingTextAreaStyle();
        } else if (selectedShapeId != null) {
            ShapeData sd = shapeDataMap.get(selectedShapeId);
            if (sd != null && sd.getType() == ShapeType.TEXT) {
                ShapeData oldSd = sd.copy();
                sd.setFontFamily(fontFamily);
                sd.setFontSize(fontSize);
                sd.setBold(bold);
                sd.setItalic(italic);
                sd.setUnderline(underline);
                sd.setTextAlignment(alignment);
                sd.setStrokeHex(colorHex);
                
                syncNodeFromData(sd);
                Group g = shapeNodeMap.get(selectedShapeId);
                if (g != null) {
                    for (javafx.scene.Node n : g.getChildren()) {
                        if (n instanceof Text) {
                            if (sd.isAutoWidth()) {
                                sd.setW(Math.max(20, ((Text)n).getLayoutBounds().getWidth()));
                            }
                            sd.setH(((Text)n).getLayoutBounds().getHeight());
                        }
                    }
                }
                syncNodeFromData(sd);
                
                recordAction(new BoardAction(BoardAction.Type.SHAPE_UPDATE, null, sd.copy(), oldSd));
                if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
            }
        } else {
            defaultFontFamily = fontFamily;
            defaultFontSize = fontSize;
            defaultBold = bold;
            defaultItalic = italic;
            defaultUnderline = underline;
            defaultTextAlignment = alignment;
            currentColor = Color.web(colorHex);
        }
    }

    /**
     * Creates a ShapeData from the current draw mode and bounding coordinates.
     * Returns null for SHAPE_TEXT and any non-shape mode.
     * The shape is given a fresh UUID. Its geometry may be zero-size on mousePressed.
     */
    private ShapeData createShapeFromBounds(double x1, double y1, double x2, double y2) {
        String hex = toHex(currentColor);
        switch (drawMode) {
            case SHAPE_RECT:
                return new ShapeData(ShapeType.RECT,
                    Math.min(x1, x2), Math.min(y1, y2),
                    Math.abs(x2 - x1), Math.abs(y2 - y1),
                    hex, strokeWidth, null, 0, annotationMode);
            case SHAPE_ELLIPSE:
                return new ShapeData(ShapeType.ELLIPSE,
                    Math.min(x1, x2), Math.min(y1, y2),
                    Math.abs(x2 - x1), Math.abs(y2 - y1),
                    hex, strokeWidth, null, 0, annotationMode);
            case SHAPE_LINE:
                return new ShapeData(ShapeType.LINE,
                    x1, y1, x2 - x1, y2 - y1,
                    hex, strokeWidth, null, 0, annotationMode);
            case SHAPE_ARROW:
                return new ShapeData(ShapeType.ARROW,
                    x1, y1, x2 - x1, y2 - y1,
                    hex, strokeWidth, null, 0, annotationMode);
            default:
                return null;
        }
    }

    /**
     * Updates the mutable geometry fields of a ShapeData to reflect the current drag bounds.
     * Does not trigger any callbacks or history recording — pure data mutation.
     */
    private void updateShapeGeometry(ShapeData sd, double x1, double y1, double x2, double y2) {
        switch (sd.getType()) {
            case RECT:
            case ELLIPSE:
                sd.setX(Math.min(x1, x2));
                sd.setY(Math.min(y1, y2));
                sd.setW(Math.abs(x2 - x1));
                sd.setH(Math.abs(y2 - y1));
                break;
            case LINE:
            case ARROW:
                sd.setX(x1);
                sd.setY(y1);
                sd.setW(x2 - x1);
                sd.setH(y2 - y1);
                break;
            default:
                break;
        }
    }

    // ── Shape node construction ───────────────────────────────────────────────
    private void addShapeInternal(ShapeData sd) {
        recordAction(new BoardAction(BoardAction.Type.SHAPE_ADD, null, sd.copy(), null));
        shapeDataMap.put(sd.getId(), sd);
        Group g = buildGroup(sd);
        shapeNodeMap.put(sd.getId(), g);
        shapeOverlayPane.getChildren().add(g);
        if (onShapeAdded != null) onShapeAdded.accept(sd);
    }

    /** Builds a Group node from ShapeData. Optionally attaches interactive handlers. */
    private Group buildGroup(ShapeData sd) {
        Group g = new Group();
        Color c = Color.web(sd.getStrokeHex());
        switch (sd.getType()) {
            case RECT: {
                Rectangle r = new Rectangle(sd.getX(), sd.getY(), sd.getW(), sd.getH());
                r.setStroke(c); r.setFill(Color.TRANSPARENT); r.setStrokeWidth(sd.getStrokeWidth());
                g.getChildren().add(r);
                break;
            }
            case ELLIPSE: {
                Ellipse el = new Ellipse(
                        sd.getX() + sd.getW() / 2, sd.getY() + sd.getH() / 2,
                        sd.getW() / 2, sd.getH() / 2);
                el.setStroke(c); el.setFill(Color.TRANSPARENT); el.setStrokeWidth(sd.getStrokeWidth());
                g.getChildren().add(el);
                break;
            }
            case LINE: {
                Line ln = new Line(sd.getX(), sd.getY(),
                        sd.getX() + sd.getW(), sd.getY() + sd.getH());
                ln.setStroke(c); ln.setStrokeWidth(sd.getStrokeWidth());
                g.getChildren().add(ln);
                break;
            }
            case ARROW: {
                double ex = sd.getX() + sd.getW(), ey = sd.getY() + sd.getH();
                double size = 14 + sd.getStrokeWidth() * 1.5;
                double angle  = Math.atan2(ey - sd.getY(), ex - sd.getX());
                
                double lineEndX = ex - (size * 0.5) * Math.cos(angle);
                double lineEndY = ey - (size * 0.5) * Math.sin(angle);

                Line ln = new Line(sd.getX(), sd.getY(), lineEndX, lineEndY);
                ln.setStroke(c); ln.setStrokeWidth(sd.getStrokeWidth());
                ln.setStrokeLineCap(StrokeLineCap.BUTT);
                
                Polygon head = new Polygon();
                head.getPoints().addAll(computeArrowhead(sd.getX(), sd.getY(), ex, ey, size));
                head.setFill(c); head.setStroke(c); head.setStrokeWidth(1);
                g.getChildren().addAll(ln, head);
                break;
            }
            case TEXT: {
                Rectangle border = new Rectangle(sd.getX(), sd.getY(), sd.getW(), sd.getH());
                border.setStroke(c.deriveColor(0, 1, 1, 0.6));
                border.setFill(Color.TRANSPARENT);
                border.setStrokeWidth(1);
                border.getStrokeDashArray().addAll(4.0, 2.0);
                border.setVisible(selectedShapeId != null && selectedShapeId.equals(sd.getId()));

                Text txt = new Text(sd.getText() != null ? sd.getText() : "");
                txt.setFill(c);
                txt.setFont(Font.font(
                    sd.getFontFamily(), 
                    sd.isBold() ? FontWeight.BOLD : FontWeight.NORMAL,
                    sd.isItalic() ? FontPosture.ITALIC : FontPosture.REGULAR,
                    sd.getFontSize()
                ));
                txt.setUnderline(sd.isUnderline());
                txt.setTextAlignment(TextAlignment.valueOf(sd.getTextAlignment()));
                txt.setWrappingWidth(sd.getW());
                txt.setTextOrigin(VPos.TOP);
                txt.setX(sd.getX());
                txt.setY(sd.getY());
                
                g.getChildren().addAll(border, txt);
                break;
            }
        }
        if (teacherMode) wireGroupInteraction(g, sd.getId());
        return g;
    }

    /**
     * Updates the visual JavaFX nodes inside a group DIRECTLY from ShapeData,
     * avoiding the need to tear down and rebuild the Group during drags.
     */
    private void syncNodeFromData(ShapeData sd) {
        Group g = shapeNodeMap.get(sd.getId());
        if (g == null) return;
        switch (sd.getType()) {
            case RECT:
                for (javafx.scene.Node n : g.getChildren()) {
                    if (n instanceof Rectangle) {
                        Rectangle r = (Rectangle) n;
                        r.setX(sd.getX()); r.setY(sd.getY()); r.setWidth(sd.getW()); r.setHeight(sd.getH());
                        break;
                    }
                }
                break;
            case ELLIPSE:
                for (javafx.scene.Node n : g.getChildren()) {
                    if (n instanceof Ellipse) {
                        Ellipse el = (Ellipse) n;
                        el.setCenterX(sd.getX() + sd.getW() / 2);
                        el.setCenterY(sd.getY() + sd.getH() / 2);
                        el.setRadiusX(sd.getW() / 2); el.setRadiusY(sd.getH() / 2);
                        break;
                    }
                }
                break;
            case LINE:
                for (javafx.scene.Node n : g.getChildren()) {
                    if (n instanceof Line) {
                        Line ln = (Line) n;
                        ln.setStartX(sd.getX()); ln.setStartY(sd.getY());
                        ln.setEndX(sd.getX() + sd.getW()); ln.setEndY(sd.getY() + sd.getH());
                        break;
                    }
                }
                break;
            case ARROW: {
                double ex2 = sd.getX() + sd.getW(), ey2 = sd.getY() + sd.getH();
                double size = 14 + sd.getStrokeWidth() * 1.5;
                double angle  = Math.atan2(ey2 - sd.getY(), ex2 - sd.getX());
                
                double lineEndX = ex2 - (size * 0.5) * Math.cos(angle);
                double lineEndY = ey2 - (size * 0.5) * Math.sin(angle);

                for (javafx.scene.Node n : g.getChildren()) {
                    if (n instanceof Line) {
                        Line ln = (Line) n;
                        ln.setStartX(sd.getX()); ln.setStartY(sd.getY());
                        ln.setEndX(lineEndX); ln.setEndY(lineEndY);
                    } else if (n instanceof Polygon) {
                        Polygon head = (Polygon) n;
                        head.getPoints().setAll(computeArrowhead(sd.getX(), sd.getY(), ex2, ey2, size));
                    }
                }
                break;
            }
            case TEXT:
                for (javafx.scene.Node n : g.getChildren()) {
                    if (n instanceof Rectangle) {
                        Rectangle r = (Rectangle) n;
                        r.setX(sd.getX()); r.setY(sd.getY()); r.setWidth(sd.getW()); r.setHeight(sd.getH());
                    } else if (n instanceof Text) {
                        Text t = (Text) n;
                        t.setText(sd.getText() != null ? sd.getText() : "");
                        t.setX(sd.getX()); t.setY(sd.getY());
                        t.setFont(Font.font(
                            sd.getFontFamily(), 
                            sd.isBold() ? FontWeight.BOLD : FontWeight.NORMAL,
                            sd.isItalic() ? FontPosture.ITALIC : FontPosture.REGULAR,
                            sd.getFontSize()
                        ));
                        t.setUnderline(sd.isUnderline());
                        t.setTextAlignment(TextAlignment.valueOf(sd.getTextAlignment()));
                        t.setWrappingWidth(sd.getW());
                        t.setFill(Color.web(sd.getStrokeHex()));
                    }
                }
                break;
        }
    }

    /** Full rebuild — used only for network-received updates (no ongoing drag). */
    private void rebuildNode(ShapeData sd) {
        Group old = shapeNodeMap.remove(sd.getId());
        if (old != null) shapeOverlayPane.getChildren().remove(old);
        Group fresh = buildGroup(sd);
        shapeNodeMap.put(sd.getId(), fresh);
        shapeOverlayPane.getChildren().add(fresh);
        // Keep handles on top
        shapeOverlayPane.getChildren().removeAll(handles);
        shapeOverlayPane.getChildren().addAll(handles);
    }

    // ── Interactive move (teacher SELECT mode) ────────────────────────────────
    private void wireGroupInteraction(Group g, String id) {
        // Double-click: edit text content (TEXT shapes only, SELECT mode)
        // Or single click in SHAPE_TEXT mode
        g.setOnMousePressed(e -> {
            if (!e.isPrimaryButtonDown()) return;
            if (drawMode == DrawMode.SHAPE_TEXT) {
                ShapeData sd = shapeDataMap.get(id);
                if (sd != null && sd.getType() == ShapeType.TEXT) {
                    e.consume();
                    if (editingTextId != null) commitEditing();
                    startInlineEditing(sd.getX(), sd.getY(), id);
                }
                return;
            }
            if (drawMode != DrawMode.SELECT) return;
            e.consume();
            selectShape(id);
            selectAction = SelectAction.MOVING;
            sDragX = e.getSceneX(); sDragY = e.getSceneY();
            ShapeData sd = shapeDataMap.get(id);
            if (sd != null) { 
                origX = sd.getX(); origY = sd.getY();
                origSdCopy = sd.copy(); 
            }
        });
        
        g.setOnMouseClicked(e -> {
            if (drawMode != DrawMode.SELECT || e.getClickCount() != 2) return;
            ShapeData sd = shapeDataMap.get(id);
            if (sd == null || sd.getType() != ShapeType.TEXT) return;
            e.consume();
            if (editingTextId != null) commitEditing();
            startInlineEditing(sd.getX(), sd.getY(), id);
            clearHandles(); 
        });
    }

    // ── Resize handles ────────────────────────────────────────────────────────
    private void selectShape(String id) {
        selectedShapeId = id;
        clearHandles();
        ShapeData sd = shapeDataMap.get(id);
        if (sd == null) return;
        
        if (sd.getType() == ShapeType.TEXT) {
            Group g = shapeNodeMap.get(id);
            if (g != null && !g.getChildren().isEmpty()) {
                g.getChildren().get(0).setVisible(true);
            }
        }
        
        double[][] pts = handlePositions(sd);
        for (int i = 0; i < pts.length; i++) {
            handles.add(makeHandle(i, pts[i][0], pts[i][1]));
        }
        shapeOverlayPane.getChildren().addAll(handles);
        if (onSelectionChanged != null) onSelectionChanged.accept(id);
    }

    private void clearHandles() {
        if (selectedShapeId != null) {
            ShapeData sd = shapeDataMap.get(selectedShapeId);
            if (sd != null && sd.getType() == ShapeType.TEXT) {
                Group g = shapeNodeMap.get(selectedShapeId);
                if (g != null && !g.getChildren().isEmpty()) {
                    g.getChildren().get(0).setVisible(false);
                }
            }
        }
        shapeOverlayPane.getChildren().removeAll(handles);
        handles.clear();
    }

    private void updateHandles() {
        if (selectedShapeId == null) return;
        ShapeData sd = shapeDataMap.get(selectedShapeId);
        if (sd == null) return;
        double[][] pts = handlePositions(sd);
        for (int i = 0; i < handles.size() && i < pts.length; i++) {
            handles.get(i).setX(pts[i][0] - HANDLE_SZ / 2);
            handles.get(i).setY(pts[i][1] - HANDLE_SZ / 2);
        }
    }

    private double[][] handlePositions(ShapeData sd) {
        if (sd.getType() == ShapeType.LINE || sd.getType() == ShapeType.ARROW) {
            return new double[][]{
                {sd.getX(),           sd.getY()},
                {sd.getX() + sd.getW(), sd.getY() + sd.getH()}
            };
        }
        double x = sd.getX(), y = sd.getY(), w = sd.getW(), h = sd.getH();
        return new double[][]{{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
    }

    private Rectangle makeHandle(int idx, double cx, double cy) {
        Rectangle h = new Rectangle(cx - HANDLE_SZ / 2, cy - HANDLE_SZ / 2, HANDLE_SZ, HANDLE_SZ);
        h.setFill(Color.WHITE);
        h.setStroke(Color.DODGERBLUE);
        h.setStrokeWidth(1.5);
        // Cursor
        Cursor cursor;
        switch (idx) {
            case 0: cursor = Cursor.NW_RESIZE; break;
            case 1: cursor = Cursor.NE_RESIZE; break;
            case 2: cursor = Cursor.SE_RESIZE; break;
            case 3: cursor = Cursor.SW_RESIZE; break;
            default: cursor = Cursor.CROSSHAIR;
        }
        h.setCursor(cursor);

        h.setOnMousePressed(e -> {
            if (drawMode != DrawMode.SELECT) return;
            e.consume();
            selectAction = SelectAction.RESIZING;
            activeHandle = idx;
            sDragX = e.getSceneX(); sDragY = e.getSceneY();
            ShapeData sd = shapeDataMap.get(selectedShapeId);
            if (sd != null) {
                origX = sd.getX(); origY = sd.getY();
                origW = sd.getW(); origH = sd.getH();
                origSdCopy = sd.copy();
            }
        });
        h.setOnMouseDragged(e -> {
            if (selectAction != SelectAction.RESIZING) return;
            e.consume();
            double dx = (e.getSceneX() - sDragX) / zoomLevel;
            double dy = (e.getSceneY() - sDragY) / zoomLevel;
            ShapeData sd = shapeDataMap.get(selectedShapeId);
            if (sd == null) return;
            applyResize(sd, activeHandle, dx, dy);
            syncNodeFromData(sd);
            updateHandles();
            // Stream resize to students while dragging (throttled)
            long nowResize = System.nanoTime();
            if (nowResize - lastShapeDragNs >= SHAPE_DRAG_INTERVAL_NS) {
                lastShapeDragNs = nowResize;
                if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
            }
        });
        h.setOnMouseReleased(e -> {
            if (selectAction != SelectAction.RESIZING) return;
            e.consume();
            selectAction = SelectAction.NONE;
            ShapeData sd = shapeDataMap.get(selectedShapeId);
            if (sd != null && onShapeUpdated != null) {
                if (sd.getType() == ShapeType.TEXT && sd.isAutoWidth()) {
                    sd.setAutoWidth(false);
                }
                if (origSdCopy != null) recordAction(new BoardAction(BoardAction.Type.SHAPE_UPDATE, null, sd.copy(), origSdCopy));
                origSdCopy = null;
                onShapeUpdated.accept(sd);
            }
        });
        return h;
    }

    private void applyResize(ShapeData sd, int idx, double dx, double dy) {
        if (sd.getType() == ShapeType.LINE || sd.getType() == ShapeType.ARROW) {
            if (idx == 0) { sd.setX(origX + dx); sd.setY(origY + dy); sd.setW(origW - dx); sd.setH(origH - dy); }
            else           { sd.setW(origW + dx); sd.setH(origH + dy); }
            return;
        }
        switch (idx) {
            case 0: sd.setX(origX+dx); sd.setY(origY+dy); sd.setW(Math.max(10,origW-dx)); sd.setH(Math.max(10,origH-dy)); break;
            case 1: sd.setY(origY+dy); sd.setW(Math.max(10,origW+dx)); sd.setH(Math.max(10,origH-dy)); break;
            case 2: sd.setW(Math.max(10,origW+dx)); sd.setH(Math.max(10,origH+dy)); break;
            case 3: sd.setX(origX+dx); sd.setW(Math.max(10,origW-dx)); sd.setH(Math.max(10,origH+dy)); break;
        }
    }

    // ── Public shape API (student receives these via network) ─────────────────
    public void addShape(ShapeData sd) {
        recordAction(new BoardAction(BoardAction.Type.SHAPE_ADD, null, sd.copy(), null));
        shapeDataMap.put(sd.getId(), sd);
        Group g = buildGroup(sd);
        shapeNodeMap.put(sd.getId(), g);
        shapeOverlayPane.getChildren().add(g);
    }

    public void updateShape(ShapeData sd) {
        ShapeData oldSd = shapeDataMap.get(sd.getId());
        if (oldSd != null) {
            recordAction(new BoardAction(BoardAction.Type.SHAPE_UPDATE, null, sd.copy(), oldSd.copy()));
        }
        shapeDataMap.put(sd.getId(), sd);
        rebuildNode(sd); // full rebuild for network-received updates
    }

    public void removeShape(String id) {
        ShapeData oldSd = shapeDataMap.get(id);
        if (oldSd != null) {
            recordAction(new BoardAction(BoardAction.Type.SHAPE_REMOVE, null, oldSd.copy(), null));
        }
        Group g = shapeNodeMap.remove(id);
        if (g != null) shapeOverlayPane.getChildren().remove(g);
        shapeDataMap.remove(id);
    }

    /** Teacher clicked "Delete Shape" button. */
    public void deleteSelectedShape() {
        if (selectedShapeId == null) return;
        String id = selectedShapeId;
        clearHandles();
        selectedShapeId = null;
        removeShape(id);
        if (onShapeRemoved != null) onShapeRemoved.accept(id);
        if (onSelectionChanged != null) onSelectionChanged.accept(null);
    }

    /** Returns a full snapshot of current whiteboard state for late-joining students. */
    public FullState getFullState() {
        // Collect only the strokes that are still "live" (not undone)
        List<StrokeData> strokes = new ArrayList<>();
        for (BoardAction a : history) {
            if (a.type == BoardAction.Type.STROKE && a.stroke != null) {
                strokes.add(a.stroke);
            }
        }
        List<ShapeData> shapes = new ArrayList<>(shapeDataMap.values());
        return new FullState(getCanvasW(), getCanvasH(), strokes, shapes);
    }

    /** Read-only view of all currently live shapes on this whiteboard. Used by TeacherUI for PPT export. */
    public java.util.Map<String, ShapeData> getShapeDataMap() {
        return java.util.Collections.unmodifiableMap(shapeDataMap);
    }

    /** Replays a FullState snapshot — used only on the student side after receiving FULL_STATE. */
    public void applyFullState(FullState state) {
        clearStrokeProgress();
        // Resize canvas first
        setCanvasSize(state.canvasW, state.canvasH);
        // Clear everything
        if (isTransparentBackground) {
            wbGc.clearRect(0, 0, state.canvasW, state.canvasH);
        } else {
            wbGc.setFill(canvasBgColor);
            wbGc.fillRect(0, 0, state.canvasW, state.canvasH);
        }
        annGc.clearRect(0, 0, state.canvasW, state.canvasH);
        annGc.beginPath();
        List<String> existing = new ArrayList<>(shapeDataMap.keySet());
        existing.forEach(this::silentRemoveShape);
        history.clear(); redoStack.clear();
        // Replay strokes
        for (StrokeData s : state.strokes) {
            drawStrokeOnly(s);
            history.addLast(new BoardAction(BoardAction.Type.STROKE, s, null, null));
        }
        // Replay shapes
        for (ShapeData sd : state.shapes) {
            silentAddShape(sd);
            history.addLast(new BoardAction(BoardAction.Type.SHAPE_ADD, null, sd.copy(), null));
        }
    }

    // \u2500\u2500 Mode switch \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
    public void setDrawMode(DrawMode mode) {
        if (editingTextId != null && (mode != DrawMode.SHAPE_TEXT && mode != DrawMode.SELECT)) {
            commitEditing();
        }
        this.drawMode = mode;
        boolean shapeOrSelect = (mode != DrawMode.FREEHAND && mode != DrawMode.ERASER);
        shapeOverlayPane.setMouseTransparent(!shapeOrSelect || !teacherMode);
        if (!shapeOrSelect) {
            clearHandles();
            selectedShapeId = null;
            if (onSelectionChanged != null) onSelectionChanged.accept(null);
        }
    }

    // ── History & Undo/Redo API ───────────────────────────────────────────────
    /**
     * Records an action into the undo history.
     * NO-OP on non-teacher boards (student-side WhiteboardPane instances have
     * teacherMode=false). Student boards must never build their own local history
     * because the teacher drives all undo/redo and sends the authoritative
     * post-operation FullState. Building student-local history would cause the
     * student's undo() calls to diverge from the teacher's state.
     */
    public void recordAction(BoardAction action) {
        if (!teacherMode) return;   // ← student boards never record history
        if (isUndoRedo) return;
        history.addLast(action);
        if (history.size() > 100) history.removeFirst();
        redoStack.clear();
    }

    public void recordStroke(StrokeData stroke) {
        recordAction(new BoardAction(BoardAction.Type.STROKE, stroke, null, null));
    }

    public void applyStroke(StrokeData stroke) {
        clearStrokeProgress(); // swap out the in-progress overlay before committing to canvas
        recordStroke(stroke);
        drawStrokeOnly(stroke);
    }

    private void drawOnGc(GraphicsContext gc, StrokeData stroke) {
        List<double[]> pts = stroke.getPoints();
        if (pts.isEmpty()) return;
        double sw = stroke.getStrokeWidth();   // absolute pixels — no canvas scaling
        boolean isEraser = "#00000000".equals(stroke.getColorHex());
        if (isEraser) {
            for (double[] pt : pts) {
                gc.clearRect(pt[0] - sw, pt[1] - sw, sw * 2, sw * 2);
            }
            return;
        }
        gc.setStroke(Color.web(stroke.getColorHex()));
        gc.setLineWidth(sw);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.beginPath();
        gc.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) {
            gc.lineTo(pts.get(i)[0], pts.get(i)[1]);
        }
        gc.stroke();
    }

    /**
     * Renders an in-progress stroke delta from the teacher onto the temporary overlay canvas.
     * Draws ONLY the new delta segment — does NOT clear the overlay first. The overlay
     * accumulates segments until clearStrokeProgress() is called (on final stroke commit).
     * This makes per-frame render cost O(delta points) instead of O(all points), eliminating
     * the quadratic redraw growth that caused student-side lag during long strokes.
     * Called only on the student side.
     */
    public void applyStrokeProgress(StrokeData delta) {
        drawOnGc(progressGc, delta);
    }

    /**
     * Clears the in-progress stroke overlay. Called before committing the final stroke,
     * and also on CLEAR / FULL_STATE to prevent ghost strokes.
     */
    public void clearStrokeProgress() {
        progressGc.clearRect(0, 0, progressOverlayCanvas.getWidth(), progressOverlayCanvas.getHeight());
    }

    private void drawStrokeOnly(StrokeData stroke) {
        GraphicsContext gc = stroke.isAnnotation() ? annGc : wbGc;
        boolean isEraser = "#00000000".equals(stroke.getColorHex());
        List<double[]> pts = stroke.getPoints();
        if (pts.isEmpty()) return;
        double sw = stroke.getStrokeWidth();   // absolute pixels — no canvas scaling

        if (isEraser && stroke.isAnnotation()) {
            gc.clearRect(pts.get(0)[0] - sw, pts.get(0)[1] - sw, sw * 2, sw * 2);
            for (int i = 1; i < pts.size(); i++) {
                double lastXp = pts.get(i-1)[0];
                double lastYp = pts.get(i-1)[1];
                double px = pts.get(i)[0];
                double py = pts.get(i)[1];
                double steps = Math.max(Math.abs(px - lastXp), Math.abs(py - lastYp));
                for(int j=1; j<=steps; j++) {
                    double stepX = lastXp + (px - lastXp) * (j / steps);
                    double stepY = lastYp + (py - lastYp) * (j / steps);
                    gc.clearRect(stepX - sw, stepY - sw, sw * 2, sw * 2);
                }
            }
            return;
        }

        if (isEraser && !stroke.isAnnotation()) {
            gc.setStroke(canvasBgColor);
            gc.setLineWidth(sw * 2);
        } else {
            gc.setStroke(Color.web(stroke.getColorHex()));
            gc.setLineWidth(sw);
        }

        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);
        gc.beginPath();
        gc.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) gc.lineTo(pts.get(i)[0], pts.get(i)[1]);
        gc.stroke();
    }

    public void clearWhiteboard() {
        clearStrokeProgress();
        if (isTransparentBackground) {
            wbGc.clearRect(0, 0, getCanvasW(), getCanvasH());
        } else {
            wbGc.setFill(canvasBgColor);
            wbGc.fillRect(0, 0, getCanvasW(), getCanvasH());
        }
        history.removeIf(a -> !a.isAnnotation());
        redoStack.removeIf(a -> !a.isAnnotation());
        
        List<String> toRemove = shapeDataMap.values().stream()
                .filter(s -> !s.isAnnotation()).map(ShapeData::getId).collect(Collectors.toList());
        toRemove.forEach(this::silentRemoveShape);
    }

    public void clearAnnotations() {
        clearStrokeProgress();
        annGc.clearRect(0, 0, getCanvasW(), getCanvasH());
        annGc.beginPath();
        history.removeIf(BoardAction::isAnnotation);
        redoStack.removeIf(BoardAction::isAnnotation);
        
        List<String> toRemove = shapeDataMap.values().stream()
                .filter(ShapeData::isAnnotation).map(ShapeData::getId).collect(Collectors.toList());
        toRemove.forEach(this::silentRemoveShape);
    }

    /**
     * Performs one undo on the teacher's board and returns the resulting FullState
     * for broadcast to students. Returns null if history is empty (nothing to undo).
     * MUST only be called on teacher-side boards (teacherMode=true).
     */
    public FullState undo() {
        if (history.isEmpty()) return null;
        BoardAction action = history.removeLast();
        redoStack.addLast(action);
        // Cap: cannot exceed the history limit (you can't undo more than you recorded).
        if (redoStack.size() > 100) redoStack.removeFirst();
        isUndoRedo = true;
        try {
            switch (action.type) {
                case STROKE:       redrawAll(); break;
                case SHAPE_ADD:    silentRemoveShape(action.shape.getId()); break;
                case SHAPE_UPDATE: silentUpdateShape(action.oldShape); break;
                case SHAPE_REMOVE: silentAddShape(action.shape); break;
            }
        } finally {
            isUndoRedo = false;
        }
        return getFullState();
    }

    /**
     * Performs one redo on the teacher's board and returns the resulting FullState
     * for broadcast to students. Returns null if redoStack is empty (nothing to redo).
     * MUST only be called on teacher-side boards (teacherMode=true).
     */
    public FullState redo() {
        if (redoStack.isEmpty()) return null;
        BoardAction action = redoStack.removeLast();
        history.addLast(action);
        if (history.size() > 100) history.removeFirst();
        isUndoRedo = true;
        try {
            switch (action.type) {
                case STROKE:       drawStrokeOnly(action.stroke); break;
                case SHAPE_ADD:    silentAddShape(action.shape); break;
                case SHAPE_UPDATE: silentUpdateShape(action.shape); break;
                case SHAPE_REMOVE: silentRemoveShape(action.shape.getId()); break;
            }
        } finally {
            isUndoRedo = false;
        }
        return getFullState();
    }

    private void silentRemoveShape(String id) {
        if (id.equals(selectedShapeId)) { clearHandles(); selectedShapeId = null; }
        Group g = shapeNodeMap.remove(id);
        if (g != null) shapeOverlayPane.getChildren().remove(g);
        shapeDataMap.remove(id);
    }

    private void silentUpdateShape(ShapeData sd) {
        shapeDataMap.put(sd.getId(), sd.copy());
        rebuildNode(sd);
        if (sd.getId().equals(selectedShapeId)) updateHandles();
    }

    private void silentAddShape(ShapeData sd) {
        ShapeData copy = sd.copy();
        shapeDataMap.put(copy.getId(), copy);
        Group g = buildGroup(copy);
        shapeNodeMap.put(copy.getId(), g);
        shapeOverlayPane.getChildren().add(g);
    }

    private void redrawAll() {
        if (getCanvasW() == 0 || getCanvasH() == 0) return;
        if (isTransparentBackground) {
            wbGc.clearRect(0, 0, getCanvasW(), getCanvasH());
        } else {
            wbGc.setFill(canvasBgColor);
            wbGc.fillRect(0, 0, getCanvasW(), getCanvasH());
        }
        annGc.clearRect(0, 0, getCanvasW(), getCanvasH());
        annGc.beginPath();
        for (BoardAction a : history) {
            if (a.type == BoardAction.Type.STROKE) drawStrokeOnly(a.stroke);
        }
    }

    public void setCanvasSize(double w, double h) {
        whiteboardCanvas.setWidth(w); whiteboardCanvas.setHeight(h);
        annotationCanvas.setWidth(w); annotationCanvas.setHeight(h);
        progressOverlayCanvas.setWidth(w);
        progressOverlayCanvas.setHeight(h);
        shapeOverlayPane.setMinSize(w, h);
        shapeOverlayPane.setPrefSize(w, h);
        shapeOverlayPane.setMaxSize(w, h);
        setMinSize(w, h); setPrefSize(w, h); setMaxSize(w, h);
        redrawAll();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    public double getCanvasW()                 { return whiteboardCanvas.getWidth(); }
    public double getCanvasH()                 { return whiteboardCanvas.getHeight(); }
    private GraphicsContext activeGc()         { return annotationMode ? annGc : wbGc; }
    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
    }

    /** Computes the 3 vertices of an arrowhead triangle at the end of line (x1,y1) → (x2,y2). */
    private static Double[] computeArrowhead(double x1, double y1, double x2, double y2, double size) {
        double angle  = Math.atan2(y2 - y1, x2 - x1);
        double spread = Math.toRadians(25);
        double lx = x2 - size * Math.cos(angle - spread);
        double ly = y2 - size * Math.sin(angle - spread);
        double rx = x2 - size * Math.cos(angle + spread);
        double ry = y2 - size * Math.sin(angle + spread);
        return new Double[]{x2, y2, lx, ly, rx, ry};
    }

    public void setAnnotationMode(boolean ann) { this.annotationMode = ann; }
    public void setCurrentColor(Color c)       { this.currentColor = c; }
    public void setStrokeWidth(double w)       { this.strokeWidth = w; }

    public double getZoom() { return zoomLevel; }
    public void setZoom(double level) {
        // Round to 1 decimal place to prevent floating-point drift (e.g. 0.999... or 0.7001...)
        level = Math.round(level * 10.0) / 10.0;
        if (level < 0.5) level = 0.5;
        if (level > 3.0) level = 3.0;
        this.zoomLevel = level;
        // Use the pivot-(0,0) Scale transform instead of setScaleX/Y.
        // setScaleX/Y pivots from node centre, pushing visual bounds into negative
        // coordinates in the parent Group and breaking the centering StackPane layout.
        scaleTransform.setX(level);
        scaleTransform.setY(level);
    }
    
    public void setTransparentBackground(boolean transparent) {
        this.isTransparentBackground = transparent;
        if (transparent) {
            setStyle("-fx-background-color: transparent;");
        } else {
            setStyle("-fx-background-color: " + containerBgStyle + ";");
        }
        redrawAll();
    }

    /**
     * Sets the canvas background color and outer container color for theme switching.
     * Dark theme: canvas=#1a2035, container=#0d1117
     * Light theme: canvas=#ffffff, container=#e0e0e0
     * Triggers a full redraw so existing strokes remain visible.
     */
    public void setCanvasBgColor(Color canvas, String containerHex) {
        this.canvasBgColor    = canvas;
        this.containerBgStyle = containerHex;
        if (!isTransparentBackground) {
            setStyle("-fx-background-color: " + containerHex + ";");
        }
        redrawAll();
    }
}
