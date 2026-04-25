# Phase 2 Architecture — Whiteboard & Annotation Module

## Context from Phase 1 (read Latest_Updates.md before proceeding)

Three deviations from Phase 1 that Phase 2 MUST respect:
1. `TeacherUI` uses `setServer()` two-step wiring — do not change this pattern
2. `StudentUI` uses `setClient()` two-step wiring — do not change this pattern
3. `ClientHandler.getOutputStream()` is used by `TeacherServer.broadcast()` — do not remove

---

## A. What Phase 2 Adds

### New Files
| File | Purpose |
|------|---------|
| `src/main/java/com/classroom/model/StrokeData.java` | Serializable stroke payload (points, color, width, layer flag) |
| `src/main/java/com/classroom/ui/WhiteboardPane.java` | Two-layer canvas widget used by both teacher (interactive) and student (read-only) |

### Modified Files
| File | Changes |
|------|---------|
| `model/MessageType.java` | Add 4 new enum constants |
| `ui/TeacherUI.java` | Replace CENTER placeholder with WhiteboardPane + add drawing toolbar |
| `ui/StudentUI.java` | Replace CENTER placeholder with WhiteboardPane; add 4 new cases to handleMessage() |

### Files NOT touched in Phase 2
`Main.java`, `LoginScreen.java`, `TeacherServer.java`, `ClientHandler.java`,
`StudentClient.java`, `NetworkUtil.java`, `Message.java`, `pom.xml`

---

## B. Message Flow

```
Teacher draws (mouse released)
  → WhiteboardPane.onStrokeDrawn callback
  → TeacherUI calls server.broadcast(new Message(WHITEBOARD_STROKE, strokeData, "Teacher"))
  → TeacherServer.broadcast() sends to all ClientHandler output streams
  → Each student's StudentClient listener thread reads the Message
  → Platform.runLater → StudentUI.handleMessage(msg)
  → whiteboardPane.applyStroke(strokeData)
  → Stroke rendered on student canvas
```

Teacher never sends through ClientHandler — that path is for student→server direction only.
`ClientHandler.handle()` requires NO changes in Phase 2.

---

## C. New MessageType Constants

Add to `MessageType.java` — append to the existing enum, do NOT remove existing constants:

```java
WHITEBOARD_STROKE,     // Teacher → All Students: StrokeData payload for whiteboard layer
WHITEBOARD_CLEAR,      // Teacher → All Students: null payload, clear whiteboard canvas
ANNOTATION_STROKE,     // Teacher → All Students: StrokeData payload for annotation layer
ANNOTATION_CLEAR       // Teacher → All Students: null payload, clear annotation canvas
```

Final MessageType enum must have all 10 constants:
AUTH_REQUEST, AUTH_SUCCESS, AUTH_FAIL, STUDENT_LIST_UPDATE, DISCONNECT, HEARTBEAT,
WHITEBOARD_STROKE, WHITEBOARD_CLEAR, ANNOTATION_STROKE, ANNOTATION_CLEAR

---

## D. Full File Specifications

### `model/StrokeData.java` — NEW FILE

```java
package com.classroom.model;

import java.io.Serializable;
import java.util.List;

public class StrokeData implements Serializable {
    private static final long serialVersionUID = 2L;

    // Normalized points: each double[] = { x/canvasWidth, y/canvasHeight } — range [0.0, 1.0]
    // Normalizing ensures correct rendering even if teacher and student window sizes differ
    private List<double[]> points;

    private String colorHex;      // hex color string, e.g. "#000000"
    private double strokeWidth;   // logical width in pixels (2.0, 4.0, 8.0)
    private boolean annotation;   // false = whiteboard layer, true = annotation layer

    // Constructor: StrokeData(List<double[]> points, String colorHex,
    //                         double strokeWidth, boolean annotation)
    //   Assign all fields directly.

    // Getters (no setters — immutable after construction):
    // getPoints()       → List<double[]>
    // getColorHex()     → String
    // getStrokeWidth()  → double
    // isAnnotation()    → boolean
}
```

---

### `ui/WhiteboardPane.java` — NEW FILE

```java
package com.classroom.ui;

import com.classroom.model.StrokeData;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class WhiteboardPane extends StackPane {

    // Constants
    private static final double CANVAS_W = 800.0;
    private static final double CANVAS_H = 500.0;

    // Two canvas layers
    private final Canvas whiteboardCanvas;   // bottom — white background
    private final Canvas annotationCanvas;   // top — transparent background

    private final GraphicsContext wbGc;
    private final GraphicsContext annGc;

    private final boolean teacherMode;       // true = interactive; false = read-only
    private boolean annotationMode = false;  // true = draw on annotation layer

    private Color currentColor = Color.BLACK;
    private double strokeWidth = 2.0;

    private List<double[]> currentPoints;    // raw pixel coords collected during drag
    private double lastX, lastY;             // previous point for lineTo continuity

    private final Consumer<StrokeData> onStrokeDrawn; // null when teacherMode==false
```

#### Constructor

```
WhiteboardPane(boolean teacherMode, Consumer<StrokeData> onStrokeDrawn)
  this.teacherMode = teacherMode;
  this.onStrokeDrawn = onStrokeDrawn;
  this.currentPoints = new ArrayList<>();

  whiteboardCanvas = new Canvas(CANVAS_W, CANVAS_H);
  annotationCanvas = new Canvas(CANVAS_W, CANVAS_H);

  wbGc = whiteboardCanvas.getGraphicsContext2D();
  annGc = annotationCanvas.getGraphicsContext2D();

  // Paint whiteboard background white
  wbGc.setFill(Color.WHITE);
  wbGc.fillRect(0, 0, CANVAS_W, CANVAS_H);

  // Annotation canvas has transparent background by default (no fill needed)

  // Stack: whiteboard first (bottom), annotation on top
  getChildren().addAll(whiteboardCanvas, annotationCanvas);
  setStyle("-fx-background-color: #e0e0e0;");  // grey surround if pane > canvas

  if (teacherMode) {
      setupMouseHandlers();
  }
```

#### setupMouseHandlers()
Only called when `teacherMode == true`. Attaches to `annotationCanvas` (top layer) so all
mouse events are captured regardless of active layer.

```
setOnMousePressed(e):
  currentPoints.clear();
  double px = e.getX(), py = e.getY();
  currentPoints.add(new double[]{px / CANVAS_W, py / CANVAS_H}); // normalize
  lastX = px; lastY = py;
  // begin local stroke for teacher's own visual feedback
  GraphicsContext gc = activeGc();
  gc.setStroke(currentColor);
  gc.setLineWidth(strokeWidth);
  gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
  gc.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
  gc.beginPath();
  gc.moveTo(px, py);

setOnMouseDragged(e):
  double px = e.getX(), py = e.getY();
  currentPoints.add(new double[]{px / CANVAS_W, py / CANVAS_H}); // normalize
  // Draw segment locally for immediate teacher feedback
  GraphicsContext gc = activeGc();
  gc.lineTo(px, py);
  gc.stroke();
  gc.beginPath();
  gc.moveTo(px, py);
  lastX = px; lastY = py;

setOnMouseReleased(e):
  if (!currentPoints.isEmpty() && onStrokeDrawn != null):
    String hex = toHex(currentColor);
    StrokeData stroke = new StrokeData(
        new ArrayList<>(currentPoints), hex, strokeWidth, annotationMode);
    onStrokeDrawn.accept(stroke);
  currentPoints.clear();
```

#### activeGc() — private helper
```
Returns wbGc if !annotationMode, else annGc
```

#### applyStroke(StrokeData stroke)
Called by StudentUI.handleMessage() — always on FX thread.
```
GraphicsContext gc = stroke.isAnnotation() ? annGc : wbGc;
gc.setStroke(Color.web(stroke.getColorHex()));
gc.setLineWidth(stroke.getStrokeWidth());
gc.setLineCap(StrokeLineCap.ROUND);
gc.setLineJoin(StrokeLineJoin.ROUND);
gc.beginPath();
List<double[]> pts = stroke.getPoints();
if (pts.isEmpty()) return;
// Denormalize: multiply by CANVAS_W / CANVAS_H
gc.moveTo(pts.get(0)[0] * CANVAS_W, pts.get(0)[1] * CANVAS_H);
for (int i = 1; i < pts.size(); i++):
  gc.lineTo(pts.get(i)[0] * CANVAS_W, pts.get(i)[1] * CANVAS_H);
gc.stroke();
```

#### clearWhiteboard()
```
wbGc.setFill(Color.WHITE);
wbGc.fillRect(0, 0, CANVAS_W, CANVAS_H);
```

#### clearAnnotations()
```
annGc.clearRect(0, 0, CANVAS_W, CANVAS_H);
```

#### setAnnotationMode(boolean ann)
```
this.annotationMode = ann;
```

#### setCurrentColor(Color c)
```
this.currentColor = c;
```

#### setStrokeWidth(double w)
```
this.strokeWidth = w;
```

#### toHex(Color c) — private static helper
```
Returns String.format("#%02X%02X%02X",
    (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255));
```

---

### `ui/TeacherUI.java` — MODIFICATIONS ONLY

**Field to add:**
```java
private WhiteboardPane whiteboardPane;
```

**In `show()` — replace the CENTER placeholder block:**

Remove these lines:
```java
Label centerLabel = new Label("Content Area — Phases 2–4");
centerLabel.setFont(Font.font("System", 14));
centerLabel.setStyle("-fx-text-fill: #aaa;");
StackPane center = new StackPane(centerLabel);
center.setStyle("-fx-background-color: #fafafa;");
```

Replace with:
```java
// WhiteboardPane — teacher mode with broadcast callback
whiteboardPane = new WhiteboardPane(true, stroke -> {
    if (server != null) {
        MessageType type = stroke.isAnnotation()
            ? MessageType.ANNOTATION_STROKE
            : MessageType.WHITEBOARD_STROKE;
        server.broadcast(new Message(type, stroke, "Teacher"));
    }
});
```

**Add a toolbar HBox — insert between topBar and center in root layout:**
```java
// Drawing toolbar
ColorPicker colorPicker = new ColorPicker(Color.BLACK);
colorPicker.setOnAction(e -> whiteboardPane.setCurrentColor(colorPicker.getValue()));

Slider widthSlider = new Slider(1, 12, 2);
widthSlider.setShowTickLabels(true);
widthSlider.setMajorTickUnit(4);
widthSlider.setPrefWidth(100);
widthSlider.valueProperty().addListener((obs, oldVal, newVal) ->
    whiteboardPane.setStrokeWidth(newVal.doubleValue()));

ToggleButton annotateToggle = new ToggleButton("Annotate");
annotateToggle.setOnAction(e ->
    whiteboardPane.setAnnotationMode(annotateToggle.isSelected()));

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

Label widthLabel = new Label("Width:");
HBox toolbar = new HBox(10,
    new Label("Color:"), colorPicker,
    widthLabel, widthSlider,
    annotateToggle, clearBoard, clearAnnotations);
toolbar.setAlignment(Pos.CENTER_LEFT);
toolbar.setPadding(new Insets(6, 10, 6, 10));
toolbar.setStyle("-fx-background-color: #ececec; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");
```

**Update root layout to include toolbar:**
```java
VBox topSection = new VBox(topBar, toolbar);
root.setTop(topSection);
root.setCenter(whiteboardPane);
```

**Add import:** `import com.classroom.model.MessageType;` and `import com.classroom.model.Message;` if not already present.

---

### `ui/StudentUI.java` — MODIFICATIONS ONLY

**Field to add:**
```java
private WhiteboardPane whiteboardPane;
```

**In `show()` — replace CENTER block:**

Remove:
```java
StackPane center = new StackPane(statusLabel);
center.setStyle("-fx-background-color: #fafafa;");
```

Replace with:
```java
whiteboardPane = new WhiteboardPane(false, null);

// Keep statusLabel visible as a small bottom bar
HBox statusBar = new HBox(statusLabel);
statusBar.setPadding(new Insets(4, 10, 4, 10));
statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 1 0 0 0;");
root.setBottom(statusBar);
```

Update center assignment:
```java
root.setCenter(whiteboardPane);
```

**In `handleMessage()` — add 4 new cases to the existing switch:**

Add before the `default:` case:
```java
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
```

**Add import:** `import com.classroom.model.StrokeData;`

---

## E. Step-by-Step Implementation Instructions

### Step 1 — Add new MessageType constants
**Edit:** `src/main/java/com/classroom/model/MessageType.java`  
Add `WHITEBOARD_STROKE, WHITEBOARD_CLEAR, ANNOTATION_STROKE, ANNOTATION_CLEAR` to the enum.  
**Verify:** `mvn clean compile` — BUILD SUCCESS.

---

### Step 2 — Create StrokeData
**Create:** `src/main/java/com/classroom/model/StrokeData.java`  
Use the full specification from Section D.  
**Verify:** `mvn clean compile` — BUILD SUCCESS.

---

### Step 3 — Create WhiteboardPane
**Create:** `src/main/java/com/classroom/ui/WhiteboardPane.java`  
Implement constructor, setupMouseHandlers(), applyStroke(), clearWhiteboard(),
clearAnnotations(), setAnnotationMode(), setCurrentColor(), setStrokeWidth(), toHex().  
**Verify:** `mvn clean compile` — BUILD SUCCESS.

---

### Step 4 — Update TeacherUI
**Edit:** `src/main/java/com/classroom/ui/TeacherUI.java`  
- Add `whiteboardPane` field  
- Replace CENTER placeholder with `WhiteboardPane(true, callback)` as specified  
- Add toolbar HBox with ColorPicker, Slider, ToggleButton, two clear buttons  
- Update root layout: `VBox(topBar, toolbar)` → `root.setTop(topSection)`  
- Add required imports  
**Verify:** `mvn javafx:run` — app launches, teacher window shows canvas + toolbar.

---

### Step 5 — Update StudentUI
**Edit:** `src/main/java/com/classroom/ui/StudentUI.java`  
- Add `whiteboardPane` field  
- Replace CENTER placeholder with `WhiteboardPane(false, null)`  
- Move `statusLabel` to a bottom `HBox` status bar  
- Add 4 new cases to `handleMessage()`  
- Add required imports  
**Verify:** `mvn javafx:run` — app launches, student window shows canvas.

---

### Step 6 — Integration Test
Run two instances on localhost:
- Instance 1: Host Session port 5000 → Teacher window with canvas + toolbar visible
- Instance 2: Join Session 127.0.0.1:5000 → Student window with canvas visible

**Test sequence:**
1. Teacher draws on the whiteboard — student must see the same strokes appear after teacher lifts mouse (mouse release = broadcast)
2. Teacher changes color to red, draws again — student sees red strokes
3. Teacher clicks "Clear Board" — student canvas clears to white
4. Teacher clicks "Annotate" toggle, draws — student sees annotation overlay
5. Teacher clicks "Clear Annotations" — student annotation clears; whiteboard strokes remain

**Expected latency on localhost:** < 100ms  
**Log:** Mark Step 6 ✅ in `Latest_Updates.md`. Update all sections of Latest_Updates.md.

---

## F. Error Prevention Checklist

| Risk | Rule |
|------|------|
| `StrokeData` not serializable | `implements Serializable` + `serialVersionUID = 2L` — confirm before compile |
| `List<double[]>` in StrokeData | `double[]` is serializable — no issue, but `ArrayList` must be used (not `Arrays.asList`) |
| Casting payload | `(StrokeData) msg.getPayload()` — wrap in try-catch ClassCastException in handleMessage if desired |
| Mouse handlers on wrong canvas | Attach handlers to `annotationCanvas` (top of StackPane) — it captures all events in both modes |
| Canvas content lost on resize | Canvases are fixed size (800×500). Do NOT bind to StackPane size in Phase 2. |
| Drawing on wrong layer | `activeGc()` returns `wbGc` or `annGc` based on `annotationMode` — verify toggle wires to `setAnnotationMode()` |
| FX thread violation | `applyStroke()`, `clearWhiteboard()`, `clearAnnotations()` called only from `handleMessage()` which runs on FX thread via `Platform.runLater` in `StudentClient` — no additional dispatch needed |
| Import conflicts | TeacherUI now imports `MessageType` and `Message` — check existing imports before adding |
| Normalized coords | Teacher normalizes on drag collection (divide by CANVAS_W/H). Student denormalizes on apply (multiply by CANVAS_W/H). Both use the same constants. |
| `Color.web()` for hex parsing | Use `Color.web(stroke.getColorHex())` in `applyStroke()` — handles "#RRGGBB" format correctly |
