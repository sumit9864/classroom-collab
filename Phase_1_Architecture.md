# Phase 1 Architecture — Foundation & Authentication

---

## A. pom.xml (Complete Content)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.classroom</groupId>
  <artifactId>classroom-collab</artifactId>
  <version>1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <javafx.version>17.0.13</javafx.version>
  </properties>

  <dependencies>

    <!-- JavaFX -->
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-controls</artifactId>
      <version>${javafx.version}</version>
    </dependency>
    <dependency>
      <groupId>org.openjfx</groupId>
      <artifactId>javafx-fxml</artifactId>
      <version>${javafx.version}</version>
    </dependency>

    <!-- Apache POI for .pptx (used in Phase 3, declared now) -->
    <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>
      <version>5.3.0</version>
    </dependency>

    <!-- JUnit 5 -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>

  </dependencies>

  <build>
    <plugins>

      <!-- Compiler Plugin -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <source>17</source>
          <target>17</target>
        </configuration>
      </plugin>

      <!-- JavaFX Maven Plugin -->
      <plugin>
        <groupId>org.openjfx</groupId>
        <artifactId>javafx-maven-plugin</artifactId>
        <version>0.0.8</version>
        <configuration>
          <mainClass>com.classroom.Main</mainClass>
          <options>
            <option>--add-opens</option>
            <option>java.base/java.lang=ALL-UNNAMED</option>
            <option>--add-opens</option>
            <option>java.base/java.io=ALL-UNNAMED</option>
          </options>
        </configuration>
      </plugin>

      <!-- Surefire for JUnit 5 -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.1.2</version>
      </plugin>

      <!-- Shade plugin for runnable fat JAR (Phase 5 final build) -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation=
                  "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.classroom.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>

    </plugins>
  </build>
</project>
```

---

## B. Complete Folder & File Structure

```
classroom-collab/
│
├── pom.xml                                         — Maven build config
│
└── src/
    ├── main/
    │   ├── java/com/classroom/
    │   │   │
    │   │   ├── Main.java                           — JavaFX Application entry point; shows LoginScreen
    │   │   │
    │   │   ├── model/
    │   │   │   ├── Message.java                    — Serializable envelope for all network messages
    │   │   │   └── MessageType.java                — Enum of all message type constants
    │   │   │
    │   │   ├── util/
    │   │   │   └── NetworkUtil.java                — Static helpers for reading/writing Message objects on sockets
    │   │   │
    │   │   ├── server/
    │   │   │   ├── TeacherServer.java              — Opens ServerSocket; spawns ClientHandler per student
    │   │   │   └── ClientHandler.java              — Reads messages from one student; broadcasts to all
    │   │   │
    │   │   ├── client/
    │   │   │   └── StudentClient.java              — Connects to teacher; sends/receives messages; notifies UI
    │   │   │
    │   │   └── ui/
    │   │       ├── LoginScreen.java                — Role-selection screen (Host / Join)
    │   │       ├── TeacherUI.java                  — Teacher's main window; lists connected students
    │   │       └── StudentUI.java                  — Student's main window; shows connection status
    │   │
    │   └── resources/                              — Static assets (empty for Phase 1)
    │
    └── test/
        └── java/com/classroom/
            └── NetworkUtilTest.java                — JUnit tests for NetworkUtil read/write round-trip
```

---

## C. Step-by-Step Implementation Instructions

### Step 1 — Maven Project & pom.xml
**Create:** `pom.xml` at project root using the exact content in Section A.  
**Verify:** Run `mvn clean compile`. Must exit with BUILD SUCCESS and zero errors.  
**Do not proceed** if there are dependency resolution errors — check internet connectivity and exact versions.

---

### Step 2 — Model Classes
**Create:** `src/main/java/com/classroom/model/MessageType.java`  
**Create:** `src/main/java/com/classroom/model/Message.java`  
**Verify:** Run `mvn clean compile`. Both classes must compile cleanly.

---

### Step 3 — NetworkUtil
**Create:** `src/main/java/com/classroom/util/NetworkUtil.java`  
**Verify:** Run `mvn clean compile`.

---

### Step 4 — Server Classes
**Create:** `src/main/java/com/classroom/server/TeacherServer.java`  
**Create:** `src/main/java/com/classroom/server/ClientHandler.java`  
**Verify:** Run `mvn clean compile`.

---

### Step 5 — Student Client
**Create:** `src/main/java/com/classroom/client/StudentClient.java`  
**Verify:** Run `mvn clean compile`.

---

### Step 6 — LoginScreen UI
**Create:** `src/main/java/com/classroom/ui/LoginScreen.java`  
**Verify:** Run `mvn javafx:run`. The login window must appear. No exceptions in console.

---

### Step 7 — TeacherUI
**Create:** `src/main/java/com/classroom/ui/TeacherUI.java`  
**Verify:** Clicking "Host Session" on the login screen must open TeacherUI. Connected-student list panel must be visible (empty on first open).

---

### Step 8 — StudentUI
**Create:** `src/main/java/com/classroom/ui/StudentUI.java`  
**Verify:** Clicking "Join Session" on the login screen must open StudentUI. Status label must show "Connecting…" and then "Connected" or an error.

---

### Step 9 — Main Entry Point
**Create:** `src/main/java/com/classroom/Main.java`  
**Verify:** Run `mvn javafx:run`. App launches to LoginScreen without exception.

---

### Step 10 — Integration Test
**Run two instances** of the app on localhost (or two machines on the same LAN).  
- Instance 1: Host Session on port 5000.  
- Instance 2: Join Session with IP `127.0.0.1`, port `5000`, name `"TestStudent"`.  
**Verify:** TeacherUI shows `"TestStudent"` in the connected students list.  
**Log:** Mark all steps ✅ in `Latest_Updates.md`.

---

## D. File Specifications

### `model/MessageType.java`
```
package com.classroom.model;

public enum MessageType {
    AUTH_REQUEST,       // Student → Teacher: join request with student name
    AUTH_SUCCESS,       // Teacher → Student: join accepted
    AUTH_FAIL,          // Teacher → Student: join rejected
    STUDENT_LIST_UPDATE,// Teacher → All: updated list of connected student names
    DISCONNECT,         // Either direction: graceful disconnect notice
    HEARTBEAT           // Either direction: keep-alive ping (Phase 5 use)
}
```

---

### `model/Message.java`
```
package com.classroom.model;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private MessageType type;       // What kind of message this is
    private Object payload;         // Data carried by the message (String, List, byte[], etc.)
    private String senderName;      // Name of sender (student name or "Teacher")

    // Constructor: Message(MessageType type, Object payload, String senderName)
    // Getters: getType(), getPayload(), getSenderName()
    // No setters — Message is immutable after construction
}
```

---

### `util/NetworkUtil.java`
```
package com.classroom.util;

import com.classroom.model.Message;
import java.io.*;
import java.net.Socket;

public class NetworkUtil {

    // sendMessage(ObjectOutputStream out, Message msg)
    //   Writes msg to out, calls out.flush(), wraps in try-catch IOException
    //   Must call out.reset() before writeObject to prevent stale cache issues

    // readMessage(ObjectInputStream in) → Message
    //   Reads and returns one Message from in
    //   Returns null if stream is closed or EOFException is caught
    //   Wraps all exceptions in try-catch; logs to System.err

    // createOutputStream(Socket socket) → ObjectOutputStream
    //   Creates and returns ObjectOutputStream from socket.getOutputStream()
    //   Flushes immediately after creation (required before creating paired OIS)

    // createInputStream(Socket socket) → ObjectInputStream
    //   Creates and returns ObjectInputStream from socket.getInputStream()
    //   Must be called AFTER createOutputStream on the same socket to avoid deadlock
}
```
> **Critical:** Always create `ObjectOutputStream` before `ObjectInputStream` on both ends to avoid a mutual blocking deadlock on stream header exchange.

---

### `server/TeacherServer.java`
```
package com.classroom.server;

import com.classroom.model.*;
import com.classroom.util.NetworkUtil;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class TeacherServer {

    private int port;
    private ServerSocket serverSocket;
    private List<ClientHandler> clients;          // synchronized list
    private volatile boolean running;
    private Runnable onClientListChanged;          // UI callback — called on every join/leave

    // Constructor: TeacherServer(int port, Runnable onClientListChanged)

    // start()
    //   Opens ServerSocket on this.port
    //   Sets running = true
    //   Launches a daemon Thread that loops: accept() → new ClientHandler(socket, this) → thread.start()
    //   Loop exits when running = false

    // stop()
    //   Sets running = false
    //   Broadcasts DISCONNECT message to all clients
    //   Closes serverSocket

    // broadcast(Message msg)
    //   Synchronized on clients list
    //   Iterates clients, calls NetworkUtil.sendMessage for each
    //   Removes client from list if send fails (broken pipe)

    // addClient(ClientHandler handler)
    //   Adds to synchronized clients list
    //   Calls broadcastStudentList()
    //   Calls onClientListChanged.run() via Platform.runLater (UI thread safety)

    // removeClient(ClientHandler handler)
    //   Removes from synchronized clients list
    //   Calls broadcastStudentList()
    //   Calls onClientListChanged.run() via Platform.runLater

    // broadcastStudentList()
    //   Builds List<String> of student names from clients
    //   Broadcasts a STUDENT_LIST_UPDATE Message with that list as payload

    // getConnectedNames() → List<String>
    //   Returns snapshot of student names for UI display
}
```

---

### `server/ClientHandler.java`
```
package com.classroom.server;

import com.classroom.model.*;
import com.classroom.util.NetworkUtil;
import java.net.Socket;
import java.io.*;

public class ClientHandler implements Runnable {

    private Socket socket;
    private TeacherServer server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String studentName;

    // Constructor: ClientHandler(Socket socket, TeacherServer server)
    //   Stores socket and server reference
    //   Initializes out and in via NetworkUtil (out FIRST, then in)

    // run()
    //   First message received must be AUTH_REQUEST
    //   Extract studentName from message.getSenderName()
    //   Send AUTH_SUCCESS back to this client
    //   Call server.addClient(this)
    //   Enter read loop: call NetworkUtil.readMessage(in)
    //     If null → break (client disconnected)
    //     Otherwise → handle(message) [stub for future phases]
    //   On exit: call server.removeClient(this), close streams

    // send(Message msg)
    //   Calls NetworkUtil.sendMessage(out, msg)
    //   If IOException: log to System.err

    // getStudentName() → String

    // handle(Message msg)
    //   Switch on msg.getType()
    //   DISCONNECT → break out of run() loop
    //   All other types → log "Unhandled: " + type (future phases fill this in)
}
```

---

### `client/StudentClient.java`
```
package com.classroom.client;

import com.classroom.model.*;
import com.classroom.util.NetworkUtil;
import java.net.Socket;
import java.io.*;
import java.util.function.Consumer;

public class StudentClient {

    private String host;
    private int port;
    private String studentName;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running;
    private Consumer<Message> onMessageReceived;    // UI callback

    // Constructor: StudentClient(String host, int port, String studentName,
    //                            Consumer<Message> onMessageReceived)

    // connect()
    //   Opens socket to host:port
    //   Creates out (NetworkUtil.createOutputStream), then in
    //   Sends AUTH_REQUEST Message with payload=null, senderName=studentName
    //   Reads first response — must be AUTH_SUCCESS; throws Exception if AUTH_FAIL
    //   Starts listener thread (daemon)

    // startListenerThread()
    //   Daemon Thread: loops calling NetworkUtil.readMessage(in)
    //   If null → disconnect()
    //   Else → calls onMessageReceived.accept(msg) via Platform.runLater

    // disconnect()
    //   Sets running = false
    //   Sends DISCONNECT message if stream still open
    //   Closes socket and streams

    // isConnected() → boolean
    //   Returns socket != null && socket.isConnected() && !socket.isClosed()
}
```

---

### `ui/LoginScreen.java`
```
package com.classroom.ui;

import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class LoginScreen {

    // buildScene(Stage primaryStage) → Scene
    //   Layout: VBox centered, spacing 15
    //
    //   Title label: "Classroom Collaboration"
    //
    //   Role selection: two RadioButtons in a ToggleGroup
    //     "Host a Session" (Teacher)
    //     "Join a Session" (Student)
    //
    //   Dynamic form area — shown fields depend on selected role:
    //     Host selected:
    //       Label + TextField portField (default "5000")
    //     Join selected:
    //       Label + TextField ipField (placeholder "Teacher's IP")
    //       Label + TextField portField (default "5000")
    //       Label + TextField nameField (placeholder "Your name")
    //
    //   Button: "Start" / "Connect"
    //     If Host:
    //       Parse port from portField
    //       Create TeacherServer(port, callback)
    //       Call server.start()
    //       Open TeacherUI(primaryStage, server)
    //     If Join:
    //       Validate ip, port, name not empty
    //       Create StudentClient(ip, port, name, messageHandler)
    //       Call client.connect() — wrap in try-catch, show Alert on failure
    //       Open StudentUI(primaryStage, client)
    //
    //   Error handling:
    //     Show Alert.AlertType.ERROR for invalid port, empty name, or connection failure
}
```

---

### `ui/TeacherUI.java`
```
package com.classroom.ui;

import com.classroom.server.TeacherServer;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.util.List;

public class TeacherUI {

    private Stage stage;
    private TeacherServer server;
    private ListView<String> studentListView;   // Displays connected student names

    // Constructor: TeacherUI(Stage stage, TeacherServer server)

    // show()
    //   Build and display the teacher's main window
    //   Layout: BorderPane
    //     TOP: HBox with label "LAN Classroom — Teacher" and "Stop Session" button
    //     LEFT: VBox with label "Connected Students" and studentListView (width 200)
    //     CENTER: StackPane placeholder labeled "Content Area — Phases 2–4"
    //   "Stop Session" button: calls server.stop(), closes stage
    //   Refresh student list by calling refreshStudentList() on window open and after each join/leave

    // refreshStudentList()
    //   Calls server.getConnectedNames()
    //   Updates studentListView.getItems() — must run on JavaFX thread (Platform.runLater)
    //   This method is passed as the onClientListChanged callback to TeacherServer
}
```

---

### `ui/StudentUI.java`
```
package com.classroom.ui;

import com.classroom.client.StudentClient;
import com.classroom.model.Message;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class StudentUI {

    private Stage stage;
    private StudentClient client;
    private Label statusLabel;      // Shows "Connected to [IP]" or error state

    // Constructor: StudentUI(Stage stage, StudentClient client)

    // show()
    //   Layout: BorderPane
    //     TOP: HBox with label "LAN Classroom — Student" and "Disconnect" button
    //     CENTER: StackPane with statusLabel centered, text "Connected to [host:port]"
    //   "Disconnect" button: calls client.disconnect(), closes stage
    //   On window close (setOnCloseRequest): calls client.disconnect()

    // handleMessage(Message msg)
    //   Called by StudentClient's onMessageReceived callback (already on FX thread)
    //   Switch on msg.getType():
    //     STUDENT_LIST_UPDATE → (no UI change needed in Phase 1; log it)
    //     DISCONNECT → show Alert "Session ended by teacher", close stage
    //     All others → log "Unhandled: " + type (future phases fill this in)
}
```

---

### `Main.java`
```
package com.classroom;

import com.classroom.ui.LoginScreen;
import javafx.application.Application;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Classroom Collaboration");
        primaryStage.setScene(new LoginScreen().buildScene(primaryStage));
        primaryStage.setWidth(480);
        primaryStage.setHeight(360);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

---

### `test/NetworkUtilTest.java`
```
package com.classroom;

import com.classroom.model.*;
import com.classroom.util.NetworkUtil;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;

public class NetworkUtilTest {

    // test_sendAndReceive_roundtrip()
    //   Open a ServerSocket on a random port
    //   In a background thread: accept one connection, read a Message, assert type == AUTH_REQUEST
    //   In main thread: connect a Socket, send AUTH_REQUEST Message
    //   Join thread, assert no exceptions
    //   Verifies full ObjectStream serialization round-trip works correctly

    // test_readMessage_returnsNull_onClosedStream()
    //   Pipe a closed InputStream into ObjectInputStream
    //   Assert NetworkUtil.readMessage() returns null (does not throw)
}
```

---

## E. Error Prevention Checklist

| Risk | Rule |
|------|------|
| JavaFX launch | Only `Main.java` extends `Application`. Never call `new Stage()` outside the FX thread. |
| Stream ordering deadlock | Always create `ObjectOutputStream` before `ObjectInputStream` on BOTH server and client sides. |
| Stale object cache | Call `out.reset()` before every `out.writeObject()` in `NetworkUtil.sendMessage`. |
| UI from background thread | All UI mutations (label, list updates) must be wrapped in `Platform.runLater()`. |
| Stream not flushed | Call `out.flush()` immediately after every `writeObject`. |
| ClientHandler list race condition | Synchronize all access to `List<ClientHandler> clients` in `TeacherServer`. |
| Socket not closed on error | Use try-with-resources or finally blocks in all socket code. |
| Port already in use | Catch `BindException` in `TeacherServer.start()` and surface a meaningful error to UI. |
| JavaFX module path | The `javafx-maven-plugin` handles module path automatically — do NOT add `module-info.java` unless all dependencies are modular. |
| POI dependency unused in Phase 1 | Declared in pom.xml now to avoid pom.xml edits mid-project. It will compile unused in Phase 1 — that is intentional. |
