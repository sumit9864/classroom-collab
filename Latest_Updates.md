# Latest Updates

## Current Phase: Post-Phase-5 — Bug Fixes & Enhancements
## Date: 2026-04-18

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
| `src/main/java/com/classroom/ui/TeacherUI.java` | Teacher's main window — Whiteboard tab + PPT Sharing tab + Code Sharing tab |
| `src/main/java/com/classroom/ui/StudentUI.java` | Student's main window — Whiteboard tab + PPT Slide tab + Code tab |
| `src/test/java/com/classroom/NetworkUtilTest.java` | JUnit 5 round-trip and null-on-EOF tests |

---

## Files Modified
| File | Reason for Change |
|------|-------------------|
| `pom.xml` | Phase 5: replaced maven-shade-plugin block to add `ServicesResourceTransformer` (POI ServiceLoader merge) and signature-file filters (strip `.SF`/`.DSA`/`.RSA` to prevent `SecurityException` at startup) |
| `src/main/java/com/classroom/server/TeacherServer.java` | Phase 5+: rewrote `stop()` to send `DISCONNECT` synchronously before interrupting threads; added `getPort()` getter |
| `src/main/java/com/classroom/client/StudentClient.java` | Phase 5+: updated `connect()` to use `InetSocketAddress` with an explicit 5000ms timeout to prevent UI freeze on bad IP |
| `src/main/java/com/classroom/ui/LoginScreen.java` | Phase 5+: added port range validation (1025-65535) and reserved name block ("Teacher", "Teacher_PPT") |
| `src/main/java/com/classroom/model/MessageType.java` | Phase 5+: removed unused `AUTH_FAIL` dead code |
| `src/main/java/com/classroom/ui/StudentUI.java` | Phase 5+: fixed empty `CODE_SHARE` auto-switching; added line numbers and "Copy to Clipboard" button logic to Code tab |
| `src/main/java/com/classroom/ui/TeacherUI.java` | Phase 5+: added IP/Port display and connected student count; added Clear button to Code tab; added arrow key PPT navigation |

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
| `TeacherUI` `codeEditor` tab key intercepted | Pressing Tab inputs 4 spaces instead of shifting GUI focus |
| `TeacherUI` and `StudentUI` use dark theme for code viewer | Improves aesthetics; premium high-contrast feel |
| `StudentUI` routing handles `STROKE_PROGRESS` | Routes to `targetPane.applyStrokeProgress` to render received partial strokes immediately |
| `ClientHandler` Phase 1 TODO comment removed | Architectural path confirmed; students are read-only viewers |

---

## Known Issues
- _(none)_

---

## Run Commands
| Command | Purpose |
|---------|---------|
| `mvn javafx:run` | **Primary classroom run command** — recommended for lab machines with JDK 17 + Maven |
| `mvn clean package` | Produces `target/classroom-collab-1.0-SNAPSHOT.jar` (fat JAR — requires JavaFX on module path to run standalone) |

> **Note:** The fat JAR produced by `mvn clean package` bundles all Java dependencies but NOT JavaFX native libraries (`.dll`/`.so`). Use `mvn javafx:run` for classroom use.

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

### Phase 5 — Integration, Polish & Final Build
- [x] P5 Step 1 — pom.xml: shade plugin updated with ServicesResourceTransformer + signature filters; mvn clean compile passes
- [x] P5 Step 2 — TeacherServer.java: heartbeatThread field added; 30s HEARTBEAT daemon thread started at end of start(); stop() interrupts heartbeatThread before running=false; mvn clean compile passes
- [x] P5 Step 3 — StudentClient.java: onDisconnectCallback field + setOnDisconnect() setter added; startListenerThread() rewrote to capture wasRunning before disconnect(); mvn clean compile passes
- [x] P5 Step 4 — StudentUI.java: HEARTBEAT no-op case added; min window 800×540; disconnect alert wired with stage.isShowing() guard; mvn clean compile passes
- [x] P5 Step 5 — TeacherUI.java: min window 900×540 added before stage.setScene(); mvn clean compile passes
- [x] P5 Step 12 — mvn clean package: BUILD SUCCESS, target/classroom-collab-1.0-SNAPSHOT.jar produced with zero errors
- [x] P5 Step 6 — Integration test: HEARTBEAT — wait 31s, confirm no "Unhandled message: HEARTBEAT" on student console
- [x] P5 Step 7 — Integration test: unexpected disconnect — kill teacher process, student sees "Connection Lost" alert
- [x] P5 Step 8 — Integration test: graceful disconnect — teacher clicks Stop Session, student sees "Session ended by teacher." (not the new alert)
- [x] P5 Step 9 — Integration test: min window size — teacher window resists below 900×540, student below 800×540
- [x] P5 Step 10 — Integration test: full end-to-end — all 3 tabs work simultaneously with 2 students
- [x] P5 Step 11 — Integration test: late-join full sync — new student receives whiteboard + PPT + code state immediately
- [x] P5 Step 13 — mvn javafx:run: app launches cleanly with no warnings

### Post-Phase-5 — Bug Fixes & Enhancements
- [x] P5+ Step 1 — TeacherServer.java: fixed DISCONNECT order; added getPort(); mvn clean compile passes
- [x] P5+ Step 2 — StudentClient.java: added 5000ms explicit timeout to connection; mvn clean compile passes
- [x] P5+ Step 3 — LoginScreen.java: port range rules (1025-65535) and reserved name block ("Teacher", "Teacher_PPT"); mvn clean compile passes
- [x] P5+ Step 4 — MessageType.java: removed AUTH_FAIL dead code; mvn clean compile passes
- [x] P5+ Step 5 — TeacherUI.java: IP/Port view, student count label, Clear code button & explicit blank message, PPT arrow keystrokes
- [x] P5+ Step 6 — StudentUI.java: fixed empty-code tab switcher, added line numbers text area, added "Copy to Clipboard" with 2-second PausedTransition
- [x] Test 7a — Graceful disconnect dialog (Session ended by teacher)
- [x] Test 7b — Bad IP timeout dialog (5-second connection timeout, non-blocking UI alert)
- [x] Test 7c — Reserved name block triggers correctly 
- [x] Test 7d — Port range block triggers correctly 
- [x] Test 7e — IP and Port displayed on teacher window bar
- [x] Test 7f — Student count dynamically adjusts
- [x] Test 7g — Clear Code empties student board without auto-switching tabs
- [x] Test 7h — PPT Arrow Key navigation
- [x] Test 7i — Copy Button visual toggle and reset
- [x] Test 7j — Code tab Line Numbers mirror dynamic text content
