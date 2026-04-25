# Project Context — LAN-Based Classroom Collaboration System

## Purpose & Constraints
A Java desktop app where a teacher (host) shares educational content with students over a local Ethernet/LAN network in real time.
- LAN only — no internet, no cloud
- No audio/video streaming
- No persistent database — all state is in-memory, session-scoped
- Single developer, 5-day build with Antigravity assistance

## Tech Stack (FIXED — do not change versions)
| Component       | Technology                  | Version   |
|-----------------|-----------------------------|-----------|
| Language        | Java                        | 17.0.18   |
| Build Tool      | Apache Maven                | 3.9.14    |
| UI Framework    | JavaFX                      | 17.0.13   |
| Networking      | Java Socket API (TCP)       | Built-in  |
| PPT Parsing     | Apache POI (XSLF)           | 5.3.0     |
| Serialization   | Java Object Streams         | Built-in  |
| Testing         | JUnit                       | 5.10.2    |

## Modules
1. **Authentication** — Teacher hosts a TCP server; students join via teacher IP + name
2. **Whiteboard** — Shared JavaFX Canvas; teacher strokes broadcast to all students
3. **PPT Sharing** — Teacher loads .pptx; POI renders slides as images, synced to clients
4. **Annotation** — Teacher draws/highlights overlaid on any active content
5. **Code Sharing** — Teacher types/pastes code with real-time auto-sync (debounced CODE_SHARE message); students receive in read-only dark-theme TextArea with strict 4-space tabs; late-join sync via codeStateSupplier

## Phase Summary
- **Phase 1** — Maven setup, TCP server/client, authentication, session UI
- **Phase 2** — Whiteboard canvas + annotation layer
- **Phase 3** — PPT loading, slide rendering, navigation sync
- **Phase 4** — Real-time Code Sharing panel with auto-sync, non-blocking async Server broadcast queue, live STROKE_PROGRESS and shape previews
- **Phase 5** — Integration, error handling, UI polish, final JAR build

## Networking Model
- Teacher = TCP ServerSocket (configurable port, default 5000)
- Students = TCP Socket clients connecting to teacher's LAN IP
- All communication via serialized `Message` objects (type + payload)
- One `ClientHandler` thread per connected student on teacher side

## Top-Level Folder Structure
```
classroom-collab/
  pom.xml
  src/main/java/com/classroom/
    Main.java
    server/        (TeacherServer, ClientHandler)
    client/        (StudentClient)
    ui/            (TeacherUI, StudentUI, LoginScreen)
    model/         (Message, MessageType)
    util/          (NetworkUtil)
  src/main/resources/
  src/test/java/com/classroom/
```

## Key Design Decisions
- **TCP over UDP** — Reliable, ordered delivery required for slide and code sync
- **JavaFX** — Native Java UI; no extra runtime; works cross-platform
- **Apache POI XSLF** — Standard library for .pptx parsing without LibreOffice dependency
- **Java Object Streams** — Zero-dependency serialization for Message passing
