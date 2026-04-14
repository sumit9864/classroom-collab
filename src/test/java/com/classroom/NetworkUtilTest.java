package com.classroom;

import com.classroom.model.Message;
import com.classroom.model.MessageType;
import com.classroom.util.NetworkUtil;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

public class NetworkUtilTest {

    /**
     * Opens a loopback ServerSocket, sends an AUTH_REQUEST from a client thread,
     * reads it on the server side, and asserts the type round-trips correctly.
     */
    @Test
    @SuppressWarnings("unused")
    public void test_sendAndReceive_roundtrip() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Future<MessageType> serverFuture = exec.submit(() -> {
                try (Socket conn = serverSocket.accept()) {
                    // Server side: OOS first, then OIS
                    ObjectOutputStream sOut = NetworkUtil.createOutputStream(conn);
                    ObjectInputStream  sIn  = NetworkUtil.createInputStream(conn);
                    Message received = NetworkUtil.readMessage(sIn);
                    assertNotNull(received, "Server should receive a message");
                    return received.getType();
                }
            });

            // Client side: OOS first, then OIS
            try (Socket client = new Socket("127.0.0.1", port)) {
                ObjectOutputStream cOut = NetworkUtil.createOutputStream(client);
                ObjectInputStream  cIn  = NetworkUtil.createInputStream(client);
                NetworkUtil.sendMessage(cOut,
                        new Message(MessageType.AUTH_REQUEST, null, "TestStudent"));

                MessageType received = serverFuture.get(5, TimeUnit.SECONDS);
                assertEquals(MessageType.AUTH_REQUEST, received,
                        "Round-trip type must be AUTH_REQUEST");
            }
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Feeds a closed input stream into NetworkUtil.readMessage and asserts
     * it returns null rather than throwing an exception.
     */
    @Test
    public void test_readMessage_returnsNull_onClosedStream() throws Exception {
        // Wrap in a byte array that has a valid OOS header so OIS can initialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream tempOut = new ObjectOutputStream(baos);
        tempOut.flush();
        byte[] header = baos.toByteArray();

        ByteArrayInputStream bais = new ByteArrayInputStream(header);
        ObjectInputStream ois = new ObjectInputStream(bais);

        // Now try to read one object — should return null, not throw
        Message result = NetworkUtil.readMessage(ois);
        assertNull(result, "readMessage must return null on a closed/EOF stream");
    }
}
