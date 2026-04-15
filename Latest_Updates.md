# Latest Updates

## Current Phase: 3 — PPT Sharing Module
## Date: 2026-04-15

---

## Files Created
| File | Purpose |
|------|---------|
| `pom.xml` | Maven build config with all dependencies |
| `src/main/java/com/classroom/Main.java` | JavaFX Application entry point |
| `src/main/java/com/classroom/model/MessageType.java` | Enum of all message types |
| `src/main/java/com/classroom/model/Message.java` | Serializable message object |
| `src/main/java/com/classroom/model/StrokeData.java` | Serializable freehand stroke payload (normalized points, color, width, layer flag) |
| `src/main/java/com/classroom/model/ShapeData.java` | Serializable shape model — types: RECT, ELLIPSE, LINE, ARROW, TEXT; mutable geometry for move/resize |
| `src/main/java/com/classroom/model/SlideData.java` | Serializable PPT slide payload — PNG byte[], 0-based slideIndex, totalSlides |
| `src/main/java/com/classroom/util/NetworkUtil.java` | Socket read/write helpers |
| `src/main/java/com/classroom/util/PptService.java` | POI XSLF loading + background rendering on daemon thread; stores rendered slide byte arrays |
| `src/main/java/com/classroom/server/TeacherServer.java` | TCP ServerSocket, manages client threads |
| `src/main/java/com/classroom/server/ClientHandler.java` | One thread per connected student |
| `src/main/java/com/classroom/client/StudentClient.java` | TCP socket connection to teacher |
| `src/main/java/com/classroom/ui/LoginScreen.java` | Host/join screen for teacher and student |
| `src/main/java/com/classroom/ui/WhiteboardPane.java` | Three-layer canvas widget (whiteboard canvas, annotation canvas, shape overlay pane) used by both teacher (interactive) and student (read-only) |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Teacher's main window with student list, drawing toolbars, whiteboard, PPT tab |
| `src/main/java/com/classroom/ui/StudentUI.java` | Student's main window with read-only whiteboard tab and PPT slide tab |
| `src/test/java/com/classroom/NetworkUtilTest.java` | JUnit 5 round-trip and null-on-EOF tests |

---

## Files Modified
| File | Reason for Change |
|------|-------------------|
| `Latest_Updates.md` | Updated after every step per instructions |
| `src/test/java/com/classroom/NetworkUtilTest.java` | Phase 1 fix: removed dead PipedStream code, suppressed intentional unused-stream variables |
| `src/main/java/com/classroom/model/MessageType.java` | Phase 2: added 11 new constants; Phase 3: added PPT_SLIDE (18 constants total) |
| `src/main/java/com/classroom/server/TeacherServer.java` | Phase 2: stateSupplier + setStateSupplier() + FULL_STATE in addClient(); Phase 3: pptStateSupplier + setPptStateSupplier() + PPT send block in addClient() |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Phase 2: WhiteboardPane, two toolbars, scroll, shape callbacks; Phase 3: toolbar/shapeToolbar promoted to instance fields; PPT fields added; center replaced with TabPane (Whiteboard + PPT Sharing); file chooser; nav buttons; pptStateSupplier wired; pptService.shutdown() in both stop handlers |
| `src/main/java/com/classroom/ui/StudentUI.java` | Phase 2: WhiteboardPane, zoom, scroll, 8 message types; Phase 3: pptImageView/pptTab/tabPane/pptSlidePanel instance fields added; center replaced with TabPane (Whiteboard + PPT Slide); PPT_SLIDE case added to handleMessage() with auto-tab-switch |
| `pom.xml` | Phase 3: added 3 --add-opens flags for java.desktop (java.awt, sun.awt, sun.java2d) for POI AWT rendering on Java 17 |

---

## Deviations from Architecture
| What Changed | Why |
|--------------|-----|
| `TeacherUI` gained `setServer()` method | Phase 1 deviation: two-step wiring to avoid circular constructor dependency |
| `StudentUI` gained `setClient()` method | Phase 1 deviation: same two-step wiring reason |
| `ClientHandler` exposes `getOutputStream()` | Phase 1 deviation: TeacherServer.broadcast() writes directly to avoid double-wrapping |
| `ShapeData.java` added (not in Phase 2 architecture) | Antigravity added shape drawing support; ShapeData is the serializable model for RECT, ELLIPSE, LINE, ARROW, TEXT shapes — includes mutable geometry for move/resize and a copy constructor |
| `MessageType` has 18 constants instead of 10 | 8 extra types added: UNDO, REDO, CANVAS_RESIZE, SHAPE_ADD, SHAPE_UPDATE, SHAPE_REMOVE, FULL_STATE, PPT_SLIDE |
| `WhiteboardPane` has DrawMode enum (8 modes) | Antigravity added ERASER, SHAPE_RECT, SHAPE_ELLIPSE, SHAPE_LINE, SHAPE_ARROW, SHAPE_TEXT, SELECT modes beyond basic FREEHAND |
| `WhiteboardPane` has a third layer: shapeOverlayPane | A JavaFX Pane on top of the two canvases holds interactive JavaFX shape nodes (Rectangle, Ellipse, Line, Polygon, Text) so shapes are selectable and resizable |
| `WhiteboardPane` has full Undo/Redo history | BoardAction inner class + history LinkedList + redoStack; supports undo/redo for strokes, shape add/update/remove |
| `WhiteboardPane` has FullState inner class | Serializable snapshot of all strokes + shapes; sent to late-joining students via FULL_STATE message so they see the current board state on join |
| `WhiteboardPane` has zoom support | setZoom()/getZoom() apply ScaleX/ScaleY; teacher and student both have Zoom In/Out buttons |
| `WhiteboardPane` has dynamic canvas resize | setCanvasSize(w, h) resizes both canvases and the overlay; teacher has a ComboBox to switch between 4 preset sizes; resize broadcasts CANVAS_RESIZE to students |
| `TeacherServer` gained stateSupplier field | Needed to send FULL_STATE to each new student at join time |
| `TeacherServer` gained pptStateSupplier field | Needed to send current PPT slide to each new student at join time (Phase 3) |
| `TeacherServer.addClient()` sends FULL_STATE then PPT_SLIDE | Before adding the new client to the broadcast list, sends whiteboard snapshot then current slide |
| TeacherUI has two toolbar rows (instance fields) | Row 1: color, stroke width, canvas size, whiteboard/annotate toggle, undo/redo, clear, zoom. Row 2: draw mode buttons. Both are instance fields so TabPane listener can show/hide them |
| TeacherUI center is now a TabPane | Phase 3: Whiteboard tab + PPT Sharing tab; drawing toolbars auto-hide when PPT tab is selected |
| StudentUI center is now a TabPane | Phase 3: Whiteboard tab + PPT Slide tab; PPT tab auto-selected when first slide arrives |
| Default canvas size is 1280×720 (not 800×500) | Set in TeacherUI on startup; matches the "Large" preset in the ComboBox |
| PptService uses `DrawFactory.getInstance(g).getDrawable(slide).draw(g)` | POI 5.x removed XSLFSlide.render(Graphics2D); the correct 5.x API is DrawFactory.getDrawable().draw() |

---

## Known Issues
- _(none)_

---

## Verified Steps
- [x] Step 1 — pom.xml: 3 java.desktop --add-opens flags added, `mvn clean compile` passes
- [x] Step 2 — PPT_SLIDE added to MessageType (18 constants), `mvn clean compile` passes
- [x] Step 3 — SlideData.java created, `mvn clean compile` passes (14 source files)
- [x] Step 4 — PptService.java created (fixed POI 5.x API: getDrawable not render/drawableOf), `mvn clean compile` passes (15 source files)
- [x] Step 5 — TeacherServer: pptStateSupplier field + setter + addClient() block, `mvn clean compile` passes
- [x] Step 6 — TeacherUI: toolbar/shapeToolbar promoted to fields; TabPane with Whiteboard+PPT tabs; file chooser; nav buttons; displayAndBroadcastSlide(); updateNavButtons(); pptService.shutdown() in both handlers, `mvn clean compile` passes
- [x] Step 7 — StudentUI: 4 PPT instance fields; TabPane with Whiteboard+PPT tabs; PPT_SLIDE case with auto-switch, `mvn clean compile` passes
- [ ] Step 8 — Integration test pending (run `mvn javafx:run`)
