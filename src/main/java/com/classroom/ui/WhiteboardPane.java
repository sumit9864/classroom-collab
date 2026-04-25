package com.classroom.ui;

import com.classroom.model.ShapeData;
import com.classroom.model.ShapeData.ShapeType;
import com.classroom.model.StrokeData;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

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

    // ── Callbacks ─────────────────────────────────────────────────────────────
    private final Consumer<StrokeData> onStrokeDrawn;
    private Consumer<ShapeData> onShapeAdded;
    private Consumer<ShapeData> onShapeUpdated;
    private Consumer<String>    onShapeRemoved;

    // Teacher-side only: fired continuously during freehand drag with current in-progress stroke.
    // Never called on the student side (set to null in student constructor).
    private Consumer<StrokeData> onStrokeProgress;

    // Throttle STROKE_PROGRESS to ~60fps so we do not fire a network message for every pixel
    private long lastStrokeProgressMs = 0L;
    private static final long STROKE_PROGRESS_INTERVAL_MS = 16L; // ~60fps

    // Throttle SHAPE_UPDATE-during-drag to ~60fps
    private long lastShapeDragMs = 0L;
    private static final long SHAPE_DRAG_INTERVAL_MS = 16L;

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

    // ── FREEHAND canvas mouse handlers ────────────────────────────────────────
    private void setupCanvasHandlers() {
        annotationCanvas.setOnMousePressed(e -> {
            boolean isFree = (drawMode == DrawMode.FREEHAND || drawMode == DrawMode.ERASER);
            if (!isFree || !e.isPrimaryButtonDown()) return;
            double cw = getCanvasW(), ch = getCanvasH();
            if (cw == 0 || ch == 0) return;
            currentPoints.clear();
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
            // Fire STROKE_PROGRESS callback — throttled to STROKE_PROGRESS_INTERVAL_MS
            if (onStrokeProgress != null) {
                long now = System.currentTimeMillis();
                if (now - lastStrokeProgressMs >= STROKE_PROGRESS_INTERVAL_MS) {
                    lastStrokeProgressMs = now;
                    StrokeData progressStroke = new StrokeData(
                        new ArrayList<>(currentPoints),
                        drawMode == DrawMode.ERASER ? "#00000000" : toHex(currentColor),
                        strokeWidth,   // absolute pixels — canvas size is sync'd network-wide
                        annotationMode
                    );
                    onStrokeProgress.accept(progressStroke);
                }
            }
        });
        annotationCanvas.setOnMouseReleased(e -> {
            boolean isFree = (drawMode == DrawMode.FREEHAND || drawMode == DrawMode.ERASER);
            if (!isFree || currentPoints.isEmpty()) return;
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
            if (drawMode == DrawMode.SELECT) {
                clearHandles();
                selectedShapeId = null;
            } else if (drawMode != DrawMode.FREEHAND && drawMode != DrawMode.ERASER) {
                shapeDragX = e.getX();
                shapeDragY = e.getY();
                startPreview(e.getX(), e.getY());

                // For non-TEXT shapes: create a zero-size shape immediately and broadcast SHAPE_ADD.
                // This lets students see the shape appear and stretch in real time as the teacher drags.
                if (drawMode != DrawMode.SHAPE_TEXT) {
                    ShapeData earlyShape = createShapeFromBounds(
                        shapeDragX, shapeDragY, shapeDragX, shapeDragY);
                    if (earlyShape != null) {
                        // Add to internal maps without recording history yet.
                        // History is recorded in finalizeShape() so undo still works as one atomic action.
                        shapeDataMap.put(earlyShape.getId(), earlyShape);
                        Group g = buildGroup(earlyShape);
                        shapeNodeMap.put(earlyShape.getId(), g);
                        shapeOverlayPane.getChildren().add(g);
                        currentDragShapeId = earlyShape.getId();
                        // Broadcast SHAPE_ADD so students see the shape appear
                        if (onShapeAdded != null) onShapeAdded.accept(earlyShape);
                    }
                }
            }
        });
        shapeOverlayPane.setOnMouseDragged(e -> {
            if (!e.isPrimaryButtonDown()) return;
            if (drawMode != DrawMode.FREEHAND && drawMode != DrawMode.ERASER && drawMode != DrawMode.SELECT) {
                updatePreview(e.getX(), e.getY());

                // Update the tracked shape geometry and broadcast SHAPE_UPDATE (throttled)
                if (currentDragShapeId != null) {
                    ShapeData sd = shapeDataMap.get(currentDragShapeId);
                    if (sd != null) {
                        updateShapeGeometry(sd, shapeDragX, shapeDragY, e.getX(), e.getY());
                        syncNodeFromData(sd);
                        long now = System.currentTimeMillis();
                        if (now - lastShapeDragMs >= SHAPE_DRAG_INTERVAL_MS) {
                            lastShapeDragMs = now;
                            if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
                        }
                    }
                }
            }
        });
        shapeOverlayPane.setOnMouseReleased(e -> {
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
            case SHAPE_RECT: case SHAPE_TEXT: {
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

        if (drawMode == DrawMode.SHAPE_TEXT) {
            // TEXT shapes are not pre-created on press — show dialog and create now
            double x0 = Math.min(shapeDragX, x), y0 = Math.min(shapeDragY, y);
            double w  = Math.abs(x - shapeDragX),   h  = Math.abs(y - shapeDragY);
            TextInputDialog dlg = new TextInputDialog();
            dlg.setTitle("Text Box");
            dlg.setHeaderText("Enter text for the text box:");
            dlg.setContentText("Text:");
            Optional<String> res = dlg.showAndWait();
            if (!res.isPresent() || res.get().isBlank()) return;
            ShapeData sd = new ShapeData(ShapeType.TEXT, x0, y0,
                    Math.max(w, 80), Math.max(h, 28),
                    toHex(currentColor), strokeWidth, res.get(), 14.0, annotationMode);
            addShapeInternal(sd); // recordAction + broadcast SHAPE_ADD
            return;
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
                Text txt = new Text(sd.getX() + 4, sd.getY() + sd.getFontSize() + 4, sd.getText());
                txt.setFill(c);
                txt.setFont(Font.font(sd.getFontSize()));
                txt.setWrappingWidth(sd.getW() - 8);
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
                        t.setX(sd.getX() + 4); t.setY(sd.getY() + sd.getFontSize() + 4);
                        t.setWrappingWidth(sd.getW() - 8);
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
        // Single-click: select & start move
        g.setOnMousePressed(e -> {
            if (drawMode != DrawMode.SELECT || !e.isPrimaryButtonDown()) return;
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
        g.setOnMouseDragged(e -> {
            if (drawMode != DrawMode.SELECT || selectAction != SelectAction.MOVING) return;
            e.consume();
            double dx = (e.getSceneX() - sDragX) / zoomLevel;
            double dy = (e.getSceneY() - sDragY) / zoomLevel;
            ShapeData sd = shapeDataMap.get(id);
            if (sd == null) return;
            sd.setX(origX + dx); sd.setY(origY + dy);
            syncNodeFromData(sd);
            updateHandles();
            // Stream position to students while dragging (throttled)
            long nowDrag = System.currentTimeMillis();
            if (nowDrag - lastShapeDragMs >= SHAPE_DRAG_INTERVAL_MS) {
                lastShapeDragMs = nowDrag;
                if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
            }
        });
        g.setOnMouseReleased(e -> {
            if (drawMode != DrawMode.SELECT || selectAction != SelectAction.MOVING) return;
            e.consume();
            selectAction = SelectAction.NONE;
            ShapeData sd = shapeDataMap.get(id);
            if (sd != null && onShapeUpdated != null) {
                if (origSdCopy != null) recordAction(new BoardAction(BoardAction.Type.SHAPE_UPDATE, null, sd.copy(), origSdCopy));
                origSdCopy = null;
                onShapeUpdated.accept(sd);
            }
        });

        // Double-click: edit text content (TEXT shapes only, SELECT mode)
        g.setOnMouseClicked(e -> {
            if (drawMode != DrawMode.SELECT || e.getClickCount() != 2) return;
            ShapeData sd = shapeDataMap.get(id);
            if (sd == null || sd.getType() != ShapeType.TEXT) return;
            e.consume();
            ShapeData oldSdCopy = sd.copy();
            TextInputDialog dlg = new TextInputDialog(sd.getText() != null ? sd.getText() : "");
            dlg.setTitle("Edit Text Box");
            dlg.setHeaderText("Edit the text content:");
            dlg.setContentText("Text:");
            Optional<String> res = dlg.showAndWait();
            if (!res.isPresent() || res.get().isBlank()) return;
            sd.setText(res.get());
            syncNodeFromData(sd);
            recordAction(new BoardAction(BoardAction.Type.SHAPE_UPDATE, null, sd.copy(), oldSdCopy));
            if (onShapeUpdated != null) onShapeUpdated.accept(sd);
        });
    }

    // ── Resize handles ────────────────────────────────────────────────────────
    private void selectShape(String id) {
        selectedShapeId = id;
        clearHandles();
        ShapeData sd = shapeDataMap.get(id);
        if (sd == null) return;
        double[][] pts = handlePositions(sd);
        for (int i = 0; i < pts.length; i++) {
            handles.add(makeHandle(i, pts[i][0], pts[i][1]));
        }
        shapeOverlayPane.getChildren().addAll(handles);
    }

    private void clearHandles() {
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
            long nowResize = System.currentTimeMillis();
            if (nowResize - lastShapeDragMs >= SHAPE_DRAG_INTERVAL_MS) {
                lastShapeDragMs = nowResize;
                if (onShapeUpdated != null) onShapeUpdated.accept(sd.copy());
            }
        });
        h.setOnMouseReleased(e -> {
            if (selectAction != SelectAction.RESIZING) return;
            e.consume();
            selectAction = SelectAction.NONE;
            ShapeData sd = shapeDataMap.get(selectedShapeId);
            if (sd != null && onShapeUpdated != null) {
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
        this.drawMode = mode;
        boolean shapeOrSelect = (mode != DrawMode.FREEHAND && mode != DrawMode.ERASER);
        shapeOverlayPane.setMouseTransparent(!shapeOrSelect || !teacherMode);
        if (!shapeOrSelect) {
            clearHandles();
            selectedShapeId = null;
        }
    }

    // ── History & Undo/Redo API ───────────────────────────────────────────────
    public void recordAction(BoardAction action) {
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
     * Renders an in-progress stroke from the teacher onto the temporary overlay canvas.
     * Does NOT add anything to history. The overlay is cleared when the final stroke arrives.
     * Called only on the student side.
     */
    public void applyStrokeProgress(StrokeData stroke) {
        progressGc.clearRect(0, 0, progressOverlayCanvas.getWidth(), progressOverlayCanvas.getHeight());
        drawOnGc(progressGc, stroke);
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

    public void undo() {
        if (history.isEmpty()) return;
        BoardAction action = history.removeLast();
        redoStack.addLast(action);
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
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        BoardAction action = redoStack.removeLast();
        history.addLast(action);
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
    private double getCanvasW()                { return whiteboardCanvas.getWidth(); }
    private double getCanvasH()                { return whiteboardCanvas.getHeight(); }
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
        if (level < 0.2) level = 0.2;
        if (level > 5.0) level = 5.0;
        this.zoomLevel = level;
        this.setScaleX(level); this.setScaleY(level);
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
