package com.classroom.util;

import com.classroom.model.SlideData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class PptService {

    private static final int TARGET_WIDTH = 1920; // render width in pixels (Full HD)

    private byte[][] renderedSlides;  // one PNG byte[] per slide, null until loaded
    private int currentIndex  = 0;
    private int totalSlides   = 0;
    private boolean loaded    = false;

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
}
