package com.classroom.server;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.util.NetworkUtil;
import javafx.application.Platform;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class TeacherServer {

    private final int port;
    private ServerSocket serverSocket;
    private final List<ClientHandler> clients;  // synchronized list
    private volatile boolean running;
    private final Runnable onClientListChanged; // UI callback — called on every join/leave
    private Supplier<Message> stateSupplier;       // provides FULL_STATE snapshot for whiteboard
    private Supplier<Message> pptStateSupplier;    // provides current PPT slide
    private Supplier<Message> pptWhiteboardStateSupplier; // provides FULL_STATE for PPT whiteboard

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
            System.out.println("[TeacherServer] Listening on port " + port);
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    ClientHandler handler = new ClientHandler(clientSocket, this);
                    Thread t = new Thread(handler);
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[TeacherServer] Accept error: " + e.getMessage());
                    }
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Gracefully shuts down the server: broadcasts DISCONNECT, then closes the socket.
     */
    public void stop() {
        running = false;
        broadcast(new Message(MessageType.DISCONNECT, null, "Teacher"));
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("[TeacherServer] Error closing server socket: " + e.getMessage());
        }
    }

    /**
     * Broadcasts a message to all connected clients.
     * Removes any client whose send fails (broken pipe).
     */
    public void broadcast(Message msg) {
        List<ClientHandler> failed = new ArrayList<>();
        synchronized (clients) {
            for (ClientHandler client : clients) {
                try {
                    NetworkUtil.sendMessage(client.getOutputStream(), msg);
                } catch (Exception e) {
                    System.err.println("[TeacherServer] Broadcast failed for "
                            + client.getStudentName() + ": " + e.getMessage());
                    failed.add(client);
                }
            }
        }
        clients.removeAll(failed);
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

        // 4. Add to broadcast list and notify UI (existing)
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
}
