# Phase 4 Architecture — Code Sharing Module
## Date: 2026-04-16
## Status: READY FOR DEVELOPMENT

---

## Phase 4 Goal
Add a "Code Sharing" tab where the teacher types or pastes a code snippet and broadcasts it to all connected students with a single "Share Code" button click. Students see a read-only, auto-updating view of the shared code. A late-joining student automatically receives the last shared snippet.

---

## What Exists After Phase 3 (Do NOT Modify Unless Listed Below)

| File | Status |
|------|--------|
| `pom.xml` | ✅ NO CHANGES — all required dependencies already present |
| `Main.java` | ✅ NO CHANGES |
| `model/Message.java` | ✅ NO CHANGES |
| `model/MessageType.java` | 🔧 ADD 1 constant → `CODE_SHARE` |
| `model/StrokeData.java` | ✅ NO CHANGES |
| `model/ShapeData.java` | ✅ NO CHANGES |
| `model/SlideData.java` | ✅ NO CHANGES |
| `util/NetworkUtil.java` | ✅ NO CHANGES |
| `util/PptService.java` | ✅ NO CHANGES |
| `server/ClientHandler.java` | ✅ NO CHANGES |
| `server/TeacherServer.java` | 🔧 ADD 4th state supplier |
| `ui/WhiteboardPane.java` | ✅ NO CHANGES |
| `ui/LoginScreen.java` | ✅ NO CHANGES |
| `ui/TeacherUI.java` | 🔧 ADD Code tab + toolbar visibility + supplier wiring |
| `ui/StudentUI.java` | 🔧 ADD Code tab + CODE_SHARE handler |
| `NetworkUtilTest.java` | ✅ NO CHANGES |

**New file to create:**
- `src/main/java/com/classroom/model/CodeData.java`

---

## A. New Model Class — `CodeData.java`

**File:** `src/main/java/com/classroom/model/CodeData.java`
**Purpose:** Serializable payload for code sharing messages.

```java
package com.classroom.model;

import java.io.Serializable;

public class CodeData implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String code;      // The full code text
    private final String language;  // Display hint only (e.g. "Java", "Python") — not parsed

    public CodeData(String code, String language) {
        this.code = code != null ? code : "";
        this.language = language != null ? language : "Plain Text";
    }

    public String getCode()     { return code; }
    public String getLanguage() { return language; }
}
```

---

## B. `MessageType.java` — Add 1 Constant

Add exactly ONE new constant at the end of the enum, inside a `// Phase 4` comment block:

```java
// Phase 4 — Code Sharing
CODE_SHARE   // Teacher → All Students (and late-join sync): CodeData payload
```

**Total after change: 19 constants.**

> ⚠️ Do NOT rename or reorder any existing constant — serialized enum ordinals must stay stable.

---

## C. `TeacherServer.java` — Add 4th State Supplier

### New field (add after `pptWhiteboardStateSupplier`):
```java
private Supplier<Message> codeStateSupplier;  // provides last shared CodeData for late-join
```

### New setter (add after `setPptWhiteboardStateSupplier()`):
```java
/** Set after construction, once codeEditor TextArea exists in TeacherUI. */
public void setCodeStateSupplier(Supplier<Message> codeStateSupplier) {
    this.codeStateSupplier = codeStateSupplier;
}
```

### Modify `addClient()` — add Step 4 block after the existing Step 3 block:
```java
// 4. Send last shared code snippet to this student only
if (codeStateSupplier != null) {
    try {
        Message codeMsg = codeStateSupplier.get();
        if (codeMsg != null) handler.send(codeMsg);
    } catch (Exception e) {
        System.err.println("[TeacherServer] Failed to send code state to "
                + handler.getStudentName() + ": " + e.getMessage());
    }
}
```

> The existing `clients.add(handler)` and `broadcastStudentList()` calls remain as Step 5 — no change to ordering logic, just insert the new block above them.

---

## D. `TeacherUI.java` — Add Code Tab

### D.1 New instance fields (add after existing Phase 3 fields)

```java
// Phase 4 — Code Sharing fields
private Tab      codeTab;
private TextArea codeEditor;
private ComboBox<String> languageCombo;
```

> No field needed for "last shared code" — the `codeStateSupplier` lambda reads directly from `codeEditor.getText()` at the moment a student joins.

---

### D.2 Update `getActivePane()` — add code tab guard

Replace the existing method body:

```java
private WhiteboardPane getActivePane() {
    Tab selected = tabPane.getSelectionModel().getSelectedItem();
    if (pptTab  != null && selected == pptTab)  return pptWhiteboardPane;
    if (codeTab != null && selected == codeTab) return null; // no canvas on code tab
    return whiteboardPane;
}
```

`getActiveSender()` — **no change required** (already returns "Teacher" as default, which is correct).

---

### D.3 Add null-check guards to all 6 toolbar button handlers

All handlers that call `getActivePane()` must guard against a `null` return (which occurs when the code tab is active).

Apply this pattern to every handler that calls `getActivePane()`:

```java
// BEFORE (example — undoBtn)
undoBtn.setOnAction(e -> {
    getActivePane().undo();
    if (server != null) server.broadcast(new Message(MessageType.UNDO, null, getActiveSender()));
});

// AFTER
undoBtn.setOnAction(e -> {
    WhiteboardPane pane = getActivePane();
    if (pane == null) return;   // code tab active — no drawing action
    pane.undo();
    if (server != null) server.broadcast(new Message(MessageType.UNDO, null, getActiveSender()));
});
```

Apply the same `if (pane == null) return;` guard to:
- `undoBtn`
- `redoBtn`
- `clearBoard`
- `clearAnnotations`
- `zoomInBtn`
- `zoomOutBtn`
- `deleteShapeBtn`

---

### D.4 Build the Code tab content (add inside `show()`, before TabPane construction)

```java
// ── Tab 3: Code Sharing ────────────────────────────────────────────────
languageCombo = new ComboBox<>();
languageCombo.getItems().addAll(
        "Java", "Python", "JavaScript", "C++", "HTML", "SQL", "Plain Text");
languageCombo.setValue("Java");

Button shareCodeBtn = new Button("Share Code");
shareCodeBtn.setStyle("-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;");

Label shareStatusLabel = new Label("Not yet shared");
shareStatusLabel.setStyle("-fx-text-fill: #888;");

HBox codeControls = new HBox(10,
        new Label("Language:"), languageCombo,
        shareCodeBtn,
        new Separator(),
        shareStatusLabel);
codeControls.setAlignment(Pos.CENTER_LEFT);
codeControls.setPadding(new Insets(8, 12, 8, 12));
codeControls.setStyle("-fx-background-color: #ececec; -fx-border-color: #ccc; " +
        "-fx-border-width: 0 0 1 0;");

codeEditor = new TextArea();
codeEditor.setPromptText("Type or paste code here, then click \"Share Code\" to broadcast to students…");
codeEditor.setFont(javafx.scene.text.Font.font("Monospaced", 14));
codeEditor.setWrapText(false);
VBox.setVgrow(codeEditor, Priority.ALWAYS);

VBox codePanel = new VBox(codeControls, codeEditor);
VBox.setVgrow(codePanel, Priority.ALWAYS);

codeTab = new Tab("Code Sharing", codePanel);
codeTab.setClosable(false);

// Share Code button action
shareCodeBtn.setOnAction(e -> {
    String code = codeEditor.getText();
    String lang = languageCombo.getValue();
    CodeData cd = new CodeData(code, lang);
    shareStatusLabel.setText("Shared at " +
            java.time.LocalTime.now().withNano(0).toString());
    shareStatusLabel.setStyle("-fx-text-fill: #27ae60;");
    if (server != null) {
        server.broadcast(new Message(MessageType.CODE_SHARE, cd, "Teacher"));
    }
});
```

---

### D.5 Add `codeTab` to the TabPane

Change the TabPane construction line from:
```java
tabPane = new TabPane(whiteboardTab, pptTab);
```
To:
```java
tabPane = new TabPane(whiteboardTab, pptTab, codeTab);
```

---

### D.6 Add toolbar visibility listener (add after TabPane is constructed)

Drawing toolbars are meaningless on the Code tab — hide them automatically:

```java
// Hide drawing toolbars when Code tab is active
tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
    boolean drawVisible = (newTab != codeTab);
    toolbar.setVisible(drawVisible);
    toolbar.setManaged(drawVisible);
    shapeToolbar.setVisible(drawVisible);
    shapeToolbar.setManaged(drawVisible);
});
// Initial state (whiteboard tab is selected by default)
toolbar.setVisible(true);
toolbar.setManaged(true);
shapeToolbar.setVisible(true);
shapeToolbar.setManaged(true);
```

---

### D.7 Wire `codeStateSupplier` (add in `show()` alongside the other supplier wiring)

Add this immediately after the existing `server.setPptStateSupplier(...)` call:

```java
// Wire code state supplier for late-join sync
if (server != null) {
    server.setCodeStateSupplier(() -> {
        if (codeEditor == null) return null;
        String code = codeEditor.getText();
        if (code == null || code.isBlank()) return null;
        String lang = (languageCombo != null) ? languageCombo.getValue() : "Plain Text";
        return new Message(MessageType.CODE_SHARE, new CodeData(code, lang), "Teacher");
    });
}
```

---

### D.8 Required import to add to `TeacherUI.java`

```java
import com.classroom.model.CodeData;
```

---

## E. `StudentUI.java` — Add Code Tab

### E.1 New instance fields (add after existing Phase 3 fields)

```java
// Phase 4 — Code Sharing fields
private Tab      codeTab;
private TextArea codeViewer;
```

---

### E.2 Build the Code tab content (add inside `show()`, before TabPane construction)

```java
// ── Tab 3: Code ────────────────────────────────────────────────────────
codeViewer = new TextArea();
codeViewer.setEditable(false);
codeViewer.setPromptText("Waiting for teacher to share code…");
codeViewer.setFont(javafx.scene.text.Font.font("Monospaced", 14));
codeViewer.setWrapText(false);
codeViewer.setStyle("-fx-control-inner-background: #1e1e1e; " +
        "-fx-text-fill: #d4d4d4;");  // dark editor theme for contrast

codeTab = new Tab("Code", codeViewer);
codeTab.setClosable(false);
```

---

### E.3 Add `codeTab` to the TabPane

Change:
```java
tabPane = new TabPane(whiteboardTab, pptTab);
```
To:
```java
tabPane = new TabPane(whiteboardTab, pptTab, codeTab);
```

---

### E.4 Update zoom buttons to handle code tab (null-safe)

The existing zoom button handlers in `StudentUI` reference `pptWhiteboardPane` by tab check. Extend them to also handle `codeTab` (no-op):

```java
zoomInBtn.setOnAction(e -> {
    Tab selected = tabPane.getSelectionModel().getSelectedItem();
    if (selected == codeTab) return;                     // no zoom on code tab
    WhiteboardPane active = (selected == pptTab)
            ? pptWhiteboardPane : whiteboardPane;
    active.setZoom(active.getZoom() + 0.1);
});

zoomOutBtn.setOnAction(e -> {
    Tab selected = tabPane.getSelectionModel().getSelectedItem();
    if (selected == codeTab) return;
    WhiteboardPane active = (selected == pptTab)
            ? pptWhiteboardPane : whiteboardPane;
    active.setZoom(active.getZoom() - 0.1);
});
```

---

### E.5 Handle `CODE_SHARE` in `handleMessage()`

Add this case to the existing switch statement in `handleMessage()`, inside the `// Phase 3` block (or after it):

```java
// Phase 4 — Code Sharing
case CODE_SHARE:
    CodeData cd = (CodeData) msg.getPayload();
    codeViewer.setText(cd.getCode());
    tabPane.getSelectionModel().select(codeTab);
    break;
```

---

### E.6 Required import to add to `StudentUI.java`

```java
import com.classroom.model.CodeData;
```

---

## F. Step-by-Step Implementation Order

Work strictly in this order. Run `mvn clean compile` after each step to verify before proceeding.

| Step | Action | Verify |
|------|--------|--------|
| 1 | Add `CODE_SHARE` constant to `MessageType.java` | `mvn clean compile` passes |
| 2 | Create `CodeData.java` in `model/` package | `mvn clean compile` passes |
| 3 | Add `codeStateSupplier` field + setter + `addClient()` block to `TeacherServer.java` | `mvn clean compile` passes |
| 4 | Update `TeacherUI.java`: new fields, `getActivePane()`, null guards on 7 handlers, Code tab build, TabPane update, toolbar listener, supplier wiring, import | `mvn clean compile` passes |
| 5 | Update `StudentUI.java`: new fields, Code tab build, TabPane update, zoom guard update, `CODE_SHARE` case, import | `mvn clean compile` passes |
| 6 | Integration test A — Teacher shares code → student auto-switches to Code tab and sees code | Manual test |
| 7 | Integration test B — Teacher switches to Code tab → drawing toolbars hide; switches back → toolbars reappear | Manual test |
| 8 | Integration test C — Teacher changes language + code, shares again → student code updates | Manual test |
| 9 | Integration test D — Student joins AFTER teacher has shared code → student receives current code on Code tab | Manual test |
| 10 | Integration test E — Whiteboard and PPT tabs still work normally alongside Code tab | Manual test |

---

## G. Error Prevention Checklist

Before implementing each file, read this checklist:

1. **`CodeData` must implement `Serializable`** with `serialVersionUID = 1L`. If this is missing, `ObjectOutputStream` will throw `NotSerializableException` at runtime.

2. **`getActivePane()` returns `null` when Code tab is active.** Every caller in `TeacherUI` must have a `if (pane == null) return;` guard. There are exactly 7 callers — all 7 must be updated.

3. **`codeStateSupplier` lambda must null-check `codeEditor`** because the lambda is set during `show()` while `codeEditor` is also initialized in `show()`. If ordering is wrong, `codeEditor` can be null. The guard `if (codeEditor == null) return null;` prevents NPE.

4. **Blank code must NOT be sent to late-joining students.** The supplier returns `null` when `code.isBlank()` — `addClient()` already null-checks the supplier's return value before sending. Do not remove that null check.

5. **`codeEditor.getText()` is called on a background thread** (the `addClient()` supplier is invoked from the accept-loop thread). JavaFX TextArea `getText()` is documented as thread-safe for reads, so this is acceptable. Do NOT add `Platform.runLater` here — it would create an async supplier which cannot return a value.

6. **Tab ordering matters for UX.** Always add tabs in order: `whiteboardTab`, `pptTab`, `codeTab`. The tab indices are never used programmatically (selection is always by Tab object reference), so reordering won't break logic — but keep it consistent with the student side.

7. **`setManaged(false)` is required alongside `setVisible(false)`** for the toolbars to collapse properly in JavaFX VBox layout. Using only `setVisible(false)` leaves an empty space where the toolbar was.

8. **Import `com.classroom.model.CodeData`** in both `TeacherUI.java` and `StudentUI.java`. Missing import is the most common compile error.

9. **Do NOT add RichTextFX or any new Maven dependency.** Plain `TextArea` with monospace font is the specified approach. No `pom.xml` changes are needed for Phase 4.

10. **`codeViewer` in `StudentUI` must be `setEditable(false)`** so students cannot accidentally type in it. The setting must be applied before the tab is built.

---

## H. Reused Patterns from Prior Phases

| Pattern | Where Defined | How Phase 4 Reuses It |
|---------|---------------|----------------------|
| `getActivePane()` / `getActiveSender()` routing | `TeacherUI` Phase 3 | Extended with `codeTab` null-return branch |
| 3-supplier pattern in `TeacherServer` | Phase 2 + 3 | 4th supplier added identically |
| Two-step wiring (`setServer()`, `setClient()`) | Phase 1 | Not needed again — no new wiring entry point |
| `setManaged()` + `setVisible()` for layout | Phase 3 (toolbars) | Applied to hide toolbars on code tab |
| Auto-tab switch on content arrival | Phase 3 (PPT_SLIDE) | `CODE_SHARE` triggers `tabPane.getSelectionModel().select(codeTab)` |
| Late-join sync via supplier + `addClient()` | Phase 2 + 3 | Same pattern, 4th block added |

---

## I. What Phase 5 Will Need From This Phase

Phase 5 (Integration, Error Handling, Polish) will read the `Latest_Updates.md` from Phase 4 and may need:
- Knowledge of the final `codeTab` field name and `codeViewer` field name
- Confirmation that `codeStateSupplier` clears correctly when a new session starts (teacher restarts server)
- The toolbar visibility state when switching between all 3 tabs
