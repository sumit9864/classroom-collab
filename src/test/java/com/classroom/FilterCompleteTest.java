package com.classroom;

import com.classroom.model.*;
import com.classroom.ui.WhiteboardPane;
import com.classroom.util.NetworkUtil;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests every payload type that appears in a real session to verify the filter whitelist is complete.
 * Run with: mvn test -Dtest=FilterCompleteTest
 */
public class FilterCompleteTest {

    /** Roundtrip helper: server reads with filter, client sends with same filter. */
    private Message roundtrip(Object payload) throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Future<Message> fut = exec.submit(() -> {
                try (Socket s = ss.accept()) {
                    // Server side — OOS first, OIS second (with filter)
                    ObjectOutputStream sOut = NetworkUtil.createOutputStream(s);
                    ObjectInputStream  sIn  = NetworkUtil.createInputStream(s);
                    return NetworkUtil.readMessage(sIn);
                }
            });

            try (Socket c = new Socket("127.0.0.1", port)) {
                ObjectOutputStream cOut = NetworkUtil.createOutputStream(c);
                ObjectInputStream  cIn  = NetworkUtil.createInputStream(c);
                NetworkUtil.sendMessage(cOut, new Message(MessageType.WHITEBOARD_STROKE, payload, "Teacher"));
            }

            return fut.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }
    }

    private void assertPassesFilter(String label, Object payload) throws Exception {
        Message m = roundtrip(payload);
        assertNotNull(m, label + ": FILTER REJECTED the payload — see stderr SECURITY log for class name");
        assertEquals(MessageType.WHITEBOARD_STROKE, m.getType(), label + ": type mismatch");
    }

    // ── null payload (HEARTBEAT, DISCONNECT, UNDO, REDO, WHITEBOARD_CLEAR, ANNOTATION_CLEAR) ──
    @Test public void t01_nullPayload() throws Exception {
        assertPassesFilter("null payload", null);
    }

    // ── StrokeData with populated points (WHITEBOARD_STROKE, ANNOTATION_STROKE) ──
    @Test public void t02_strokeData() throws Exception {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0.1, 0.2}); pts.add(new double[]{0.3, 0.4});
        assertPassesFilter("StrokeData", new StrokeData(pts, "#FF0000", 2.0, false));
    }

    // ── StrokeData empty points (STROKE_PROGRESS with no drag yet) ──
    @Test public void t03_strokeDataEmpty() throws Exception {
        assertPassesFilter("StrokeData empty", new StrokeData(new ArrayList<>(), "#000000", 2.0, false));
    }

    // ── ShapeData RECT ──
    @Test public void t04_shapeDataRect() throws Exception {
        assertPassesFilter("ShapeData RECT", new ShapeData(ShapeData.ShapeType.RECT, 10,20,100,50,"#0000FF",2.0,null,0,false));
    }

    // ── ShapeData TEXT (has non-null text field) ──
    @Test public void t05_shapeDataText() throws Exception {
        assertPassesFilter("ShapeData TEXT", new ShapeData(ShapeData.ShapeType.TEXT, 50,50,200,40,"#FF0000",2.0,"Hello",16,false));
    }

    // ── String payload (SHAPE_REMOVE) ──
    @Test public void t06_stringPayload() throws Exception {
        assertPassesFilter("String (shape id)", "some-uuid-string-12345");
    }

    // ── Integer payload (TAB_SWITCH with positive index) ──
    @Test public void t07_integerPositive() throws Exception {
        assertPassesFilter("Integer positive (tab switch)", 2);
    }

    // ── Integer payload (TAB_SWITCH with -1 to unlock) ──
    @Test public void t08_integerNegative() throws Exception {
        assertPassesFilter("Integer -1 (unlock tabs)", -1);
    }

    // ── double[] payload (CANVAS_RESIZE) ──
    @Test public void t09_doubleArray() throws Exception {
        assertPassesFilter("double[] (canvas resize)", new double[]{1280.0, 720.0});
    }

    // ── byte[] payload (FILE_CHUNK) ──
    @Test public void t10_byteArray() throws Exception {
        assertPassesFilter("byte[]", new byte[]{0x01, 0x02, 0x03});
    }

    // ── List<String> payload (STUDENT_LIST_UPDATE) ──
    @Test public void t11_stringList() throws Exception {
        assertPassesFilter("List<String>", new ArrayList<>(Arrays.asList("Alice", "Bob", "Charlie")));
    }

    // ── FullState with strokes and shapes ──
    @Test public void t12_fullState() throws Exception {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0.5, 0.5});
        List<StrokeData> strokes = new ArrayList<>();
        strokes.add(new StrokeData(pts, "#000000", 2.0, false));
        List<ShapeData> shapes = new ArrayList<>();
        shapes.add(new ShapeData(ShapeData.ShapeType.ELLIPSE, 10,10,50,50,"#00FF00",1.5,null,0,false));
        WhiteboardPane.FullState fs = new WhiteboardPane.FullState(1280, 720, strokes, shapes);
        assertPassesFilter("FullState with content", fs);
    }

    // ── FullState empty ──
    @Test public void t13_fullStateEmpty() throws Exception {
        WhiteboardPane.FullState fs = new WhiteboardPane.FullState(800, 500, new ArrayList<>(), new ArrayList<>());
        assertPassesFilter("FullState empty", fs);
    }

    // ── CodeData ──
    @Test public void t14_codeData() throws Exception {
        assertPassesFilter("CodeData", new CodeData("System.out.println(\"Hello\");", "Java"));
    }

    // ── SlideData (byte[] image) ──
    @Test public void t15_slideData() throws Exception {
        assertPassesFilter("SlideData", new SlideData(new byte[]{0x01, 0x02}, 0, 5));
    }

    // ── FileShareData start ──
    @Test public void t16_fileShareData() throws Exception {
        assertPassesFilter("FileShareData", FileShareData.chunk("tid-123", "report.pdf", 1024L, 2, 0, new byte[]{0x01, 0x02}));
    }

    // ── StrokeData annotation=true ──
    @Test public void t17_annotationStroke() throws Exception {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{0.0, 0.0});
        assertPassesFilter("StrokeData annotation", new StrokeData(pts, "#FFFF00", 4.0, true));
    }
}
