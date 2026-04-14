package com.classroom.model;

import java.io.Serializable;
import java.util.List;

public class StrokeData implements Serializable {
    private static final long serialVersionUID = 2L;

    // Normalized points: each double[] = { x/canvasWidth, y/canvasHeight } — range [0.0, 1.0]
    // Normalizing ensures correct rendering even if teacher and student window sizes differ
    private final List<double[]> points;

    private final String colorHex;    // hex color string, e.g. "#000000"
    private final double strokeWidth; // logical width in pixels (2.0, 4.0, 8.0)
    private final boolean annotation; // false = whiteboard layer, true = annotation layer

    public StrokeData(List<double[]> points, String colorHex,
                      double strokeWidth, boolean annotation) {
        this.points      = points;
        this.colorHex    = colorHex;
        this.strokeWidth = strokeWidth;
        this.annotation  = annotation;
    }

    public List<double[]> getPoints()    { return points; }
    public String getColorHex()          { return colorHex; }
    public double getStrokeWidth()       { return strokeWidth; }
    public boolean isAnnotation()        { return annotation; }
}
