package com.classroom.client;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.util.NetworkUtil;
import javafx.application.Platform;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

public class StudentClient {

    private final String host;
    private final int port;
    private final String studentName;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean running;
    private Runnable onDisconnectCallback; // called on FX thread when server drops unexpectedly
    private Consumer<State> onStateChange;
    private final Consumer<Message> onMessageReceived; // UI callback
    
    public enum State { OK, STALE, LOST }
    private volatile long lastMessageAt;

    public StudentClient(String host, int port, String studentName,
                         Consumer<Message> onMessageReceived) {
        this.host = host;
        this.port = port;
        this.studentName = studentName;
        this.onMessageReceived = onMessageReceived;
    }

    /**
     * Opens a TCP socket to host:port, performs the AUTH handshake, and
     * starts the background listener thread.
     * Throws IOException if the connection or authentication fails.
     */
    public void connect() throws Exception {
        socket = new Socket();
        
        try {
            socket.connect(new InetSocketAddress(host, port), 5000); // 5-second timeout
        } catch (java.net.ConnectException e) {
            throw new Exception("No classroom found at " + host + ":" + port + ". Check the IP and that the teacher has started a session.");
        } catch (java.net.SocketTimeoutException e) {
            throw new Exception("The teacher did not respond. Check Wi-Fi and the firewall (port " + port + ").");
        } catch (Exception e) {
            throw new Exception("Connection failed: " + e.getMessage());
        }

        socket.setSoTimeout(5000);
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);

        // Create OOS first, then OIS — critical ordering
        out = NetworkUtil.createOutputStream(socket);
        in  = NetworkUtil.createInputStream(socket);

        // Send AUTH_REQUEST
        NetworkUtil.sendMessage(out,
                new Message(MessageType.AUTH_REQUEST, null, studentName));

        // Expect AUTH_SUCCESS
        Message response;
        try {
            response = NetworkUtil.readMessage(in);
        } catch (Exception e) {
            closeStreams();
            throw new Exception("The teacher did not respond. Check Wi-Fi and the firewall (port " + port + ").");
        }
        
        if (response == null || (response.getType() != MessageType.AUTH_SUCCESS && response.getType() != MessageType.AUTH_FAILURE)) {
            closeStreams();
            String reason = response != null && response.getPayload() instanceof String ? (String) response.getPayload() : "server rejected the join request";
            throw new Exception("Authentication failed: " + reason);
        }
        
        if (response.getType() == MessageType.AUTH_FAILURE) {
            closeStreams();
            throw new Exception((String) response.getPayload());
        }
        
        socket.setSoTimeout(0); // Reset timeout for normal operation

        running = true;
        startListenerThread();
        System.out.println("[StudentClient] Connected as '" + studentName
                + "' to " + host + ":" + port);
    }

    /**
     * Daemon thread that reads Messages from the server and forwards them
     * to the UI callback via Platform.runLater.
     */
    private void startListenerThread() {
        Thread watchdog = new Thread(() -> {
            boolean wasStale = false;
            while (running) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                if (running) {
                    if (System.currentTimeMillis() - lastMessageAt > 75_000) {
                        if (!wasStale) {
                            wasStale = true;
                            if (onStateChange != null) Platform.runLater(() -> onStateChange.accept(State.STALE));
                        }
                    } else if (wasStale) {
                        wasStale = false;
                        if (onStateChange != null) Platform.runLater(() -> onStateChange.accept(State.OK));
                    }
                }
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();

        Thread listener = new Thread(() -> {
            lastMessageAt = System.currentTimeMillis();
            while (running) {
                Message msg = NetworkUtil.readMessage(in);
                if (msg == null) {
                    // Server closed or error — capture running state BEFORE disconnect()
                    boolean wasRunning = running;
                    disconnect();
                    if (wasRunning) {
                        if (onStateChange != null) Platform.runLater(() -> onStateChange.accept(State.LOST));
                        if (onDisconnectCallback != null) Platform.runLater(onDisconnectCallback);
                    }
                    break;
                }
                lastMessageAt = System.currentTimeMillis();
                if (msg.getType() == MessageType.DISCONNECT) {
                    running = false; // Expected disconnect: prevent onDisconnectCallback from firing
                }
                Platform.runLater(() -> onMessageReceived.accept(msg));
            }
        });
        listener.setDaemon(true);
        listener.start();
    }

    /**
     * Gracefully disconnects: sends DISCONNECT, then closes all streams.
     */
    public void disconnect() {
        if (!running) return;
        running = false;
        if (isConnected()) {
            try {
                NetworkUtil.sendMessage(out,
                        new Message(MessageType.DISCONNECT, null, studentName));
            } catch (IOException ignored) {}
        }
        closeStreams();
        System.out.println("[StudentClient] Disconnected.");
    }

    /** Returns true if the socket is open and connected. */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Registers a callback to be invoked on the JavaFX Application Thread
     * when the server connection is lost unexpectedly (not a graceful disconnect).
     * Must be called before the listener thread delivers the disconnect.
     */
    public void setOnDisconnect(Runnable callback) {
        this.onDisconnectCallback = callback;
    }

    public void setOnStateChange(Consumer<State> callback) {
        this.onStateChange = callback;
    }

    private void closeStreams() {
        try {
            if (in     != null) in.close();
            if (out    != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("[StudentClient] Close error: " + e.getMessage());
        }
    }

    public void sendMessage(Message msg) {
        if (running && out != null) {
            try {
                NetworkUtil.sendMessage(out, msg);
            } catch (IOException e) {
                System.err.println("[StudentClient] sendMessage error: " + e.getMessage());
            }
        }
    }
}
