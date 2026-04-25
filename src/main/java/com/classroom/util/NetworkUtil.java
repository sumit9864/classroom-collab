package com.classroom.util;

import com.classroom.model.Message;
import java.io.*;
import java.net.Socket;

public class NetworkUtil {

    /**
     * Writes a Message to the given ObjectOutputStream.
     * Calls reset() first to prevent stale object cache issues.
     * Flushes after write.
     */
    public static void sendMessage(ObjectOutputStream out, Message msg) {
        try {
            out.reset();
            out.writeObject(msg);
            out.flush();
        } catch (IOException e) {
            System.err.println("[NetworkUtil] sendMessage error: " + e.getMessage());
        }
    }

    /**
     * Reads one Message from the given ObjectInputStream.
     * Returns null if the stream is closed or EOF is reached.
     */
    public static Message readMessage(ObjectInputStream in) {
        try {
            return (Message) in.readObject();
        } catch (EOFException | java.net.SocketException e) {
            // Normal: connection closed
            return null;
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[NetworkUtil] readMessage error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates and returns an ObjectOutputStream from the socket's OutputStream.
     * Flushes immediately after creation — REQUIRED before creating a paired
     * ObjectInputStream to prevent mutual blocking on stream header exchange.
     */
    public static ObjectOutputStream createOutputStream(Socket socket) throws IOException {
        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        return out;
    }

    /**
     * Creates and returns an ObjectInputStream from the socket's InputStream.
     * MUST be called AFTER createOutputStream on the same socket to avoid deadlock.
     */
    public static ObjectInputStream createInputStream(Socket socket) throws IOException {
        return new ObjectInputStream(socket.getInputStream());
    }
}
