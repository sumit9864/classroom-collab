package com.classroom.model;

import java.io.Serializable;
import java.util.UUID;

/**
 * Serializable model for a teacher-placed shape or text box on the whiteboard overlay.
 * Coordinates are in absolute canvas pixels (same pixel space as the canvas dimensions).
 * For LINE: x,y = start point; w = dx; h = dy (so end = x+w, y+h).
 * For RECT / ELLIPSE / TEXT: x,y = top-left corner; w,h = size.
 */
public class ShapeData implements Serializable {
    private static final long serialVersionUID = 3L;

    public enum ShapeType { RECT, ELLIPSE, LINE, ARROW, TEXT }

    private final String    id;          // immutable UUID
    private final ShapeType type;        // immutable
    private final boolean   annotation;  // immutable — set at creation time

    // Mutable geometry (updated by move / resize)
    private double x, y, w, h;

    // Mutable style
    private String strokeHex;
    private double strokeWidth;

    // Text content (TEXT type only)
    private String text;
    private double fontSize;

    /** Primary constructor — generates a fresh UUID. */
    public ShapeData(ShapeType type,
                     double x, double y, double w, double h,
                     String strokeHex, double strokeWidth,
                     String text, double fontSize,
                     boolean annotation) {
        this(UUID.randomUUID().toString(), type, x, y, w, h, strokeHex, strokeWidth, text, fontSize, annotation);
    }

    /** Reconstitution / copy constructor. */
    public ShapeData(String id, ShapeType type,
                     double x, double y, double w, double h,
                     String strokeHex, double strokeWidth,
                     String text, double fontSize,
                     boolean annotation) {
        this.id          = id;
        this.type        = type;
        this.x           = x;
        this.y           = y;
        this.w           = w;
        this.h           = h;
        this.strokeHex   = strokeHex;
        this.strokeWidth = strokeWidth;
        this.text        = text;
        this.fontSize    = fontSize;
        this.annotation  = annotation;
    }

    public ShapeData copy() {
        return new ShapeData(id, type, x, y, w, h, strokeHex, strokeWidth, text, fontSize, annotation);
    }

    // ── Immutable getters ──────────────────────────────────────────────────────
    public String    getId()          { return id; }
    public ShapeType getType()        { return type; }
    public boolean   isAnnotation()   { return annotation; }

    // ── Mutable getters ────────────────────────────────────────────────────────
    public double getX()              { return x; }
    public double getY()              { return y; }
    public double getW()              { return w; }
    public double getH()              { return h; }
    public String getStrokeHex()      { return strokeHex; }
    public double getStrokeWidth()    { return strokeWidth; }
    public String getText()           { return text; }
    public double getFontSize()       { return fontSize; }

    // ── Setters (used during move / resize) ────────────────────────────────────
    public void setX(double v)          { x = v; }
    public void setY(double v)          { y = v; }
    public void setW(double v)          { w = v; }
    public void setH(double v)          { h = v; }
    public void setText(String v)       { text = v; }
    public void setStrokeHex(String v)  { strokeHex = v; }
    public void setStrokeWidth(double v){ strokeWidth = v; }
    public void setFontSize(double v)   { fontSize = v; }
}
