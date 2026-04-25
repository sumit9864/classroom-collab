# Phase 5 Architecture — Integration, Error Handling, UI Polish & Final Build
## Date: 2026-04-17
## Status: READY FOR DEVELOPMENT

---

## Phase 5 Goal
Harden the complete application: implement silent-disconnect detection with
a HEARTBEAT, surface connection-loss errors to the student UI, fix the fat JAR
build for Apache POI service merging, enforce minimum window sizes, and run a
full end-to-end integration test covering all five modules together.

---

## What Exists After Phase 4 (Read Before Touching Anything)

| File | Status |
|------|--------|
| `pom.xml` | 🔧 MODIFY — shade plugin needs ServicesResourceTransformer + signature filters |
| `Main.java` | ✅ NO CHANGES |
| `model/Message.java` | ✅ NO CHANGES |
| `model/MessageType.java` | ✅ NO CHANGES — HEARTBEAT already declared at position 6 |
| `model/StrokeData.java` | ✅ NO CHANGES |
| `model/ShapeData.java` | ✅ NO CHANGES |
| `model/SlideData.java` | ✅ NO CHANGES |
| `model/CodeData.java` | ✅ NO CHANGES |
| `util/NetworkUtil.java` | ✅ NO CHANGES |
| `util/PptService.java` | ✅ NO CHANGES |
| `server/ClientHandler.java` | ✅ NO CHANGES |
| `server/TeacherServer.java` | 🔧 MODIFY — add HEARTBEAT daemon thread |
| `client/StudentClient.java` | 🔧 MODIFY — add setOnDisconnect(Runnable) setter |
| `ui/LoginScreen.java` | ✅ NO CHANGES |
| `ui/WhiteboardPane.java` | ✅ NO CHANGES |
| `ui/TeacherUI.java` | 🔧 MODIFY — set minimum window size |
| `ui/StudentUI.java` | 🔧 MODIFY — wire onDisconnect callback + HEARTBEAT case + min window size |
| `src/test/java/com/classroom/NetworkUtilTest.java` | ✅ NO CHANGES |

**No new files are created in Phase 5.**

---

## A. `pom.xml` — Fix Shade Plugin for Apache POI

### Why this is needed
Apache POI uses Java's `ServiceLoader` mechanism. It declares service providers
in `META-INF/services/` files inside its JARs. When `maven-shade-plugin` merges
all JARs into one fat JAR, it overwrites these files instead of merging them.
This causes `NullPointerException` or `NoSuchElementException` at runtime when
POI tries to load its XML parsers. `ServicesResourceTransformer` fixes this by
merging (appending) all service files instead of overwriting.

Signed JARs (many Apache libraries) embed `.SF`, `.DSA`, and `.RSA` signature
files that become invalid once the JAR is repackaged. The JVM rejects the whole
fat JAR with a `SecurityException`. The filter below strips these files.

### Exact change — replace the entire `<plugin>` block for `maven-shade-plugin`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.5.1</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>

        <!-- Strip invalid signatures from Apache libs -->
        <filters>
          <filter>
            <artifact>*:*</artifact>
            <excludes>
              <exclude>module-info.class</exclude>
              <exclude>META-INF/MANIFEST.MF</exclude>
              <exclude>META-INF/*.SF</exclude>
              <exclude>META-INF/*.DSA</exclude>
              <exclude>META-INF/*.RSA</exclude>
            </excludes>
          </filter>
        </filters>

        <transformers>
          <!-- Sets the Main-Class entry in the fat JAR manifest -->
          <transformer implementation=
            "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>com.classroom.Main</mainClass>
          </transformer>
          <!-- Merges META-INF/services/* files instead of overwriting — required for POI -->
          <transformer implementation=
            "org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
        </transformers>

      </configuration>
    </execution>
  </executions>
</plugin>
```

> ⚠️ Replace ONLY the `maven-shade-plugin` block. Every other plugin block in
> `pom.xml` must remain exactly as-is.

### Important note on running the fat JAR
`mvn clean package` produces `target/classroom-collab-1.0-SNAPSHOT.jar`.
This JAR contains all Java dependencies but NOT the JavaFX native platform
libraries (`.dll`/`.so` files), because native libraries cannot be bundled
inside a JAR. Therefore:

- **Primary run command for the classroom lab** (recommended):
  ```
  mvn javafx:run
  ```
  This is already fully configured in pom.xml and works on any machine that
  has JDK 17 + Maven installed (standard lab setup).

- **Fat JAR** (`java -jar target/classroom-collab-1.0-SNAPSHOT.jar`) will only
  work on machines where JavaFX 17 is already on the module path. Do not attempt
  to run it as a standalone JAR on bare OpenJDK without JavaFX.

Document this clearly in the `Latest_Updates.md` after completing Phase 5.

---

## B. `TeacherServer.java` — Add HEARTBEAT Daemon Thread

### Why this is needed
TCP connections over a LAN can become silently dead if the OS removes the
connection from its table (e.g. after the machine sleeps, the router resets,
or a cable is briefly unplugged). Without periodic traffic, neither side notices
for minutes. A 30-second HEARTBEAT broadcast ensures:
1. Students detect the connection is still alive.
2. The dispatch thread discovers broken pipes quickly and removes stale clients.

### New field (add after the existing `dispatchThread` field declaration):
```java
private Thread heartbeatThread;
```

### New heartbeat thread (add at the END of the `start()` method, after
`dispatchThread.start()`):
```java
heartbeatThread = new Thread(() -> {
    while (running) {
        try {
            Thread.sleep(30_000); // 30 seconds
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
        }
        if (running) {
            broadcast(new Message(MessageType.HEARTBEAT, null, "Teacher"));
        }
    }
});
heartbeatThread.setDaemon(true);
heartbeatThread.setName("heartbeat");
heartbeatThread.start();
```

### Update `stop()` — interrupt the heartbeat thread (add after the existing
`dispatchThread.interrupt()` line):
```java
if (heartbeatThread != null) heartbeatThread.interrupt();
```

The complete updated beginning of `stop()` becomes:
```java
public void stop() {
    if (dispatchThread   != null) dispatchThread.interrupt();
    if (heartbeatThread  != null) heartbeatThread.interrupt();
    running = false;
    broadcast(new Message(MessageType.DISCONNECT, null, "Teacher"));
    // ... rest of stop() unchanged
}
```

> No other changes to TeacherServer. The HEARTBEAT message is sent via the
> existing `broadcast()` → `dispatchQueue` path. No new fields, no new
> suppliers, no changes to `addClient()`.

---

## C. `StudentClient.java` — Add setOnDisconnect Setter

### Why this is needed
Currently, when the teacher's server drops (network failure, teacher closes app,
power cut), `StudentClient.startListenerThread()` receives `null` from
`readMessage()` and calls `disconnect()` silently. The student window stays open
with no indication that anything happened — the student keeps trying to interact
with a dead session. A disconnect callback allows `StudentUI` to show an alert
and close the window.

### New field (add after the existing `running` field):
```java
private Runnable onDisconnectCallback; // called on FX thread when server drops unexpectedly
```

### New setter (add after the existing `isConnected()` method):
```java
/**
 * Registers a callback to be invoked on the JavaFX Application Thread
 * when the server connection is lost unexpectedly (not a graceful disconnect).
 * Must be called before connect().
 */
public void setOnDisconnect(Runnable callback) {
    this.onDisconnectCallback = callback;
}
```

### Update `startListenerThread()` — fire callback on unexpected drop:

Replace the existing `startListenerThread()` method body with:
```java
private void startListenerThread() {
    Thread listener = new Thread(() -> {
        while (running) {
            Message msg = NetworkUtil.readMessage(in);
            if (msg == null) {
                // Server closed connection — unexpected if we didn't call disconnect()
                boolean wasRunning = running;
                disconnect();
                if (wasRunning && onDisconnectCallback != null) {
                    Platform.runLater(onDisconnectCallback);
                }
                break;
            }
            Platform.runLater(() -> onMessageReceived.accept(msg));
        }
    });
    listener.setDaemon(true);
    listener.start();
}
```

**Key detail:** `boolean wasRunning = running` captures the state BEFORE
`disconnect()` sets `running = false`. This distinguishes an unexpected server
drop (`wasRunning = true`) from a student-initiated disconnect (`running` is
already `false` before the null read occurs). Only unexpected drops fire the
callback — intentional student disconnects do not.

> `disconnect()` itself does NOT change — it still sends DISCONNECT and closes
> streams. Only `startListenerThread()` changes.

---

## D. `StudentUI.java` — Wire Disconnect Callback + HEARTBEAT + Min Size

### D.1 — Wire disconnect callback in `show()`

Add this block BEFORE `client.connect()` is called. Since `StudentUI.show()`
is called after `client.connect()` has already succeeded (in `LoginScreen`),
the callback must be wired inside `show()` immediately after the scene is
built — before the stage is shown. Add it just before `stage.show()`:

```java
// Wire unexpected-disconnect handler
if (client != null) {
    client.setOnDisconnect(() -> {
        // Already on FX thread (dispatched by StudentClient)
        if (stage.isShowing()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Connection Lost");
            alert.setHeaderText("Disconnected from teacher");
            alert.setContentText(
                "The session has ended or the network connection was lost.");
            alert.showAndWait();
            stage.close();
        }
    });
}
```

**Placement:** Insert this block directly before the existing `stage.show();`
call at the bottom of `show()`.

**Why WARNING not ERROR:** The session ending is not the student's error — a
warning dialog is less alarming and more appropriate.

**Why `stage.isShowing()` guard:** If the student already clicked Disconnect
and the stage is closed, this callback must not attempt to show an alert on
a closed window.

---

### D.2 — Add HEARTBEAT case to `handleMessage()`

Add this case to the switch statement in `handleMessage()`, immediately after
the `STUDENT_LIST_UPDATE` case:

```java
case HEARTBEAT:
    // No-op — connection keep-alive from teacher. Prevents "Unhandled message" log spam.
    break;
```

Without this, every 30-second HEARTBEAT prints `[StudentUI] Unhandled message: HEARTBEAT`
to the console — harmless but noisy.

---

### D.3 — Set minimum window size

Add these two lines immediately BEFORE `stage.show()` in `show()`:
```java
stage.setMinWidth(800);
stage.setMinHeight(540);
```

Place them directly above the `setOnDisconnect` block added in D.1, so the
order near the bottom of `show()` is:
```
stage.setMinWidth(800);
stage.setMinHeight(540);
[onDisconnect wiring block]
stage.setScene(new Scene(root, 1000, 620));
stage.setTitle("Classroom Collaboration — Student");
stage.show();
```

---

## E. `TeacherUI.java` — Set Minimum Window Size

Add these two lines immediately BEFORE `stage.show()` at the bottom of `show()`:
```java
stage.setMinWidth(900);
stage.setMinHeight(540);
```

The existing close handler and `stage.setScene(...)` lines must remain exactly
as they are. The order near the bottom of `show()` becomes:
```
stage.setMinWidth(900);
stage.setMinHeight(540);
stage.setScene(new Scene(root, 1100, 620));
stage.setTitle("Classroom Collaboration — Teacher");
stage.show();
refreshStudentList();
```

---

## F. Step-by-Step Implementation Order

Work strictly in this order. Run `mvn clean compile` after each step.

| Step | File | Action | Verify |
|------|------|--------|--------|
| 1 | `pom.xml` | Replace shade plugin block with updated version including filters + ServicesResourceTransformer | `mvn clean compile` passes |
| 2 | `server/TeacherServer.java` | Add `heartbeatThread` field; add heartbeat thread at end of `start()`; update `stop()` to interrupt it | `mvn clean compile` passes |
| 3 | `client/StudentClient.java` | Add `onDisconnectCallback` field; add `setOnDisconnect()` setter; update `startListenerThread()` | `mvn clean compile` passes |
| 4 | `ui/StudentUI.java` | Add HEARTBEAT no-op case to `handleMessage()`; add `setOnDisconnect` wiring block before `stage.show()`; add min window size | `mvn clean compile` passes |
| 5 | `ui/TeacherUI.java` | Add min window size before `stage.show()` | `mvn clean compile` passes |
| 6 | Test: HEARTBEAT | Start teacher session. Open console. Wait 31 seconds. Confirm no "Unhandled message: HEARTBEAT" log appears on student console | Manual test |
| 7 | Test: Unexpected disconnect | Start teacher + student. Kill teacher process from Task Manager (do NOT click Stop Session). Confirm student sees "Connection Lost" alert and window closes | Manual test |
| 8 | Test: Graceful disconnect | Start teacher + student. Teacher clicks Stop Session. Confirm student sees "Session ended by teacher." alert (existing DISCONNECT handler — NOT the new callback) | Manual test |
| 9 | Test: Min window size | Open teacher window. Try to resize below 900×540. Confirm window resists. Same for student below 800×540 | Manual test |
| 10 | Test: Full integration — all modules | Start teacher. Connect 2 students. Test all tabs in order: Whiteboard draw → PPT load & navigate → Code type. Confirm all students sync correctly throughout | Manual test |
| 11 | Test: Late-join full sync | With teacher having active whiteboard strokes, a PPT slide loaded, and code typed — connect a new student. Confirm new student receives all three states immediately | Manual test |
| 12 | Final build | Run `mvn clean package`. Confirm it produces `target/classroom-collab-1.0-SNAPSHOT.jar` with zero errors or warnings about duplicate entries | `mvn clean package` passes |
| 13 | Final run via plugin | Run `mvn javafx:run`. Confirm full app launches from the packaged output | App launches cleanly |
| 14 | Update Latest_Updates.md | Document all Phase 5 changes, deviations, and verified steps | Documentation complete |

---

## G. Error Prevention Checklist

Read before implementing each step:

1. **`heartbeatThread` interrupt on `stop()`** — Both `dispatchThread.interrupt()`
   and `heartbeatThread.interrupt()` must be called BEFORE `running = false`.
   The heartbeat loop checks `running` after waking from sleep. If `running` is
   set to `false` first and the thread is sleeping, it will not wake until the
   30-second sleep completes unless interrupted. Always interrupt first.

2. **`wasRunning` capture in `startListenerThread()`** — This boolean MUST be
   captured BEFORE calling `disconnect()`. If captured after, `disconnect()` has
   already set `running = false` so the condition would never fire the callback.

3. **`stage.isShowing()` guard in the disconnect callback** — Without this guard,
   if the stage is already closed (e.g. student clicked Disconnect at the same
   moment the server dropped), calling `alert.showAndWait()` may throw an
   IllegalStateException or show on an invisible window.

4. **Do NOT fire disconnect callback on graceful student-initiated disconnect** —
   When a student clicks Disconnect, `client.disconnect()` sets `running = false`
   and closes streams. The listener thread may then receive a null read (EOF from
   closed stream). `wasRunning` will be `false` at that point, so the callback
   will NOT fire. This is correct. Do not change this logic.

5. **HEARTBEAT case placement in `handleMessage()` switch** — Place it early in
   the switch (after `STUDENT_LIST_UPDATE`), NOT inside a fall-through block.
   Confirm it has its own `break` statement.

6. **Do NOT add `setOnDisconnect()` call to `LoginScreen.java`** — The callback
   is wired inside `StudentUI.show()` AFTER the connection is already established.
   `LoginScreen` creates and connects the client before calling `show()`, so the
   callback is registered after connect succeeds — this is intentional and correct.

7. **Shade plugin filter for `module-info.class`** — JavaFX and many modern
   libraries include `module-info.class` files. When multiple are merged, the
   shade plugin may emit a warning or error. The exclusion in the filter block
   prevents this. Do not remove it.

8. **`ServicesResourceTransformer` has no `<configuration>` block** — it
   operates on all `META-INF/services/*` files automatically. Do not add a
   configuration child element to it.

9. **`stage.setMinWidth/setMinHeight` must come BEFORE `stage.setScene()`** —
   In JavaFX, setting minimum dimensions before attaching the scene ensures the
   constraint is applied to the initial layout pass. Setting them after `show()`
   works but may cause a brief visible resize flash.

10. **Run `mvn clean package`, NOT `mvn package`** — Without `clean`, stale
    compiled classes from previous builds may be included in the JAR even if
    those files were deleted. Always use `clean package` for the final build step.

---

## H. What Each Test Verifies

| Test | What It Proves |
|------|---------------|
| HEARTBEAT no-op | 30s broadcast does not crash or log errors on any side |
| Unexpected disconnect alert | StudentClient.onDisconnectCallback fires correctly on server process kill |
| Graceful disconnect keeps existing flow | DISCONNECT message handler in StudentUI still works as before |
| Min window size | Windows cannot be resized to unusable dimensions |
| Full integration | All 5 modules coexist — no regression from Phase 5 changes |
| Late-join sync | All 4 state suppliers (whiteboard, PPT slide, PPT whiteboard, code) deliver correctly together |
| `mvn clean package` | Shade plugin correctly merges POI service files — no ClassNotFoundException at runtime |
| `mvn javafx:run` | Authoritative run command works cleanly on the lab machine |

---

## I. Phase 5 Completion Definition

Phase 5 is complete when:
- [x] `mvn clean compile` passes with zero errors
- [x] `mvn clean package` passes with zero errors
- [x] All 13 integration tests above pass manually
- [x] `Latest_Updates.md` is updated with all Phase 5 changes
- [x] No known issues remain in `Latest_Updates.md`

The project is then fully complete and ready for classroom use via `mvn javafx:run`.
