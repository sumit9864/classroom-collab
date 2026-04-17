# Latest Updates

## Current Phase: 4 — Code Sharing Module
## Date: 2026-04-17

---

## Files Created
| File | Purpose |
|------|---------|
| `pom.xml` | Maven build config with all dependencies |
| `src/main/java/com/classroom/Main.java` | JavaFX Application entry point |
| `src/main/java/com/classroom/model/MessageType.java` | Enum of all message types (20 constants total) |
| `src/main/java/com/classroom/model/Message.java` | Serializable message object |
| `src/main/java/com/classroom/model/StrokeData.java` | Serializable freehand stroke payload |
| `src/main/java/com/classroom/model/ShapeData.java` | Serializable shape model (RECT, ELLIPSE, LINE, ARROW, TEXT) |
| `src/main/java/com/classroom/model/SlideData.java` | Serializable PPT slide payload — PNG byte[], slideIndex, totalSlides |
| `src/main/java/com/classroom/model/CodeData.java` | Serializable code sharing payload — code String + language String (Phase 4) |
| `src/main/java/com/classroom/util/NetworkUtil.java` | Socket read/write helpers |
| `src/main/java/com/classroom/util/PptService.java` | POI XSLF slide loader and renderer; background render thread; slide navigation |
| `src/main/java/com/classroom/server/TeacherServer.java` | TCP ServerSocket, manages client threads |
| `src/main/java/com/classroom/server/ClientHandler.java` | One thread per connected student |
| `src/main/java/com/classroom/client/StudentClient.java` | TCP socket connection to teacher |
| `src/main/java/com/classroom/ui/LoginScreen.java` | Host/join screen for teacher and student |
| `src/main/java/com/classroom/ui/WhiteboardPane.java` | Three-layer canvas widget with drawing, shapes, undo/redo, zoom, transparent mode |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Teacher's main window — Whiteboard tab + PPT Sharing tab with transparent overlay whiteboard |
| `src/main/java/com/classroom/ui/StudentUI.java` | Student's main window — Whiteboard tab + PPT Slide tab with transparent overlay whiteboard |
| `src/test/java/com/classroom/NetworkUtilTest.java` | JUnit 5 round-trip and null-on-EOF tests |

---

## Files Modified
| File | Reason for Change |
|------|-------------------|
| `Latest_Updates.md` | Updated to document Phase 4 completion and all Real-Time Architecture deviations |
| `src/main/java/com/classroom/server/TeacherServer.java` | Added `codeStateSupplier`, refactored entirely to use `LinkedBlockingQueue` and `dispatchThread` for non-blocking async broadcasts (`doSendAll`, `broadcastLatest`), updated `addClient()` with Step 4 block. |
| `src/main/java/com/classroom/model/MessageType.java` | Added `CODE_SHARE` and `STROKE_PROGRESS` constants |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Built Code Sharing tab (`codeEditor`, dark-theme, 4-space tab intercept filter); wired `codeStateSupplier`; added `PauseTransition` for real-time debounced auto-sync; removed "Share Code" button & language dropdown; wired real-time shape callbacks using `broadcastLatest` and `setStrokeProgressCallback`. |
| `src/main/java/com/classroom/ui/StudentUI.java` | Built Code Sharing tab (`codeViewer`, dark-theme, non-editable); added zoom guards; added `CODE_SHARE` handler; added `STROKE_PROGRESS` handler. |
| `src/main/java/com/classroom/ui/WhiteboardPane.java` | Added `progressOverlayCanvas`, `lastStrokeProgressMs`, `lastShapeDragMs`, `currentDragShapeId`; implemented `createShapeFromBounds`, `updateShapeGeometry` to support real-time shape streaming and live stroke streaming to overlay canvas without persisting them prematurely in undo history. |
| `src/main/java/com/classroom/server/ClientHandler.java` | Removed obsolete Phase 1 `TODO` comment as students are confirmed strictly read-only for these modules. |
| `Project_Context.md` | Updated module description and phase summary to reflect actual Phase 4 Real-Time Code Sharing and async syncing features. |

---

## Deviations from Architecture
| What Changed | Why |
|--------------|-----|
| `TeacherUI` setServer() two-step wiring | Phase 1 deviation: avoids circular constructor dependency |
| `StudentUI` setClient() two-step wiring | Phase 1 deviation: same reason |
| `ClientHandler` exposes getOutputStream() | Phase 1 deviation: TeacherServer.broadcast() writes directly |
| `ShapeData.java` added (not in Phase 2 spec) | Phase 2 deviation: required for shape drawing support |
| `MessageType` has 20 constants | Phase 2 added 7 extra; Phase 3 added 1 (PPT_SLIDE); Phase 4 architecture required CODE_SHARE but real-time feature also required STROKE_PROGRESS |
| `WhiteboardPane` has DrawMode enum (8 modes) | Phase 2 deviation: ERASER + 6 shape/select modes beyond basic FREEHAND |
| `WhiteboardPane` has full Undo/Redo history | Phase 2 deviation: BoardAction + history LinkedList + redoStack |
| `WhiteboardPane` has FullState inner class | Phase 2 deviation: serializable snapshot for late-join sync |
| `WhiteboardPane` has zoom + dynamic canvas resize | Phase 2 deviation: setZoom(), setCanvasSize() |
| Default canvas size is 1280x720 | Phase 2 deviation: set in TeacherUI; teacher has ComboBox for 4 presets |
| `TeacherServer` has stateSupplier + pptWhiteboardStateSupplier | Phase 2/3 added these because PPT tab has its own drawable whiteboard layer whose state must be synced |
| `WhiteboardPane` gained setTransparentBackground(boolean) | Added in Phase 3 — required for the PPT overlay pane to show the slide image behind it |
| `pptWhiteboardPane` added to TeacherUI (not in Phase 3 spec) | Antigravity added a second full WhiteboardPane as a transparent overlay directly on top of the PPT slide |
| `pptWhiteboardPane` added to StudentUI (not in Phase 3 spec) | Student has a matching read-only transparent WhiteboardPane overlay on the PPT slide |
| `getActivePane()` and `getActiveSender()` helpers in TeacherUI | Routes all toolbar actions to the correct WhiteboardPane based on active tab |
| Message routing by senderName in StudentUI.handleMessage() | Drawing messages are routed to pptWhiteboardPane if sender is "Teacher_PPT", else whiteboardPane |
| Drawing toolbars remain visible on PPT tab | Architecture spec said to hide them on PPT tab; actual build keeps them visible for the active PPT overlay |
| displayAndBroadcastSlide() clears PPT whiteboard on slide change | Prevents old drawings appearing on the new slide |
| PptService uses DrawFactory API | Architecture spec used slide.render(g); actual uses POI SL draw API |
| PptService TARGET_WIDTH is 1920 (not 960) | Renders at Full HD resolution for sharper display |
| pptImageView bound to container | Both teacher and student pptImageView bound to width/height to scale perfectly |
| `TeacherServer` made entirely async with a daemon dispatch thread | Fixing UI freezing on the JavaFX thread caused by high frequency synchronous TCP socket blocking during broadcast |
| `STROKE_PROGRESS` MessageType added | Required to transmit live freehand strokes at ~60Hz without polluting undo history |
| `WhiteboardPane` progress overlay canvas added | Required to render live strokes momentarily without saving them into the permanent graphic context |
| `WhiteboardPane` real-time shape stretch broadcast | Streams shape dimension updates live via `broadcastLatest` to let students see shapes resizing dynamically |
| `TeacherUI` "Share Code" button removed | Replaced by a `PauseTransition` debounced listener to auto-sync text 300ms after typing |
| `TeacherUI` `languageCombo` removed | Reduced complexity by enforcing Plain Text broadcasting only |
| `TeacherUI` `codeEditor` tab key intercepted | Pressing Tab inputs 4 spaces instead of shifting GUI focus to match formatting correctly |
| `TeacherUI` and `StudentUI` use dark theme for code viewer | Improves aesthetics, gives premium high-contrast feel different from regular text boxes |
| `StudentUI` routing handles `STROKE_PROGRESS` | Routes to `targetPane.applyStrokeProgress` to render the received partial strokes immediately |
| `ClientHandler` Phase 1 TODO comment removed | Final architectural path determined students are read-only viewers, making incoming listener expansion obsolete |

---

## Known Issues
- _(none)_

---

## Verified Steps
- [x] Step 1 — pom.xml updated with java.desktop --add-opens flags, mvn clean compile passes
- [x] Step 2 — PPT_SLIDE added to MessageType, mvn clean compile passes
- [x] Step 3 — SlideData.java created and compiles
- [x] Step 4 — PptService.java created and compiles
- [x] Step 5 — TeacherServer.java updated with pptStateSupplier and pptWhiteboardStateSupplier
- [x] Step 6 — TeacherUI.java updated: PPT tab, overlay whiteboard, toolbars, suppliers wired
- [x] Step 7 — StudentUI.java updated: PPT tab, overlay whiteboard, PPT_SLIDE handler, message routing
- [x] Step 8 — Integration test: teacher loads PPTX → slides render → student auto-switches to PPT tab
- [x] Step 9 — Integration test: teacher navigates slides → student updates in real time
- [x] Step 10 — Integration test: teacher draws on PPT overlay → student sees drawing on top of slide
- [x] Step 11 — Integration test: slide change clears PPT overlay drawings on both sides
- [x] Step 12 — Integration test: student joins mid-session → receives current slide + PPT overlay state
- [x] P4 Step 1 — CODE_SHARE and STROKE_PROGRESS added to MessageType (20 constants), mvn clean compile passes
- [x] P4 Step 2 — CodeData.java created (Serializable, serialVersionUID=1L), mvn clean compile passes
- [x] P4 Step 3 — TeacherServer.java updated: codeStateSupplier, async dispatch loop, broadcastLatest, mvn clean compile passes
- [x] P4 Step 4 — TeacherUI.java updated: Code Sharing tab, debounced sync, dark theme, tab interception, mvn clean compile passes
- [x] P4 Step 5 — StudentUI.java updated: Code viewer tab, STROKE_PROGRESS routing, CODE_SHARE routing, dark theme, mvn clean compile passes
- [x] P4 Step 6 — Architecture Validation: verified no compile errors exist after real-time canvas integration
