package com.classroom.server;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.util.NetworkUtil;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final TeacherServer server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String studentName;
    private volatile boolean active = true;

    public ClientHandler(Socket socket, TeacherServer server) {
        this.socket = socket;
        this.server = server;
        try {
            // Create OOS first, then OIS — critical ordering to avoid deadlock
            this.out = NetworkUtil.createOutputStream(socket);
            this.in  = NetworkUtil.createInputStream(socket);
        } catch (IOException e) {
            System.err.println("[ClientHandler] Stream init error: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            // First message must be AUTH_REQUEST
            Message authMsg = NetworkUtil.readMessage(in);
            if (authMsg == null || authMsg.getType() != MessageType.AUTH_REQUEST) {
                System.err.println("[ClientHandler] Expected AUTH_REQUEST, got: "
                        + (authMsg != null ? authMsg.getType() : "null"));
                closeStreams();
                return;
            }

            studentName = authMsg.getSenderName();
            System.out.println("[ClientHandler] AUTH_REQUEST from: " + studentName);

            // Confirm authentication
            NetworkUtil.sendMessage(out, new Message(MessageType.AUTH_SUCCESS, null, "Teacher"));
            server.addClient(this);

            // Main read loop
            while (active) {
                Message msg = NetworkUtil.readMessage(in);
                if (msg == null) {
                    break; // client disconnected
                }
                handle(msg);
            }
        } finally {
            server.removeClient(this);
            closeStreams();
            System.out.println("[ClientHandler] " + studentName + " disconnected.");
        }
    }

    /**
     * Sends a message to this specific student.
     */
    public void send(Message msg) {
        try {
            NetworkUtil.sendMessage(out, msg);
        } catch (Exception e) {
            System.err.println("[ClientHandler] send error for " + studentName + ": " + e.getMessage());
        }
    }

    /**
     * Returns the raw OOS so TeacherServer.broadcast() can write directly.
     */
    public ObjectOutputStream getOutputStream() {
        return out;
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
