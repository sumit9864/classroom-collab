# Classroom Collaboration

A LAN-based real-time classroom collaboration desktop app where a teacher hosts a session and students join over the local network — no internet, no cloud, no accounts required.

---

## Features

- **Session Join & Authentication** — Students connect using the teacher's local IP address and a display name. An authentication handshake confirms the connection before any content is shared.
- **Shared Whiteboard** — A real-time freehand drawing canvas visible to all students. Supports pen colors, stroke widths, eraser, undo/redo, and a separate annotation overlay layer.
- **Shape & Object Tools** — Draw rectangles, ellipses, lines, arrows, and text boxes directly on the whiteboard. Shapes are selectable, movable, and resizable.
- **PowerPoint Slide Sharing** — Teacher loads a `.pptx` file which is rendered server-side and broadcast to all students as high-resolution slide images. Supports forward/backward navigation and per-slide annotations that persist when switching slides.
- **PPT Export with Markings** — Teacher can export the annotated presentation as a new `.pptx` file with all whiteboard drawings baked in as native PowerPoint shapes.
- **Real-Time Code Sharing** — A shared code editor panel where the teacher can type and broadcast code snippets live to all students.
- **File Sharing** — Teacher can select and send one or more files of any type to all connected students simultaneously. Students can save files individually or download everything as a ZIP archive.
- **Live Student List** — The teacher sees a live roster of all connected students, updated automatically as students join or leave.
- **Light / Dark Theme** — Toggle between a light and dark UI theme at any time during the session.

---

## Tech Stack

| Component         | Technology                              |
|-------------------|-----------------------------------------|
| Language          | Java 17                                 |
| Build Tool        | Apache Maven 3.9.14                     |
| UI Framework      | JavaFX 17.0.13                          |
| Networking        | Java TCP Sockets (`java.net`)           |
| Serialization     | Java Object Serialization (`java.io`)   |
| PPT Parsing       | Apache POI 5.3.0 (`poi-ooxml`)          |
| Testing           | JUnit Jupiter 5.10.2                    |

---

## Architecture Overview

The app uses a direct TCP server/client model entirely within the local network. The teacher's machine runs a `ServerSocket` that accepts one TCP connection per student, each handled by a dedicated `ClientHandler` thread. All data — whiteboard strokes, shapes, slide images, code, and files — is wrapped in serialized `Message` objects containing a `MessageType` enum and a typed payload, then dispatched via a prioritized async broadcast queue. Students are purely receive-only; they connect as clients, receive a full state snapshot on join, and then receive incremental updates in real time. All state is held in memory for the duration of the session and is discarded when the session ends.

---

## Prerequisites

- **JDK 17** or later (must be on `PATH`)
- **Apache Maven 3.6+** (must be on `PATH`)
- Teacher and student machines must be on the **same LAN or subnet** (e.g. the same Wi-Fi network or wired switch). The teacher's machine firewall must allow inbound TCP connections on the chosen port.

---

## Build

Clone the repository and run:

```bash
mvn package
```

This compiles all sources, runs the test suite, and produces a self-contained executable fat JAR at:

```
target/classroom-collab-1.0-SNAPSHOT.jar
```

To only compile and run tests without packaging:

```bash
mvn test
```

---

## Run

Both the teacher and students use the **same JAR**. The role (Teacher or Student) is selected on the login screen at startup.

### Launch the application

```bash
java -jar target/classroom-collab-1.0-SNAPSHOT.jar
```

> **Note:** Because JavaFX modules are bundled in the fat JAR via the Shade plugin, no separate JavaFX SDK installation is required.

### Teacher (Host a Session)

1. Launch the JAR.
2. Select **Teacher — Host a Session** on the login screen.
3. Enter a port number (default: **5000**). The port must be unused and open on your firewall.
4. Click **Start Session**.
5. Share your local IP address (shown via `ipconfig` / `ifconfig`) with students.

### Student (Join a Session)

1. Launch the JAR on the student's machine.
2. Select **Student — Join a Session** on the login screen.
3. Enter:
   - **Teacher's IP Address** — the local IP of the teacher's machine (e.g. `192.168.1.5`)
   - **Port** — must match the port the teacher started on (default: **5000**)
   - **Your Name** — a display name visible to the teacher. The names `Teacher` and `Teacher_PPT` are reserved and cannot be used.
4. Click **Connect to Classroom**.

---

## Project Structure

```
src/main/java/com/classroom/
│
├── Main.java                   Entry point — launches the JavaFX application
│
├── server/                     Teacher-side TCP server
│   ├── TeacherServer.java      Manages all connections, prioritized broadcast queues
│   └── ClientHandler.java      Per-student connection handler with its own send queue
│
├── client/                     Student-side TCP client
│   └── StudentClient.java      Connects to the teacher and receives incoming messages
│
├── ui/                         All JavaFX user interface classes
│   ├── LoginScreen.java        Role selection and connection screen
│   ├── TeacherUI.java          Full teacher interface (whiteboard, PPT, code, files)
│   ├── StudentUI.java          Full student interface (view-only + file saving)
│   └── WhiteboardPane.java     Shared canvas component used by both teacher and student
│
├── model/                      Serializable data transfer objects
│   ├── Message.java            Envelope for all network communication (type + payload)
│   ├── MessageType.java        Enum of all message types (STROKE, SHAPE, FILE_CHUNK, etc.)
│   ├── StrokeData.java         Freehand stroke points, color, width
│   ├── ShapeData.java          Geometric shape definitions (rect, ellipse, line, etc.)
│   ├── SlideData.java          Rendered PPT slide as PNG byte array
│   ├── CodeData.java           Code snippet text payload
│   └── FileShareData.java      File transfer metadata and chunk payload
│
└── util/                       Shared utilities
    ├── NetworkUtil.java        ObjectStream helpers and deserialization security filter
    └── PptService.java         .pptx loading, slide rendering, and PPT export
```

---

## Known Limitations

These are intentional design constraints, not bugs:

- **LAN only** — There is no relay server, NAT traversal, or internet connectivity. All participants must be on the same local network.
- **No audio or video** — The app is text, drawing, and file based. Screen sharing and video conferencing are out of scope.
- **No persistent storage** — All session data (whiteboard state, chat, shared files) exists only in memory for the duration of the session. Nothing is written to a database or saved automatically.
- **Single active session** — The teacher application supports one session at a time. Restarting the app starts a fresh session with no prior state.
- **Students are receive-only** — Students cannot draw on the whiteboard or send content back to the teacher. All content flows from teacher to students.
