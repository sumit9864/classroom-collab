# Latest Updates

## Current Phase: 2 — Whiteboard & Annotation Module
## Date: 2026-04-14

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
| `src/main/java/com/classroom/util/NetworkUtil.java` | Socket read/write helpers |
| `src/main/java/com/classroom/server/TeacherServer.java` | TCP ServerSocket, manages client threads |
| `src/main/java/com/classroom/server/ClientHandler.java` | One thread per connected student |
| `src/main/java/com/classroom/client/StudentClient.java` | TCP socket connection to teacher |
| `src/main/java/com/classroom/ui/LoginScreen.java` | Host/join screen for teacher and student |
| `src/main/java/com/classroom/ui/WhiteboardPane.java` | Three-layer canvas widget (whiteboard canvas, annotation canvas, shape overlay pane) used by both teacher (interactive) and student (read-only) |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Teacher's main window with student list, drawing toolbars, whiteboard |
| `src/main/java/com/classroom/ui/StudentUI.java` | Student's main window with read-only whiteboard and status bar |
| `src/test/java/com/classroom/NetworkUtilTest.java` | JUnit 5 round-trip and null-on-EOF tests |

---

## Files Modified
| File | Reason for Change |
|------|-------------------|
| `Latest_Updates.md` | Updated after every step per instructions |
| `src/test/java/com/classroom/NetworkUtilTest.java` | Phase 1 fix: removed dead PipedStream code, suppressed intentional unused-stream variables |
| `src/main/java/com/classroom/model/MessageType.java` | Phase 2: added 11 new constants (WHITEBOARD_STROKE, WHITEBOARD_CLEAR, ANNOTATION_STROKE, ANNOTATION_CLEAR, UNDO, REDO, CANVAS_RESIZE, SHAPE_ADD, SHAPE_UPDATE, SHAPE_REMOVE, FULL_STATE) |
| `src/main/java/com/classroom/server/TeacherServer.java` | Phase 2: added stateSupplier field and setStateSupplier() method; modified addClient() to send FULL_STATE snapshot to each new student before adding them to the broadcast list |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Phase 2: replaced CENTER placeholder with WhiteboardPane; added two toolbar rows (drawing + shape tools); wired shape callbacks and stateSupplier; added ScrollPane; window resized to 1100×620 |
| `src/main/java/com/classroom/ui/StudentUI.java` | Phase 2: replaced CENTER placeholder with WhiteboardPane; added zoom buttons to status bar; added ScrollPane; handled 8 new message types; window resized to 1000×620 |

---

## Deviations from Architecture
| What Changed | Why |
|--------------|-----|
| `TeacherUI` gained `setServer()` method | Phase 1 deviation: two-step wiring to avoid circular constructor dependency |
| `StudentUI` gained `setClient()` method | Phase 1 deviation: same two-step wiring reason |
| `ClientHandler` exposes `getOutputStream()` | Phase 1 deviation: TeacherServer.broadcast() writes directly to avoid double-wrapping |
| `ShapeData.java` added (not in Phase 2 architecture) | Antigravity added shape drawing support; ShapeData is the serializable model for RECT, ELLIPSE, LINE, ARROW, TEXT shapes — includes mutable geometry for move/resize and a copy constructor |
| `MessageType` has 17 constants instead of 10 | 7 extra types added: UNDO, REDO, CANVAS_RESIZE, SHAPE_ADD, SHAPE_UPDATE, SHAPE_REMOVE, FULL_STATE — all required to support the expanded feature set below |
| `WhiteboardPane` has DrawMode enum (8 modes) | Antigravity added ERASER, SHAPE_RECT, SHAPE_ELLIPSE, SHAPE_LINE, SHAPE_ARROW, SHAPE_TEXT, SELECT modes beyond basic FREEHAND |
| `WhiteboardPane` has a third layer: shapeOverlayPane | A JavaFX Pane on top of the two canvases holds interactive JavaFX shape nodes (Rectangle, Ellipse, Line, Polygon, Text) so shapes are selectable and resizable |
| `WhiteboardPane` has full Undo/Redo history | BoardAction inner class + history LinkedList + redoStack; supports undo/redo for strokes, shape add/update/remove |
| `WhiteboardPane` has FullState inner class | Serializable snapshot of all strokes + shapes; sent to late-joining students via FULL_STATE message so they see the current board state on join |
| `WhiteboardPane` has zoom support | setZoom()/getZoom() apply ScaleX/ScaleY; teacher and student both have Zoom In/Out buttons |
| `WhiteboardPane` has dynamic canvas resize | setCanvasSize(w, h) resizes both canvases and the overlay; teacher has a ComboBox to switch between 4 preset sizes; resize broadcasts CANVAS_RESIZE to students |
| `TeacherServer` gained stateSupplier field | Needed to send FULL_STATE to each new student at join time; supplier is wired from TeacherUI after WhiteboardPane exists |
| `TeacherServer.addClient()` sends FULL_STATE first | Before adding the new client to the broadcast list, it sends the current board snapshot so the student sees the existing content immediately |
| TeacherUI has two toolbar rows | Row 1: color, stroke width, canvas size, whiteboard/annotate toggle, undo/redo, clear, zoom. Row 2: draw mode buttons (Freehand, Eraser, Rectangle, Ellipse, Line, Arrow, Text Box, Select/Resize) + Delete Shape |
| StudentUI has ScrollPane around WhiteboardPane | Required because canvas can be larger than the window (up to 1920×1080) |
| TeacherUI also uses ScrollPane around WhiteboardPane | Same reason as StudentUI |
| Default canvas size is 1280×720 (not 800×500) | Set in TeacherUI on startup; matches the "Large" preset in the ComboBox |

---

## Known Issues
- _(none)_

---

## Verified Steps
- [x] Step 1 — MessageType constants added, `mvn clean compile` passes
- [x] Step 2 — StrokeData.java compiles
- [x] Step 3 — ShapeData.java compiles (added beyond Phase 2 spec)
- [x] Step 4 — WhiteboardPane.java compiles
- [x] Step 5 — TeacherUI.java updated, `mvn javafx:run` launches correctly
- [x] Step 6 — StudentUI.java updated
- [x] Step 7 — Integration test: teacher draws → student sees strokes
- [x] Step 8 — Integration test: clear board, clear annotations, undo, redo all sync correctly
- [x] Step 9 — Integration test: shapes (rect, ellipse, line, arrow, text) sync to student
- [x] Step 10 — Integration test: late-joining student receives FULL_STATE and sees existing content
