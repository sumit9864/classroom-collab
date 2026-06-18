package com.classroom.server;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.util.NetworkUtil;
import javafx.application.Platform;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class TeacherServer {

    // ── Configurable capacity constants ───────────────────────────────────────
    /**
     * Maximum number of concurrent ClientHandler threads.
     * Set slightly above the expected max class size so a burst of simultaneous
     * reconnects is absorbed by the pool queue rather than spawning unlimited threads.
     */
    public static final int MAX_HANDLER_THREADS = 60;

    private final int port;
    private ServerSocket serverSocket;
    private final List<ClientHandler> clients;  // synchronized list
    private volatile boolean running;
    private final Runnable onClientListChanged; // UI callback — called on every join/leave
    private Supplier<Message> stateSupplier;       // provides FULL_STATE snapshot for whiteboard
    private Supplier<Message> pptStateSupplier;    // provides current PPT slide
    private Supplier<Message> pptWhiteboardStateSupplier; // provides FULL_STATE for PPT whiteboard
    private Supplier<Message> codeStateSupplier;  // provides last shared CodeData for late-join

    // Async broadcast infrastructure
    private final LinkedBlockingQueue<Message> dispatchQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<String, AtomicReference<Message>> latestProgressMap = new ConcurrentHashMap<>();
    private Thread dispatchThread;
    private Thread heartbeatThread;

    /**
     * Bounded thread pool for ClientHandler instances.
     * Capped at MAX_HANDLER_THREADS to prevent unbounded thread creation on repeated
     * or rapid reconnections. All threads are daemon threads so they do not prevent
     * JVM shutdown when the teacher closes the session.
     */
    private final ExecutorService handlerPool = Executors.newFixedThreadPool(
        MAX_HANDLER_THREADS,
        r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }
    );

    public TeacherServer(int port, Runnable onClientListChanged) {
        this.port = port;
        this.onClientListChanged = onClientListChanged;
        this.clients = Collections.synchronizedList(new ArrayList<>());
    }

    /** Set after construction, once the WhiteboardPane exists in TeacherUI. */
    public void setStateSupplier(Supplier<Message> stateSupplier) {
        this.stateSupplier = stateSupplier;
    }

    /** Set after construction, once PptService is initialised in TeacherUI. */
    public void setPptStateSupplier(Supplier<Message> pptStateSupplier) {
        this.pptStateSupplier = pptStateSupplier;
    }

    public void setPptWhiteboardStateSupplier(Supplier<Message> pptWhiteboardStateSupplier) {
        this.pptWhiteboardStateSupplier = pptWhiteboardStateSupplier;
    }

    /** Set after construction, once codeEditor TextArea exists in TeacherUI. */
    public void setCodeStateSupplier(Supplier<Message> codeStateSupplier) {
        this.codeStateSupplier = codeStateSupplier;
    }

    /**
     * Opens a ServerSocket and starts a daemon accept-loop thread.
     * Each accepted connection spawns a new ClientHandler thread.
     */
    public void start() throws IOException {
        try {
            serverSocket = new ServerSocket(port);
        } catch (BindException e) {
            throw new IOException("Port " + port + " is already in use. Choose a different port.", e);
        }
        running = true;
        Thread acceptThread = new Thread(() -> {
            System.out.println("[TeacherServer] Listening on port " + port
                    + " (max clients: " + MAX_HANDLER_THREADS + ")");
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    // Submit to the bounded pool — replaces raw Thread spawning.
                    // If the pool is at capacity the new Runnable queues internally;
                    // it does NOT block this accept-loop thread.
                    handlerPool.submit(new ClientHandler(clientSocket, this));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[TeacherServer] Accept error: " + e.getMessage());
                    }
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();

        dispatchThread = new Thread(() -> {
            while (running || !dispatchQueue.isEmpty()) {
                // Priority 1: drain all pending latest-value progress messages first.
                // This ensures in-progress strokes and shape drags are never stale.
                // Iterator-based traversal allows pruning entries whose AtomicReference
                // is permanently null (left over from completed shape drags). Without
                // pruning, these dead entries accumulate over a long session.
                Iterator<Map.Entry<String, AtomicReference<Message>>> it =
                        latestProgressMap.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, AtomicReference<Message>> entry = it.next();
                    Message m = entry.getValue().getAndSet(null);
                    if (m != null) {
                        doSendAll(m);
                    } else {
                        // Entry has been null since the last drain cycle — prune it.
                        it.remove();
                    }
                }
                // Priority 2: drain one regular queued message, waiting up to 1 ms if queue is
                // empty. 1 ms keeps the loop responsive during active drawing (5 ms caused
                // up to 5 ms idle jitter between progress-message cycles) while still yielding
                // the CPU when the board is idle.
                try {
                    Message msg = dispatchQueue.poll(1, TimeUnit.MILLISECONDS);
                    if (msg != null) doSendAll(msg);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        dispatchThread.setDaemon(true);
        dispatchThread.setName("broadcast-dispatch");
        dispatchThread.start();

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
    }

    /**
     * Gracefully shuts down the server.
     * Sends DISCONNECT synchronously BEFORE interrupting the dispatch thread,
     * so all connected students receive the message before the thread exits.
     */
    public void stop() {
        running = false;
        // Send DISCONNECT directly — bypass the queue, dispatch thread is about to be interrupted
        doSendAll(new Message(MessageType.DISCONNECT, null, "Teacher"));
        if (dispatchThread  != null) dispatchThread.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("[TeacherServer] Error closing server socket: " + e.getMessage());
        }
    }

    /**
     * Sends a message to all connected clients.
     * Each client's OOS is locked individually (synchronized on the OOS object) so
     * this can run safely in parallel with ClientHandler.send() which locks the same
     * object. Without this, addClient()'s state-snapshot sends and the dispatch thread's
     * doSendAll() would race on the same ObjectOutputStream, corrupting the stream and
     * causing the student to see an unexpected disconnect.
     * Called only from the dispatch thread — never from the FX thread.
     */
    private void doSendAll(Message msg) {
        List<ClientHandler> snapshot;
        synchronized (clients) {
            snapshot = new ArrayList<>(clients);
        }
        List<ClientHandler> failed = new ArrayList<>();
        for (ClientHandler client : snapshot) {
            ObjectOutputStream oos = client.getOutputStream();
            if (oos == null) continue;
            synchronized (oos) {
                try {
                    NetworkUtil.sendMessage(oos, msg);
                } catch (Exception e) {
                    System.err.println("[TeacherServer] Send failed for "
                            + client.getStudentName() + ": " + e.getMessage());
                    failed.add(client);
                }
            }
        }
        if (!failed.isEmpty()) {
            synchronized (clients) { clients.removeAll(failed); }
            Platform.runLater(onClientListChanged);
        }
    }

    /**
     * Enqueues a message for async broadcast to all students.
     * Non-blocking — safe to call from the JavaFX Application Thread.
     */
    public void broadcast(Message msg) {
        dispatchQueue.offer(msg);
    }

    /**
     * Enqueues a message using a latest-value pattern.
     * If a pending message with the same key already exists, it is atomically replaced.
     * Non-blocking — safe to call from the JavaFX Application Thread.
     *
     * @param key A string key identifying the slot (e.g. "STROKE_PROGRESS_Teacher").
     * @param msg The message to send.
     */
    public void broadcastLatest(String key, Message msg) {
        latestProgressMap
            .computeIfAbsent(key, k -> new AtomicReference<>())
            .set(msg);
    }

    /**
     * Adds a new ClientHandler after successful authentication.
     * Sends a full-state snapshot ONLY to this new client, then broadcasts the updated student list.
     */
    public void addClient(ClientHandler handler) {
        // 1. Send whiteboard full state to this student only (existing)
        if (stateSupplier != null) {
            try {
                Message stateMsg = stateSupplier.get();
                if (stateMsg != null) handler.send(stateMsg);
            } catch (Exception e) {
                System.err.println("[TeacherServer] Failed to send whiteboard state to " + handler.getStudentName() + ": " + e.getMessage());
            }
        }

        // 2. Send current PPT slide to this student only
        if (pptStateSupplier != null) {
            try {
                Message pptMsg = pptStateSupplier.get();
                if (pptMsg != null) handler.send(pptMsg);
            } catch (Exception e) {
                System.err.println("[TeacherServer] Failed to send PPT state to " + handler.getStudentName() + ": " + e.getMessage());
            }
        }

        // 3. Send PPT whiteboard full state to this student only
        if (pptWhiteboardStateSupplier != null) {
            try {
                Message pptFullStateMsg = pptWhiteboardStateSupplier.get();
                if (pptFullStateMsg != null) handler.send(pptFullStateMsg);
            } catch (Exception e) {
                System.err.println("[TeacherServer] Failed to send PPT whiteboard state to " + handler.getStudentName() + ": " + e.getMessage());
            }
        }

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

        // 5. Add to broadcast list and notify UI (existing)
        clients.add(handler);
        broadcastStudentList();
        Platform.runLater(onClientListChanged);
    }

    /**
     * Removes a ClientHandler on disconnect,
     * then broadcasts the updated student list and notifies the UI.
     */
    public void removeClient(ClientHandler handler) {
        clients.remove(handler);
        broadcastStudentList();
        Platform.runLater(onClientListChanged);
    }

    /**
     * Builds the current list of student names and broadcasts it as STUDENT_LIST_UPDATE.
     */
    public void broadcastStudentList() {
        List<String> names = getConnectedNames();
        broadcast(new Message(MessageType.STUDENT_LIST_UPDATE, names, "Teacher"));
    }

    /**
     * Returns a snapshot of connected student names for UI display.
     */
    public List<String> getConnectedNames() {
        List<String> names = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler c : clients) {
                names.add(c.getStudentName());
            }
        }
        return names;
    }

    /** Returns the port this server is listening on. */
    public int getPort() { return port; }
}
