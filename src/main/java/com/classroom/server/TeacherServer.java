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

    /**
     * Capacity of the high-priority general dispatch queue.
     * Covers all non-file messages: strokes, shapes, code, PPT nav, heartbeat, undo/redo.
     * 300 messages at ~1 KB average = ~300 KB peak queue RAM — negligible.
     * If the queue fills (e.g., extremely rapid code updates with 0 students reading),
     * broadcast() silently drops the message (offer() returns false) rather than OOM.
     */
    public static final int MAX_DISPATCH_QUEUE = 300;

    /**
     * Capacity of the low-priority file chunk queue.
     * Each FILE_CHUNK message carries a 256 KB byte[]. At 200 capacity: max 50 MB of
     * pending heap during large concurrent transfers. sendFileAsync() uses the BLOCKING
     * broadcastFileChunk() so file sender threads stall when this fills — preventing
     * the heap explosion that an unbounded queue causes with 5 concurrent large files.
     */
    public static final int MAX_FILE_CHUNK_QUEUE = 200;

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
    /**
     * HIGH priority queue: all non-file messages (strokes, shapes, code, PPT, tab sync,
     * heartbeat, undo/redo). Bounded to prevent OOM from runaway producers.
     * broadcast() uses non-blocking offer() — drops silently if full (extremely rare).
     */
    private final LinkedBlockingQueue<Message> dispatchQueue =
            new LinkedBlockingQueue<>(MAX_DISPATCH_QUEUE);

    /**
     * LOW priority queue: FILE_SHARE_START, FILE_CHUNK, FILE_SHARE_COMPLETE only.
     * Bounded — broadcastFileChunk() BLOCKS the file sender thread when full,
     * providing backpressure that prevents heap explosion during large concurrent transfers.
     * Drained one message per dispatch loop cycle after the high-priority queue is empty.
     */
    private final LinkedBlockingQueue<Message> fileChunkQueue =
            new LinkedBlockingQueue<>(MAX_FILE_CHUNK_QUEUE);

    /**
     * Semaphore used to wake the dispatch thread immediately when a new message
     * is enqueued in any of the three queues (latestProgressMap, dispatchQueue,
     * fileChunkQueue). Each enqueue releases one permit; the dispatch thread acquires
     * one permit per loop iteration. This replaces the old poll(1ms) busy-loop:
     * - Zero CPU spin when queues are empty.
     * - Sub-millisecond wake latency instead of up to 1ms polling jitter.
     */
    private final java.util.concurrent.Semaphore dispatchSignal =
            new java.util.concurrent.Semaphore(0);

    private final ConcurrentHashMap<String, AtomicReference<Message>> latestProgressMap =
            new ConcurrentHashMap<>();

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
            while (running || !dispatchQueue.isEmpty() || !fileChunkQueue.isEmpty()) {
                // Wait for a signal that something was enqueued (sub-ms wake latency).
                // tryAcquire drains any permits released while we were working, then
                // blocks up to 5ms so we don't spin 100% CPU when queues are empty.
                try {
                    dispatchSignal.tryAcquire(5, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // Priority 1: drain all pending latest-value progress messages.
                // These are always high-priority (stroke/shape drag previews).
                Iterator<Map.Entry<String, AtomicReference<Message>>> it =
                        latestProgressMap.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, AtomicReference<Message>> entry = it.next();
                    Message m = entry.getValue().getAndSet(null);
                    if (m != null) {
                        doSendAll(m);
                    } else {
                        it.remove(); // prune permanently-empty entries
                    }
                }

                // Priority 2: drain all pending high-priority general messages.
                // poll() is non-blocking so we empty the queue in one pass without
                // blocking on an empty queue; control falls to Priority 3 immediately.
                Message msg;
                while ((msg = dispatchQueue.poll()) != null) {
                    doSendAll(msg);
                }

                // Priority 3: send ONE file chunk (low priority).
                // Sending one per loop cycle interleaves file chunks with whiteboard
                // messages so stroke/shape updates are never fully starved by a
                // large file transfer.
                Message chunk = fileChunkQueue.poll();
                if (chunk != null) doSendAll(chunk);
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
     * Non-blocking (offer) — safe to call from the JavaFX Application Thread.
     * Releases the dispatch semaphore so the dispatch thread wakes immediately.
     */
    public void broadcast(Message msg) {
        dispatchQueue.offer(msg);
        dispatchSignal.release();
    }

    /**
     * Enqueues a FILE_SHARE_START, FILE_CHUNK, or FILE_SHARE_COMPLETE message into
     * the low-priority file chunk queue. BLOCKS the calling thread if the queue is
     * full (capacity = MAX_FILE_CHUNK_QUEUE). This provides backpressure so file
     * sender threads pause disk reads rather than flooding heap with pending chunks.
     *
     * Must NOT be called from the FX thread — only from file sender background threads.
     *
     * @throws InterruptedException if the sender thread is interrupted while waiting.
     */
    public void broadcastFileChunk(Message msg) throws InterruptedException {
        fileChunkQueue.put(msg); // blocks when queue is full
        dispatchSignal.release();
    }

    /**
     * Enqueues a message using a latest-value pattern.
     * If a pending message with the same key already exists, it is atomically replaced.
     * Non-blocking — safe to call from the JavaFX Application Thread.
     * Releases the dispatch semaphore so the dispatch thread wakes immediately.
     *
     * @param key A string key identifying the slot (e.g. "STROKE_PROGRESS_Teacher").
     * @param msg The message to send.
     */
    public void broadcastLatest(String key, Message msg) {
        latestProgressMap
            .computeIfAbsent(key, k -> new AtomicReference<>())
            .set(msg);
        dispatchSignal.release();
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
