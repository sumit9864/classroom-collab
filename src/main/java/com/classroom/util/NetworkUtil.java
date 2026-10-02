package com.classroom.util;

import com.classroom.model.Message;
import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class NetworkUtil {

    /**
     * Writes a Message to the given ObjectOutputStream.
     * Calls reset() first to prevent stale object cache issues.
     * Flushes after write.
     */
    public static void sendMessage(ObjectOutputStream out, Message msg) throws IOException {
        out.reset();
        out.writeObject(msg);
        out.flush();
    }

    /**
     * Reads one Message from the given ObjectInputStream.
     * Returns null if the stream is closed or EOF is reached.
     * Returns null (with a SECURITY warning log) if the ObjectInputFilter rejects the class.
     */
    public static Message readMessage(ObjectInputStream in) {
        try {
            return (Message) in.readObject();
        } catch (EOFException | java.net.SocketException e) {
            // Normal: connection closed
            return null;
        } catch (InvalidClassException e) {
            // ObjectInputFilter rejected a class — log the full exception chain so the operator
            // can identify which class was rejected. The cause chain contains the REJECTED status.
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            System.err.println("[NetworkUtil] SECURITY: deserialization rejected — "
                    + e.getMessage()
                    + " | root: " + root.getMessage()
                    + " (class not on whitelist or stream limits exceeded)");
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
     *
     * A strict class-allowlist filter (ObjectInputFilter) is applied immediately after
     * construction. Only classes that can legitimately appear in a Message object graph
     * are permitted. Any attempt to deserialize a class outside this whitelist is rejected
     * before any constructor or static initialiser runs, preventing deserialization
     * gadget-chain attacks from rogue clients on the LAN.
     *
     * The filter is additive — it does not change the OOS/OIS handshake protocol and
     * does not require any change to the model classes or their serialVersionUIDs.
     */
    public static ObjectInputStream createInputStream(Socket socket) throws IOException {
        ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
        ois.setObjectInputFilter(buildClassroomFilter());
        return ois;
    }

    /**
     * Builds the ObjectInputFilter whitelist for all streams in this application.
     *
     * Permitted classes (everything in a valid Message object graph):
     *   java.lang.Object               REQUIRED: Message.payload is declared as Object.
     *                                  The JVM filter evaluates the declared field type
     *                                  during deserialization. Without this entry every
     *                                  message with a non-null payload is rejected.
     *                                  Object has no serializable state, so permitting it
     *                                  does not weaken the gadget-chain protection —
     *                                  the !* sentinel still blocks all unlisted classes.
     *   com.classroom.model.*          All payload model classes + MessageType enum +
     *                                  ShapeData$ShapeType enum.
     *   WhiteboardPane$FullState       Late-join full-state snapshot payload.
     *   java.util.ArrayList            List<StrokeData>, List<ShapeData>, List<String>, etc.
     *   java.util.Arrays$ArrayList     May appear in serialized ArrayList data.
     *   java.lang.String               senderName, colorHex, id, text, fileName, transferId...
     *   java.lang.Integer              TAB_SWITCH payload (boxed int).
     *   java.lang.Number               Superclass of Integer — JVM filter evaluates superclasses.
     *   java.lang.Enum                 Superclass of all enum types. Without this entry,
     *                                  MessageType and ShapeData$ShapeType deserialization
     *                                  is rejected even though the enum class itself is
     *                                  covered by com.classroom.model.*.
     *   [D  (double[])                 StrokeData point arrays, CANVAS_RESIZE payload.
     *   [B  (byte[])                   SlideData.imageBytes, FileShareData.chunkData.
     *
     * Limits (DoS hardening):
     *   maxdepth=10        Rejects deeply-nested object graphs (stack-overflow attacks).
     *                      NOTE: maxrefs and maxbytes must NOT be used here because JVM filter
     *                      limits are cumulative over the lifetime of the ObjectInputStream.
     *                      They do not reset on stream.reset(), so long-lived sessions would
     *                      inevitably hit them and disconnect.
     *
     * The final "!*" is the default-deny sentinel — any class not matched by an earlier
     * pattern is rejected with status REJECTED before any code in that class runs.
     */
    private static ObjectInputFilter buildClassroomFilter() {
        return ObjectInputFilter.Config.createFilter(
            "maxdepth=10;"                                    +
            "java.lang.Object;"                               +   // declared type of Message.payload
            "com.classroom.model.*;"                          +
            "com.classroom.ui.WhiteboardPane$FullState;"      +
            "java.util.ArrayList;"                            +
            "java.util.Arrays$ArrayList;"                     +
            "java.lang.String;"                               +
            "java.lang.Integer;"                              +
            "java.lang.Number;"                               +   // superclass of Integer
            "java.lang.Enum;"                                 +   // superclass of all enums
            "[D;"                                             +   // double[]
            "[B;"                                             +   // byte[]
            "!*"                                              // reject all others
        );
    }

    /**
     * Enumerates network interfaces to find the best local IP address for the teacher server.
     * Skips loopback, down, virtual (isVirtual()), and VPN/VM adapters based on name.
     * Prefers isSiteLocalAddress() IPv4 on an interface that is up.
     * @return A list of candidate IPs. If empty, falls back to getLocalHost().
     */
    public static List<String> findBestLocalIp() {
        List<String> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                if (!isInterfaceNameAllowed(ni.getName()) || !isInterfaceNameAllowed(ni.getDisplayName())) continue;
                
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress() && addr.getHostAddress().contains(".")) {
                        candidates.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[NetworkUtil] Error finding local IP: " + e.getMessage());
        }
        
        if (candidates.isEmpty()) {
            try {
                candidates.add(InetAddress.getLocalHost().getHostAddress());
            } catch (Exception e) {
                candidates.add("127.0.0.1");
            }
        }
        return candidates;
    }

    /**
     * Helper to filter out VM/VPN adapters by name.
     * Public so it can be tested easily.
     */
    public static boolean isInterfaceNameAllowed(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase();
        return !(lower.contains("vbox") || lower.contains("vmware") || lower.contains("virtual") 
                || lower.contains("hyper-v") || lower.contains("wsl") || lower.contains("docker") 
                || lower.contains("tailscale") || lower.contains("vpn") || lower.contains("vmnet"));
    }
}
