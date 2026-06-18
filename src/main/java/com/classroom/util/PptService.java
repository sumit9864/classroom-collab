package com.classroom.util;

import com.classroom.model.ShapeData;
import com.classroom.model.SlideData;
import com.classroom.model.StrokeData;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineEndProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class PptService {

    private static final int TARGET_WIDTH = 1920; // render width in pixels (Full HD)

    private byte[][] renderedSlides;  // one PNG byte[] per slide, null until loaded
    private int currentIndex  = 0;
    private int totalSlides   = 0;
    private boolean loaded    = false;
    private File loadedFile   = null;  // reference to the currently loaded .pptx file

    // Single-threaded executor — all POI rendering runs here, never on FX thread
    private final ExecutorService renderExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ppt-render");
                t.setDaemon(true);
                return t;
            });

    /**
     * Loads and renders all slides of the given .pptx file on the background render thread.
     * Calls onSuccess (on FX thread) when all slides are ready, or onError with a message on failure.
     */
    public void loadAsync(File file, Runnable onSuccess, Consumer<String> onError) {
        this.loadedFile = null; // reset before rendering starts
        renderExecutor.submit(() -> {
            try (XMLSlideShow pptx = new XMLSlideShow(new FileInputStream(file))) {

                List<XSLFSlide> slides = pptx.getSlides();
                if (slides.isEmpty()) {
                    javafx.application.Platform.runLater(() ->
                        onError.accept("The selected file contains no slides."));
                    return;
                }

                Dimension pgSize = pptx.getPageSize();
                double scale = TARGET_WIDTH / (double) pgSize.width;
                int imgH = (int) (pgSize.height * scale);

                byte[][] rendered = new byte[slides.size()][];

                for (int i = 0; i < slides.size(); i++) {
                    BufferedImage img = new BufferedImage(
                            TARGET_WIDTH, imgH, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = img.createGraphics();
                    try {
                        // Quality rendering hints
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                           RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                                           RenderingHints.VALUE_RENDER_QUALITY);
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                           RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                                           RenderingHints.VALUE_FRACTIONALMETRICS_ON);

                        // White background — POI does not guarantee background fill
                        g.setPaint(Color.WHITE);
                        g.fillRect(0, 0, TARGET_WIDTH, imgH);

                        // Scale transform and render
                        AffineTransform at = AffineTransform.getScaleInstance(scale, scale);
                        g.setTransform(at);
                        org.apache.poi.sl.draw.DrawFactory.getInstance(g)
                                .getDrawable(slides.get(i)).draw(g);

                    } finally {
                        g.dispose();
                    }

                    // Encode to PNG bytes
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(img, "PNG", baos);
                    rendered[i] = baos.toByteArray();
                }

                // Commit results (accessed only after this point)
                this.renderedSlides = rendered;
                this.totalSlides    = slides.size();
                this.currentIndex   = 0;
                this.loaded         = true;
                this.loadedFile     = file;  // store for export

                javafx.application.Platform.runLater(onSuccess);

            } catch (Exception e) {
                String msg = "Failed to load PPTX: " + e.getMessage();
                javafx.application.Platform.runLater(() -> onError.accept(msg));
            }
        });
    }

    /** Returns a SlideData for the current slide, or null if not loaded. */
    public SlideData getCurrentSlideData() {
        if (!loaded) return null;
        return new SlideData(renderedSlides[currentIndex], currentIndex, totalSlides);
    }

    /** Advances to the next slide and returns its SlideData, or null if already at the last slide. */
    public SlideData nextSlide() {
        if (!loaded || currentIndex >= totalSlides - 1) return null;
        currentIndex++;
        return getCurrentSlideData();
    }

    /** Goes back to the previous slide and returns its SlideData, or null if already at the first slide. */
    public SlideData prevSlide() {
        if (!loaded || currentIndex <= 0) return null;
        currentIndex--;
        return getCurrentSlideData();
    }

    public boolean isLoaded()      { return loaded; }
    public int getCurrentIndex()   { return currentIndex; }
    public int getTotalSlides()    { return totalSlides; }

    /** Shuts down the render executor. Call when the teacher session ends. */
    public void shutdown() {
        renderExecutor.shutdownNow();
    }

    // ── PPT Export ────────────────────────────────────────────────────────────

    /**
     * Exports the loaded .pptx with per-slide vector markings written as native PPTX shapes.
     * Runs on the render executor (background thread). Calls onSuccess or onError on the FX thread.
     */
    public void exportAllSlidesWithMarkings(
            Map<Integer, List<StrokeData>> strokesPerSlide,
            Map<Integer, List<ShapeData>>  shapesPerSlide,
            double canvasWidth,
            double canvasHeight,
            File outputFile,
            Runnable onSuccess,
            Consumer<String> onError) {

        renderExecutor.submit(() -> {
            try (XMLSlideShow pptx = new XMLSlideShow(new FileInputStream(loadedFile))) {

                Dimension pageSize = pptx.getPageSize();

                // POI setAnchor() expects coordinates in POINTS (not EMUs).
                // pageSize is already in points. Convert from canvas pixels to points.
                double xFactor = (double) pageSize.width  / canvasWidth;
                double yFactor = (double) pageSize.height / canvasHeight;

                List<XSLFSlide> slides = pptx.getSlides();

                for (int i = 0; i < slides.size(); i++) {
                    XSLFSlide slide = slides.get(i);
                    List<ShapeData>  shapes  = shapesPerSlide.getOrDefault(i, List.of());
                    List<StrokeData> strokes = strokesPerSlide.getOrDefault(i, List.of());
                    for (ShapeData sd : shapes)   addPoiShape(slide, sd, xFactor, yFactor);
                    for (StrokeData st : strokes) addPoiStroke(slide, st, xFactor, yFactor);
                }

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    pptx.write(fos);
                }

                javafx.application.Platform.runLater(onSuccess);

            } catch (Exception e) {
                String msg = "Export failed: " + e.getMessage();
                javafx.application.Platform.runLater(() -> onError.accept(msg));
            }
        });
    }

    private void addPoiShape(XSLFSlide slide, ShapeData sd,
                              double xFactor, double yFactor) {
        long x = (long)(sd.getX() * xFactor);
        long y = (long)(sd.getY() * yFactor);
        long w = (long)(sd.getW() * xFactor);
        long h = (long)(sd.getH() * yFactor);
        Rectangle2D.Double anchor = new Rectangle2D.Double(x, y, w, h);
        java.awt.Color color = parseHex(sd.getStrokeHex());

        switch (sd.getType()) {
            case RECT -> {
                XSLFAutoShape shape = slide.createAutoShape();
                shape.setShapeType(ShapeType.RECT);
                shape.setAnchor(anchor);
                shape.setLineColor(color);
                shape.setLineWidth(sd.getStrokeWidth());
                shape.setFillColor(null);
            }
            case ELLIPSE -> {
                XSLFAutoShape shape = slide.createAutoShape();
                shape.setShapeType(ShapeType.ELLIPSE);
                shape.setAnchor(anchor);
                shape.setLineColor(color);
                shape.setLineWidth(sd.getStrokeWidth());
                shape.setFillColor(null);
            }
            case LINE -> {
                XSLFAutoShape line = slide.createAutoShape();
                line.setShapeType(ShapeType.LINE);
                line.setAnchor(anchor);
                line.setLineColor(color);
                line.setLineWidth(sd.getStrokeWidth());
                line.setFillColor(null);
            }
            case ARROW -> {
                XSLFAutoShape arrow = slide.createAutoShape();
                arrow.setShapeType(ShapeType.LINE);
                arrow.setAnchor(anchor);
                arrow.setLineColor(color);
                arrow.setLineWidth(sd.getStrokeWidth());
                arrow.setFillColor(null);
                // Add arrowhead at the tail end of the line via XML bean
                // (getSpPr() is protected in XSLFSimpleShape, so access through the XML object)
                org.openxmlformats.schemas.presentationml.x2006.main.CTShape ctShape =
                        (org.openxmlformats.schemas.presentationml.x2006.main.CTShape) arrow.getXmlObject();
                var spPr = ctShape.getSpPr();
                var ln = spPr.isSetLn() ? spPr.getLn() : spPr.addNewLn();
                CTLineEndProperties endArrow = ln.addNewTailEnd();
                endArrow.setType(STLineEndType.ARROW);
            }
            case TEXT -> {
                XSLFTextBox tb = slide.createTextBox();
                tb.setAnchor(anchor);
                XSLFTextParagraph para = tb.addNewTextParagraph();
                XSLFTextRun run = para.addNewTextRun();
                run.setText(sd.getText() != null ? sd.getText() : "");
                run.setFontColor(color);
                run.setFontSize(sd.getFontSize());
            }
        }
    }

    private void addPoiStroke(XSLFSlide slide, StrokeData st,
                               double xFactor, double yFactor) {
        // StrokeData.getPoints() returns List<double[]> where each element is [x, y]
        List<double[]> pts = st.getPoints();
        if (pts == null || pts.size() < 2) return;

        Path2D.Double path = new Path2D.Double();
        path.moveTo(pts.get(0)[0] * xFactor, pts.get(0)[1] * yFactor);
        for (int i = 1; i < pts.size(); i++) {
            path.lineTo(pts.get(i)[0] * xFactor, pts.get(i)[1] * yFactor);
        }

        XSLFFreeformShape freeform = slide.createFreeform();
        freeform.setPath(path);
        freeform.setLineColor(parseHex(st.getColorHex()));
        freeform.setLineWidth(st.getStrokeWidth());
        freeform.setFillColor(null);
    }

    private java.awt.Color parseHex(String hex) {
        if (hex == null || hex.isEmpty()) return java.awt.Color.BLACK;
        try {
            return java.awt.Color.decode(hex.startsWith("#") ? hex : "#" + hex);
        } catch (NumberFormatException e) {
            return java.awt.Color.BLACK;
        }
    }
}
