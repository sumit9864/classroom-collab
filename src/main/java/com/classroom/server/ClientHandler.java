package com.classroom.server;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.util.NetworkUtil;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientHandler implements Runnable {

    /**
     * Capacity of the per-client outbound send queue.
     * Each message is typically 1–256 KB. At 200 capacity this is ~50 MB worst case
     * per client (for file chunks). If a slow client cannot drain its queue fast enough,
     * offer() returns false and the dispatch thread marks the client for disconnection.
     * This prevents a single slow student from stalling all other students (head-of-line
     * blocking) — the core motivation for Fix B.
     */
    public static final int SEND_QUEUE_CAPACITY = 200;

    private final Socket socket;
    private final TeacherServer server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String studentName;
    private volatile boolean active = true;
    private final AtomicBoolean removed = new AtomicBoolean(false);

    /**
     * Per-client outbound message queue. The dispatch thread (and addClient state sync)
     * enqueue messages here via {@link #enqueue(Message)}. The private {@code sendThread}
     * is the sole consumer and the sole writer of the ObjectOutputStream — no
     * synchronization on OOS is needed because only one thread ever touches it.
     */
    private final LinkedBlockingQueue<Message> sendQueue =
            new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY);

    /**
     * Daemon thread that drains {@link #sendQueue} and writes each message to this
     * client's OOS. Created and started in the constructor after streams are initialised.
     * Exits when {@link #shutdown()} poisons the queue or on any write exception.
     */
    private Thread sendThread;

    public ClientHandler(Socket socket, TeacherServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(10_000);
            out = NetworkUtil.createOutputStream(socket);
            in  = NetworkUtil.createInputStream(socket);

            // First message must be AUTH_REQUEST
            Message authMsg = NetworkUtil.readMessage(in);
            if (authMsg == null || authMsg.getType() != MessageType.AUTH_REQUEST) {
                System.err.println("[ClientHandler] Expected AUTH_REQUEST, got: "
                        + (authMsg != null ? authMsg.getType() : "null"));
                closeStreams();
                return;
            }

            studentName = authMsg.getSenderName();
            if (studentName == null || studentName.trim().isEmpty()) {
                rejectJoin("Name cannot be empty.");
                return;
            }
            if (studentName.length() > 30) {
                rejectJoin("Name too long (max 30 chars).");
                return;
            }
            if (studentName.equalsIgnoreCase("Teacher") || studentName.equalsIgnoreCase("Teacher_PPT")) {
                rejectJoin("\"Teacher\" and \"Teacher_PPT\" are reserved names.");
                return;
            }
            if (server.isNameTaken(studentName)) {
                rejectJoin("That name is already in use. Please choose another.");
                return;
            }

            socket.setSoTimeout(0); // Reset for normal operation
            System.out.println("[ClientHandler] AUTH_REQUEST from: " + studentName);

            // Start the per-client send thread BEFORE sending AUTH_SUCCESS so that
            // any state-sync messages queued by addClient() are drained promptly.
            startSendThread();

            // Confirm authentication — goes through the send queue like everything else
            enqueue(new Message(MessageType.AUTH_SUCCESS, null, "Teacher"));
            server.addClient(this);

            // Main read loop
            while (active) {
                Message msg = NetworkUtil.readMessage(in);
                if (msg == null) {
                    break; // client disconnected
                }
                handle(msg);
            }
        } catch (Exception e) {
            System.err.println("[ClientHandler] Init/read error: " + e.getMessage());
        } finally {
            shutdown();
            if (removed.compareAndSet(false, true)) {
                server.removeClient(this);
            }
            closeStreams();
            System.out.println("[ClientHandler] " + (studentName != null ? studentName : "unknown") + " disconnected.");
        }
    }

    private void rejectJoin(String reason) {
        try {
            NetworkUtil.sendMessage(out, new Message(MessageType.AUTH_FAILURE, reason, "Teacher"));
        } catch (IOException ignored) {}
        closeStreams();
    }

    /**
     * Enqueues a message for delivery to this student.
     * Non-blocking — returns true if the message was accepted, false if the queue is full.
     * Called from:
     *   - The dispatch thread (doSendAll → distributeToAll)
     *   - The ClientHandler pool thread during addClient() state sync
     * Both callers are safe because LinkedBlockingQueue.offer() is thread-safe.
     */
    public boolean enqueue(Message msg) {
        return sendQueue.offer(msg);
    }

    public String getStudentName() {
        return studentName;
    }

    /**
     * Handles an incoming message from this student.
     * DISCONNECT exits the run loop; everything else is stubbed for future phases.
     */
    private void handle(Message msg) {
        switch (msg.getType()) {
            case DISCONNECT:
                active = false;
                break;
            default:
                System.out.println("[ClientHandler] Unhandled message type: " + msg.getType()
                        + " from " + studentName);
                // Students are read-only. We do not expect incoming sync messages from them.
                break;
        }
    }

    /**
     * Starts the per-client send thread. The thread drains the sendQueue and writes
     * each message to the ObjectOutputStream. It is the ONLY thread that ever writes
     * to this client's OOS — no synchronized block is needed.
     *
     * On any IOException the thread logs the error, marks the client inactive,
     * and exits. The read-loop in run() will detect the inactive flag or the
     * broken socket and clean up.
     */
    private void startSendThread() {
        sendThread = new Thread(() -> {
            try {
                while (active) {
                    Message msg = sendQueue.take(); // blocks until a message arrives
                    if (msg.getType() == null) break; // poison pill — shutdown()
                    try {
                        NetworkUtil.sendMessage(out, msg);
                    } catch (Exception e) {
                        System.err.println("[ClientHandler] sendThread write error for "
                                + studentName + ": " + e.getMessage());
                        active = false;
                        break;
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "send-" + (studentName != null ? studentName : "pending"));
        sendThread.setDaemon(true);
        sendThread.start();
    }

    public void drain(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!sendQueue.isEmpty() && System.currentTimeMillis() < end) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    /**
     * Gracefully stops the send thread by interrupting it.
     * Called from the run() finally block and from TeacherServer.stop().
     */
    public void shutdown() {
        active = false;
        if (sendThread != null) {
            sendThread.interrupt();
        }
    }

    private void closeStreams() {
        try {
            if (in  != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("[ClientHandler] Close error: " + e.getMessage());
        }
    }
}
