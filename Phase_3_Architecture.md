# Phase 3 Architecture — PPT Sharing Module

---

## Context from Phase 2 (mandatory reading before any implementation)

Current state of the project that Phase 3 directly builds on:

- `MessageType` has **17 constants** — Phase 3 adds exactly one more (`PPT_SLIDE`)
- `TeacherServer` has a `stateSupplier` field (type `Supplier<Message>`) and `setStateSupplier()` — Phase 3 adds a parallel `pptStateSupplier` using the same pattern
- `TeacherUI` center is currently `ScrollPane(Group(whiteboardPane))` inside a `BorderPane` — Phase 3 replaces this with a `TabPane`
- `StudentUI` center is currently `ScrollPane(Group(whiteboardPane))` inside a `BorderPane` — Phase 3 replaces this with a `TabPane`
- Both UIs still use the two-step wiring pattern (`setServer()` / `setClient()`) — do NOT change this
- `pom.xml` already declares `poi-ooxml 5.3.0` — **no new dependencies needed**
- `javafx-swing` is NOT in the pom — do NOT use `SwingFXUtils`. Use JavaFX `Image(InputStream)` instead.

---

## A. Scope — What Phase 3 Adds

### New Files
| File | Purpose |
|------|---------|
| `src/main/java/com/classroom/model/SlideData.java` | Serializable payload: PNG byte[], slide index, total slides |
| `src/main/java/com/classroom/util/PptService.java` | POI XSLF loading + background rendering; stores rendered slides |

### Modified Files
| File | Change |
|------|--------|
| `pom.xml` | Add 3 `--add-opens` flags for `java.desktop` to javafx-maven-plugin — required for POI AWT rendering on Java 17 |
| `model/MessageType.java` | Append `PPT_SLIDE` — one new constant only |
| `server/TeacherServer.java` | Add `pptStateSupplier` field + `setPptStateSupplier()` + update `addClient()` to send PPT state to new students |
| `ui/TeacherUI.java` | Replace center `ScrollPane` with `TabPane`; add PPT tab with panel, file chooser, navigation; hide drawing toolbars when on PPT tab |
| `ui/StudentUI.java` | Replace center `ScrollPane` with `TabPane`; add PPT tab with `ImageView`; handle `PPT_SLIDE`; auto-switch to PPT tab on receipt |

### Files NOT Touched in Phase 3
`Main.java`, `LoginScreen.java`, `ClientHandler.java`, `StudentClient.java`,
`NetworkUtil.java`, `Message.java`, `StrokeData.java`, `ShapeData.java`, `WhiteboardPane.java`

---

## B. Message Flow

```
Teacher loads .pptx file
  → FileChooser returns File
  → PptService.loadAsync(file, onComplete callback)
      Background thread: XMLSlideShow → renders each slide → byte[][] renderedSlides
      Platform.runLater: onComplete called → enable navigation buttons → display slide 0
  → TeacherUI displays slide 0 in teacher's ImageView
  → server.broadcast(new Message(PPT_SLIDE, slideData0, "Teacher"))
  → server.setPptStateSupplier(() → new Message(PPT_SLIDE, pptService.getCurrentSlideData(), "Teacher"))

Teacher clicks "Next →"
  → pptService.nextSlide() → returns SlideData for next slide
  → Update teacher ImageView
  → server.broadcast(new Message(PPT_SLIDE, sliceData, "Teacher"))

Student receives PPT_SLIDE
  → StudentUI.handleMessage() (already on FX thread)
  → Decode SlideData.imageBytes → new Image(new ByteArrayInputStream(bytes))
  → Set on ImageView in PPT tab
  → tabPane.getSelectionModel().select(pptTab)  ← auto-switch to PPT view

New student joins mid-session (PPT is loaded)
  → TeacherServer.addClient():
      1. handler.send(whiteboard FULL_STATE)    ← existing
      2. handler.send(pptStateSupplier.get())  ← NEW: current slide
  → Student's handleMessage processes PPT_SLIDE before adding to broadcast list
```

---

## C. pom.xml Change

**In the `<configuration>` of `javafx-maven-plugin`, append 6 more `<option>` entries after the existing 4:**

```xml
<option>--add-opens</option>
<option>java.desktop/java.awt=ALL-UNNAMED</option>
<option>--add-opens</option>
<option>java.desktop/sun.awt=ALL-UNNAMED</option>
<option>--add-opens</option>
<option>java.desktop/sun.java2d=ALL-UNNAMED</option>
```

The full `<options>` block after this change:
```xml
<options>
  <option>--add-opens</option>
  <option>java.base/java.lang=ALL-UNNAMED</option>
  <option>--add-opens</option>
  <option>java.base/java.io=ALL-UNNAMED</option>
  <option>--add-opens</option>
  <option>java.desktop/java.awt=ALL-UNNAMED</option>
  <option>--add-opens</option>
  <option>java.desktop/sun.awt=ALL-UNNAMED</option>
  <option>--add-opens</option>
  <option>java.desktop/sun.java2d=ALL-UNNAMED</option>
</options>
```

**Why:** Apache POI's XSLF slide renderer internally accesses `sun.awt` and `sun.java2d` classes via reflection for font metrics and 2D rendering on Java 17. Without these opens, slides with text may throw `InaccessibleObjectException` at runtime.

---

## D. New MessageType Constant

Append to `MessageType.java` after the existing `FULL_STATE` entry:

```java
// Phase 3 — PPT Sharing
PPT_SLIDE   // Teacher → All Students: SlideData payload (PNG bytes + index + total)
```

MessageType will now have **18 constants** total.

---

## E. Full File Specifications

### `model/SlideData.java` — NEW FILE

```java
package com.classroom.model;

import java.io.Serializable;

public class SlideData implements Serializable {
    private static final long serialVersionUID = 4L;

    private final byte[] imageBytes;   // PNG-encoded rendered slide image
    private final int slideIndex;      // 0-based current slide index
    private final int totalSlides;     // total number of slides in the deck

    // Constructor: SlideData(byte[] imageBytes, int slideIndex, int totalSlides)
    //   Assign all fields directly.

    // Getters (no setters — immutable):
    // getImageBytes()  → byte[]
    // getSlideIndex()  → int   (0-based)
    // getTotalSlides() → int
}
```

---

### `util/PptService.java` — NEW FILE

```java
package com.classroom.util;

import com.classroom.model.SlideData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PptService {

    private static final int TARGET_WIDTH = 960;  // render width in pixels

    private byte[][] renderedSlides;   // one PNG byte[] per slide, null until loaded
    private int currentIndex  = 0;
    private int totalSlides   = 0;
    private boolean loaded    = false;

    // Single-threaded executor — all POI rendering runs here, never on FX thread
    private final ExecutorService renderExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ppt-render");
                t.setDaemon(true);
                return t;
            });
```

#### `loadAsync(File file, Runnable onSuccess, java.util.function.Consumer<String> onError)`

Submits work to `renderExecutor`. The entire POI load + render happens off the FX thread.

```
renderExecutor.submit(() -> {
    try (XMLSlideShow pptx = new XMLSlideShow(new FileInputStream(file))) {

        List<XSLFSlide> slides = pptx.getSlides();
        if (slides.isEmpty()) {
            javafx.application.Platform.runLater(()
                → onError.accept("The selected file contains no slides."));
            return;
        }

        Dimension pgSize = pptx.getPageSize();
        double scale = TARGET_WIDTH / (double) pgSize.width;
        int imgH = (int)(pgSize.height * scale);

        byte[][] rendered = new byte[slides.size()][];

        for (int i = 0; i < slides.size(); i++) {
            BufferedImage img = new BufferedImage(
                TARGET_WIDTH, imgH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                // Quality rendering hints
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                   RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                                   RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                   RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                                   RenderingHints.VALUE_FRACTIONALMETRICS_ON);

                // White background (POI does not guarantee background fill)
                g.setPaint(Color.WHITE);
                g.fillRect(0, 0, TARGET_WIDTH, imgH);

                // Scale transform and render
                AffineTransform at = AffineTransform.getScaleInstance(scale, scale);
                g.setTransform(at);
                slides.get(i).render(g);

            } finally {
                g.dispose();
            }

            // Encode to PNG bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            rendered[i] = baos.toByteArray();
        }

        // Commit results (accessed only after this point)
        this.renderedSlides = rendered;
        this.totalSlides    = slides.size();
        this.currentIndex   = 0;
        this.loaded         = true;

        javafx.application.Platform.runLater(onSuccess);

    } catch (IOException | Exception e) {
        String msg = "Failed to load PPTX: " + e.getMessage();
        javafx.application.Platform.runLater(() → onError.accept(msg));
    }
});
```

#### `getCurrentSlideData() → SlideData`
```
if (!loaded) return null;
return new SlideData(renderedSlides[currentIndex], currentIndex, totalSlides);
```

#### `nextSlide() → SlideData`
```
if (!loaded || currentIndex >= totalSlides - 1) return null;
currentIndex++;
return getCurrentSlideData();
```

#### `prevSlide() → SlideData`
```
if (!loaded || currentIndex <= 0) return null;
currentIndex--;
return getCurrentSlideData();
```

#### `isLoaded() → boolean`
```
return loaded;
```

#### `getCurrentIndex() → int`
```
return currentIndex;
```

#### `getTotalSlides() → int`
```
return totalSlides;
```

#### `shutdown()`
```
renderExecutor.shutdownNow();
```
> Call this when the teacher session ends.

---

### `server/TeacherServer.java` — MODIFICATIONS ONLY

**Add one field** (alongside the existing `stateSupplier` field):
```java
private Supplier<Message> pptStateSupplier;  // provides current PPT slide for late-joining students
```

**Add one method** (alongside the existing `setStateSupplier()`):
```java
public void setPptStateSupplier(Supplier<Message> pptStateSupplier) {
    this.pptStateSupplier = pptStateSupplier;
}
```

**Modify `addClient()`** — add the PPT send block AFTER the existing whiteboard state send and BEFORE `clients.add(handler)`:

```java
public void addClient(ClientHandler handler) {
    // 1. Send whiteboard full state to this student only (existing)
    if (stateSupplier != null) {
        try {
            Message stateMsg = stateSupplier.get();
            if (stateMsg != null) handler.send(stateMsg);
        } catch (Exception e) {
            System.err.println("[TeacherServer] Failed to send whiteboard state: " + e.getMessage());
        }
    }

    // 2. Send current PPT slide to this student only (NEW)
    if (pptStateSupplier != null) {
        try {
            Message pptMsg = pptStateSupplier.get();
            if (pptMsg != null) handler.send(pptMsg);
        } catch (Exception e) {
            System.err.println("[TeacherServer] Failed to send PPT state: " + e.getMessage());
        }
    }

    // 3. Add to broadcast list and notify UI (existing)
    clients.add(handler);
    broadcastStudentList();
    Platform.runLater(onClientListChanged);
}
```

> The Supplier import (`java.util.function.Supplier`) is already present from Phase 2.

---

### `ui/TeacherUI.java` — MODIFICATIONS ONLY

#### New fields to add:
```java
private PptService pptService;
private javafx.scene.image.ImageView pptImageView;
private Label slideCountLabel;
private Button prevSlideBtn;
private Button nextSlideBtn;
private HBox toolbar;        // make field so tab listener can show/hide it
private HBox shapeToolbar;   // make field so tab listener can show/hide it
```

> Note: `toolbar` and `shapeToolbar` are currently local variables in `show()`.
> Promote them to instance fields so the tab-switch listener can access them.
> The rest of `show()` construction is unchanged — only the declaration site changes
> from `HBox toolbar = ...` to `this.toolbar = ...` (and same for shapeToolbar).

#### New imports to add:
```java
import com.classroom.util.PptService;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import java.io.ByteArrayInputStream;
import java.io.File;
```

#### In `show()` — replace the center assignment block

**Remove** this exact existing block (the last lines of show() before the stage.show()):
```java
javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
ScrollPane scroller = new ScrollPane(canvasGroup);
scroller.setStyle("-fx-focus-color: transparent; ...");
```
and the line:
```java
root.setCenter(scroller);
```

**Replace with the following — build a TabPane with two tabs:**

```java
// ── Tab 1: Whiteboard ──────────────────────────────────────────────────
javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
ScrollPane wbScroller = new ScrollPane(canvasGroup);
wbScroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");
Tab whiteboardTab = new Tab("Whiteboard", wbScroller);
whiteboardTab.setClosable(false);

// ── Tab 2: PPT Sharing ─────────────────────────────────────────────────
// PPT controls bar
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

// PPT slide display
pptImageView = new ImageView();
pptImageView.setPreserveRatio(true);
pptImageView.setFitWidth(960);
pptImageView.setFitHeight(540);
pptImageView.setSmooth(true);

StackPane pptCenter = new StackPane(pptImageView);
pptCenter.setStyle("-fx-background-color: #1a1a1a;");
pptCenter.setAlignment(Pos.CENTER);

VBox pptPanel = new VBox(pptControls, pptCenter);
VBox.setVgrow(pptCenter, Priority.ALWAYS);

Tab pptTab = new Tab("PPT Sharing", pptPanel);
pptTab.setClosable(false);

// ── TabPane ────────────────────────────────────────────────────────────
TabPane tabPane = new TabPane(whiteboardTab, pptTab);
tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

// Show/hide drawing toolbars based on active tab
tabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
    boolean onWhiteboard = (selected == whiteboardTab);
    this.toolbar.setVisible(onWhiteboard);
    this.toolbar.setManaged(onWhiteboard);
    this.shapeToolbar.setVisible(onWhiteboard);
    this.shapeToolbar.setManaged(onWhiteboard);
});

root.setCenter(tabPane);
```

#### Wire the Load PPTX button action (add inside `show()`, after TabPane construction):

```java
// PptService initialization
pptService = new PptService();
if (server != null) {
    server.setPptStateSupplier(() ->
        pptService.isLoaded()
            ? new Message(MessageType.PPT_SLIDE, pptService.getCurrentSlideData(), "Teacher")
            : null);
}

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
```

#### Add two private helper methods to TeacherUI:

```java
// Displays a slide image locally and broadcasts it to all students.
private void displayAndBroadcastSlide(SlideData sd) {
    if (sd == null) return;
    Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
    pptImageView.setImage(fxImg);
    if (server != null) {
        server.broadcast(new Message(MessageType.PPT_SLIDE, sd, "Teacher"));
    }
}

// Enables/disables Prev and Next buttons based on current position.
private void updateNavButtons() {
    int idx   = pptService.getCurrentIndex();
    int total = pptService.getTotalSlides();
    prevSlideBtn.setDisable(idx <= 0);
    nextSlideBtn.setDisable(idx >= total - 1);
    slideCountLabel.setText((idx + 1) + " / " + total);
}
```

#### In `stop()` handler — add pptService.shutdown():

In the existing `stopButton.setOnAction` and `stage.setOnCloseRequest` handlers, add:
```java
if (pptService != null) pptService.shutdown();
```
(Add alongside the existing `server.stop()` call in both handlers.)

---

### `ui/StudentUI.java` — MODIFICATIONS ONLY

#### New fields to add:
```java
private javafx.scene.image.ImageView pptImageView;
private Tab pptTab;
private TabPane tabPane;
```

#### New imports to add:
```java
import com.classroom.model.SlideData;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import java.io.ByteArrayInputStream;
```

#### In `show()` — replace the center assignment block

**Remove** the existing center block:
```java
javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
ScrollPane scroller = new ScrollPane(canvasGroup);
scroller.setStyle("...");
```
and the line:
```java
root.setCenter(scroller);
```

**Replace with:**
```java
// ── Tab 1: Whiteboard ──────────────────────────────────────────────────
javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
ScrollPane wbScroller = new ScrollPane(canvasGroup);
wbScroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");
Tab whiteboardTab = new Tab("Whiteboard", wbScroller);
whiteboardTab.setClosable(false);

// ── Tab 2: PPT Slide ───────────────────────────────────────────────────
pptImageView = new ImageView();
pptImageView.setPreserveRatio(true);
pptImageView.setFitWidth(960);
pptImageView.setFitHeight(540);
pptImageView.setSmooth(true);

Label waitingLabel = new Label("Waiting for PPT slide from teacher…");
waitingLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 15;");

StackPane pptPanel = new StackPane();
pptPanel.setStyle("-fx-background-color: #1a1a1a;");
pptPanel.setAlignment(Pos.CENTER);
// Show waitingLabel until first slide arrives; then replace with image
pptPanel.getChildren().add(waitingLabel);

pptTab = new Tab("PPT Slide", pptPanel);
pptTab.setClosable(false);

// Store pptPanel reference for later image injection
// (use pptPanel as effectively final via a field assignment below)

tabPane = new TabPane(whiteboardTab, pptTab);
tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

root.setCenter(tabPane);
```

> Because `pptPanel` is used in `handleMessage()` later, declare it as an instance field (type `StackPane`) named `pptSlidePanel`, OR use the `pptImageView` field directly.
> **Simplest approach:** Add an instance field `private StackPane pptSlidePanel;` to StudentUI. In `show()`, assign `pptSlidePanel = pptPanel` before the TabPane line.

#### In `handleMessage()` — add `PPT_SLIDE` case before `default`:

```java
case PPT_SLIDE:
    SlideData sd = (SlideData) msg.getPayload();
    Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
    pptImageView.setImage(fxImg);
    // Replace waiting label with image on first slide received
    if (!pptSlidePanel.getChildren().contains(pptImageView)) {
        pptSlidePanel.getChildren().clear();
        pptSlidePanel.getChildren().add(pptImageView);
    }
    // Auto-switch student to PPT tab
    tabPane.getSelectionModel().select(pptTab);
    break;
```

---

## F. Step-by-Step Implementation Instructions

### Step 1 — pom.xml: add java.desktop opens
**Edit:** `pom.xml`
Add the 3 new `--add-opens` pairs to `javafx-maven-plugin` `<options>` block as specified in Section C.
**Verify:** `mvn clean compile` — BUILD SUCCESS (same as before, just JVM flags changed).

---

### Step 2 — Add PPT_SLIDE to MessageType
**Edit:** `src/main/java/com/classroom/model/MessageType.java`
Append `PPT_SLIDE` after `FULL_STATE` with a `// Phase 3` comment.
**Verify:** `mvn clean compile` — BUILD SUCCESS.

---

### Step 3 — Create SlideData
**Create:** `src/main/java/com/classroom/model/SlideData.java`
Use the full spec from Section E. Ensure `implements Serializable` and `serialVersionUID = 4L`.
**Verify:** `mvn clean compile` — BUILD SUCCESS.

---

### Step 4 — Create PptService
**Create:** `src/main/java/com/classroom/util/PptService.java`
Implement all methods from Section E. Double-check:
- `Graphics2D.dispose()` is called in `finally` block inside the loop
- `XMLSlideShow` is in a try-with-resources so it auto-closes
- All `Platform.runLater()` calls are used for the `onSuccess` and `onError` callbacks
- The render executor thread is a daemon thread
**Verify:** `mvn clean compile` — BUILD SUCCESS.

---

### Step 5 — Modify TeacherServer
**Edit:** `src/main/java/com/classroom/server/TeacherServer.java`
Add `pptStateSupplier` field, `setPptStateSupplier()` method, and the new block in `addClient()`.
Do NOT change any other method.
**Verify:** `mvn clean compile` — BUILD SUCCESS.

---

### Step 6 — Modify TeacherUI
**Edit:** `src/main/java/com/classroom/ui/TeacherUI.java`
- Promote `toolbar` and `shapeToolbar` from local variables to instance fields
- Add new instance fields: `pptService`, `pptImageView`, `slideCountLabel`, `prevSlideBtn`, `nextSlideBtn`
- Replace center ScrollPane with TabPane (two tabs) as specified
- Wire `loadPptBtn`, `prevSlideBtn`, `nextSlideBtn` actions
- Wire `pptStateSupplier` into server
- Add `displayAndBroadcastSlide()` and `updateNavButtons()` private methods
- Add `pptService.shutdown()` to both stop handlers
- Add all required imports
**Verify:** `mvn javafx:run` — Teacher window opens. Clicking "PPT Sharing" tab shows the PPT panel. Drawing toolbars hide on PPT tab and reappear on Whiteboard tab. "Load PPTX…" button opens a file chooser filtering *.pptx.

---

### Step 7 — Modify StudentUI
**Edit:** `src/main/java/com/classroom/ui/StudentUI.java`
- Add instance fields: `pptImageView`, `pptTab`, `tabPane`, `pptSlidePanel`
- Replace center ScrollPane with TabPane (two tabs) as specified
- Add `PPT_SLIDE` case to `handleMessage()`
- Add required imports
**Verify:** `mvn javafx:run` — Student window opens with two tabs. PPT Slide tab shows the waiting label.

---

### Step 8 — Integration Test
Run two instances on localhost. Use a real .pptx file (at least 3 slides).

**Test sequence:**
1. Host Session on port 5000 → Teacher window with Whiteboard + PPT tabs
2. Join Session → Student window with Whiteboard + PPT tabs (waiting label visible in PPT tab)
3. Teacher clicks "PPT Sharing" tab → Drawing toolbars hide
4. Teacher clicks "Load PPTX…" → selects file → slides render (brief pause for large files)
5. Slide 1/N appears in teacher's PPT view
6. Student automatically switches to PPT tab and sees slide 1
7. Teacher clicks "Next →" → slide 2 appears on teacher; student updates immediately
8. Teacher clicks "← Prev" → slide 1 returns on both sides
9. Prev button is disabled on slide 1; Next button is disabled on last slide
10. Disconnect student; reconnect → student receives current slide immediately via pptStateSupplier

**Log:** Mark Step 8 ✅ in `Latest_Updates.md`. Update all sections.

---

## G. Error Prevention Checklist

| Risk | Rule |
|------|------|
| `SwingFXUtils` not in classpath | Do NOT use `SwingFXUtils.toFXImage()`. Use `new Image(new ByteArrayInputStream(bytes))` only |
| POI rendering on FX thread | ALL `XMLSlideShow` + `slide.render()` calls MUST be inside `renderExecutor.submit()` — never on FX thread |
| `Graphics2D.dispose()` not called | Wrap the g.dispose() in a `finally` block inside the rendering loop — memory leak if skipped |
| `XMLSlideShow` not closed | Always use try-with-resources: `try (XMLSlideShow pptx = ...) { }` |
| White background not set | Call `g.setPaint(Color.WHITE); g.fillRect(...)` BEFORE `slide.render(g)` — POI does not guarantee background |
| AffineTransform before render | Set `g.setTransform(at)` BEFORE calling `slide.render(g)` |
| `pptStateSupplier.get()` returns null | TeacherServer checks `if (pptMsg != null)` before `handler.send()` — already specified |
| `toolbar`/`shapeToolbar` not instance fields | Both must be instance fields (not local) for the TabPane listener to reference them |
| `pptSlidePanel` not instance field | Must be an instance field so `handleMessage()` can add `pptImageView` to it |
| ByteArrayInputStream reuse | Create a `new ByteArrayInputStream(bytes)` each time — never reuse a read-exhausted stream |
| TabPane auto-switch thread | `tabPane.getSelectionModel().select()` must run on FX thread — it does because `handleMessage()` is always called via `Platform.runLater()` in `StudentClient` |
| `pptService` null during PPT state supplier | Supplier is set AFTER `pptService = new PptService()` — always initialise pptService first |
| `setPptStateSupplier()` called before `server` exists | In TeacherUI, check `if (server != null)` before calling `server.setPptStateSupplier(...)` |
| `loadPptBtn` stays disabled on error | The `onError` callback must call `loadPptBtn.setDisable(false)` — already specified |
| `pptService.shutdown()` missing | Must be called in BOTH the Stop button handler AND the `setOnCloseRequest` handler |
| Tab closing policy | Set `tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE)` on both teacher and student TabPanes — prevents accidental tab close |
| Prev/Next button state on first load | Call `updateNavButtons()` immediately after `loadAsync` onSuccess callback, not before |
| Empty PPTX file | `PptService.loadAsync` checks `slides.isEmpty()` and calls `onError` with a user-readable message |
| POI rendering on Java 17 | The 3 `--add-opens` flags in pom.xml (Step 1) must be applied before running |
