# Latest Updates

## Current Phase: 4 — Code Sharing Module
## Date: 2026-04-16

---

## Files Created
| File | Purpose |
|------|---------|
| `pom.xml` | Maven build config with all dependencies |
| `src/main/java/com/classroom/Main.java` | JavaFX Application entry point |
| `src/main/java/com/classroom/model/MessageType.java` | Enum of all message types (18 constants total) |
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
| `Latest_Updates.md` | Updated after every step per instructions |
| `src/test/java/com/classroom/NetworkUtilTest.java` | Phase 1 fix: removed dead PipedStream code, suppressed intentional unused-stream variables |
| `src/main/java/com/classroom/model/MessageType.java` | Phase 2: added 11 constants; Phase 3: added PPT_SLIDE; Phase 4: added CODE_SHARE — total now 19 constants |
| `src/main/java/com/classroom/server/TeacherServer.java` | Phase 2: stateSupplier + setStateSupplier(); Phase 3: added pptStateSupplier, pptWhiteboardStateSupplier, and their setters; Phase 4: added codeStateSupplier field, setCodeStateSupplier() setter, and Step 4 block in addClient() |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Phase 3: PPT tab + overlay whiteboard; Phase 4: added codeTab/codeEditor/languageCombo fields, updated getActivePane() with codeTab null-return branch, added if(pane==null)return guards to all 7 toolbar handlers, built Code Sharing tab UI, updated TabPane to 3 tabs, added toolbar visibility listener, wired codeStateSupplier |
| `src/main/java/com/classroom/ui/StudentUI.java` | Phase 3: PPT tab + overlay; Phase 4: added codeTab/codeViewer fields, built Code tab (dark-themed read-only TextArea), updated TabPane to 3 tabs, added codeTab guard to both zoom buttons, added CODE_SHARE case to handleMessage() |
| `pom.xml` | Phase 3: added 3 --add-opens flags for java.desktop (java.awt, sun.awt, sun.java2d) to javafx-maven-plugin |

---

## Deviations from Architecture
| What Changed | Why |
|--------------|-----|
| `TeacherUI` setServer() two-step wiring | Phase 1 deviation: avoids circular constructor dependency |
| `StudentUI` setClient() two-step wiring | Phase 1 deviation: same reason |
| `ClientHandler` exposes getOutputStream() | Phase 1 deviation: TeacherServer.broadcast() writes directly |
| `ShapeData.java` added (not in Phase 2 spec) | Phase 2 deviation: required for shape drawing support |
| `MessageType` has 18 constants | Phase 2 added 7 extra (UNDO, REDO, CANVAS_RESIZE, SHAPE_ADD, SHAPE_UPDATE, SHAPE_REMOVE, FULL_STATE); Phase 3 added 1 (PPT_SLIDE) |
| `WhiteboardPane` has DrawMode enum (8 modes) | Phase 2 deviation: ERASER + 6 shape/select modes beyond basic FREEHAND |
| `WhiteboardPane` has full Undo/Redo history | Phase 2 deviation: BoardAction + history LinkedList + redoStack |
| `WhiteboardPane` has FullState inner class | Phase 2 deviation: serializable snapshot for late-join sync |
| `WhiteboardPane` has zoom + dynamic canvas resize | Phase 2 deviation: setZoom(), setCanvasSize() |
| Default canvas size is 1280x720 | Phase 2 deviation: set in TeacherUI; teacher has ComboBox for 4 presets |
| `TeacherServer` has stateSupplier + pptWhiteboardStateSupplier | Phase 2 added stateSupplier; Phase 3 added pptStateSupplier AND pptWhiteboardStateSupplier (third supplier not in Phase 3 spec) — required because PPT tab has its own drawable whiteboard layer whose state must be synced to late-joining students |
| `WhiteboardPane` gained setTransparentBackground(boolean) | Added in Phase 3 — required for the PPT overlay pane to show the slide image behind it without the grey/white canvas background obscuring the slide |
| `pptWhiteboardPane` added to TeacherUI (not in Phase 3 spec) | Antigravity added a second full WhiteboardPane as a transparent overlay directly on top of the PPT slide — teacher can draw/annotate on top of the current slide. Uses senderName "Teacher_PPT" to distinguish from main whiteboard traffic |
| `pptWhiteboardPane` added to StudentUI (not in Phase 3 spec) | Student has a matching read-only transparent WhiteboardPane overlay on the PPT slide. When PPT_SLIDE first arrives, pptImageView + overlay Group are placed into pptSlidePanel together |
| `getActivePane()` and `getActiveSender()` helpers in TeacherUI | Routes all toolbar actions (undo, redo, clear, zoom, shape tools) to the correct WhiteboardPane based on which tab is currently selected. Returns pptWhiteboardPane/"Teacher_PPT" when on PPT tab; whiteboardPane/"Teacher" otherwise |
| Message routing by senderName in StudentUI.handleMessage() | All drawing messages (WHITEBOARD_STROKE, SHAPE_ADD, UNDO, etc.) are routed to pptWhiteboardPane if msg.getSenderName() equals "Teacher_PPT", otherwise to whiteboardPane — no new MessageType constants needed |
| Drawing toolbars remain visible on PPT tab | Architecture spec said to hide them on PPT tab; actual build keeps them visible because the PPT tab now has a fully interactive drawing overlay, making the tools relevant on that tab too |
| displayAndBroadcastSlide() clears PPT whiteboard on slide change | When teacher navigates to a new slide, both whiteboard and annotation layers of the pptWhiteboardPane are cleared and the clears are broadcast — prevents old drawings appearing on the new slide |
| PptService uses DrawFactory.getInstance(g).getDrawable(slide).draw(g) | Architecture spec used slide.render(g); Antigravity used the POI SL draw API instead — functionally equivalent and equally valid |
| PptService TARGET_WIDTH is 1920 (not 960) | Renders at Full HD resolution for sharper display; byte arrays are larger but image quality is significantly better |
| pptImageView.fitWidth/fitHeight bound to container | Both teacher and student pptImageView are bound to their container's width/height properties so the slide scales to fill the available panel area |

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

### Phase 4 — Code Sharing Module
- [x] P4 Step 1 — CODE_SHARE added to MessageType (19th constant), mvn clean compile passes
- [x] P4 Step 2 — CodeData.java created (Serializable, serialVersionUID=1L), mvn clean compile passes
- [x] P4 Step 3 — TeacherServer.java updated: codeStateSupplier field + setter + addClient() Step 4 block, mvn clean compile passes
- [x] P4 Step 4 — TeacherUI.java updated: CodeData import, 3 new fields, getActivePane() codeTab branch, null guards on all 7 handlers, Code Sharing tab UI, TabPane 3-tab, toolbar listener, codeStateSupplier wiring; mvn clean compile passes
- [x] P4 Step 5 — StudentUI.java updated: CodeData import, codeTab/codeViewer fields, Code tab dark-theme UI, TabPane 3-tab, zoom guards, CODE_SHARE case; mvn clean compile passes
- [ ] P4 Step 6 — Integration test A: teacher shares code → student auto-switches and sees code
- [ ] P4 Step 7 — Integration test B: toolbar hide/show on code tab switch
- [ ] P4 Step 8 — Integration test C: teacher re-shares updated code → student codeViewer updates
- [ ] P4 Step 9 — Integration test D: late-joining student receives current code immediately
- [ ] P4 Step 10 — Integration test E: all 3 tabs work simultaneously
