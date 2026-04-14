package com.classroom.client;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.util.NetworkUtil;
import javafx.application.Platform;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
    private final Consumer<Message> onMessageReceived; // UI callback

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
        socket = new Socket(host, port);

        // Create OOS first, then OIS — critical ordering
        out = NetworkUtil.createOutputStream(socket);
        in  = NetworkUtil.createInputStream(socket);

        // Send AUTH_REQUEST
        NetworkUtil.sendMessage(out,
                new Message(MessageType.AUTH_REQUEST, null, studentName));

        // Expect AUTH_SUCCESS
        Message response = NetworkUtil.readMessage(in);
        if (response == null || response.getType() != MessageType.AUTH_SUCCESS) {
            closeStreams();
            throw new Exception("Authentication failed: server rejected the join request.");
        }

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
        Thread listener = new Thread(() -> {
            while (running) {
                Message msg = NetworkUtil.readMessage(in);
                if (msg == null) {
                    // Server closed or error
                    disconnect();
                    break;
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
            NetworkUtil.sendMessage(out,
                    new Message(MessageType.DISCONNECT, null, studentName));
        }
        closeStreams();
        System.out.println("[StudentClient] Disconnected.");
    }

    /** Returns true if the socket is open and connected. */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
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
}
