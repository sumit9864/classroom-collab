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
    private static final long serialVersionUID = 4L;

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

    // ── Text formatting (TEXT type only) — per-object, not per-character ────────
    private String  fontFamily;     // e.g. "System", "Arial", "Serif" — default "System"
    private boolean bold;           // default false
    private boolean italic;         // default false
    private boolean underline;      // default false
    private String  textAlignment;  // "LEFT", "CENTER", "RIGHT" — default "LEFT"
    private double  lineHeight;     // multiplier, e.g. 1.2 — default 1.2 (0 = use system default)
    private double  letterSpacing;  // in px — default 0.0 (visual effect NOT implemented this phase)
    private boolean autoWidth;      // true = width auto-derived from content; false = user has resized

    /** Primary constructor — generates a fresh UUID. Text formatting fields use sensible defaults. */
    public ShapeData(ShapeType type,
                     double x, double y, double w, double h,
                     String strokeHex, double strokeWidth,
                     String text, double fontSize,
                     boolean annotation) {
        this(UUID.randomUUID().toString(), type, x, y, w, h, strokeHex, strokeWidth, text, fontSize, annotation,
             "System", false, false, false, "LEFT", 1.2, 0.0, true);
    }

    /** Full reconstitution / copy constructor — accepts all 19 fields including text formatting. */
    public ShapeData(String id, ShapeType type,
                     double x, double y, double w, double h,
                     String strokeHex, double strokeWidth,
                     String text, double fontSize,
                     boolean annotation,
                     String fontFamily, boolean bold, boolean italic, boolean underline,
                     String textAlignment, double lineHeight, double letterSpacing, boolean autoWidth) {
        this.id            = id;
        this.type          = type;
        this.x             = x;
        this.y             = y;
        this.w             = w;
        this.h             = h;
        this.strokeHex     = strokeHex;
        this.strokeWidth   = strokeWidth;
        this.text          = text;
        this.fontSize      = fontSize;
        this.annotation    = annotation;
        this.fontFamily    = fontFamily;
        this.bold          = bold;
        this.italic        = italic;
        this.underline     = underline;
        this.textAlignment = textAlignment;
        this.lineHeight    = lineHeight;
        this.letterSpacing = letterSpacing;
        this.autoWidth     = autoWidth;
    }

    public ShapeData copy() {
        return new ShapeData(id, type, x, y, w, h, strokeHex, strokeWidth, text, fontSize, annotation,
                             fontFamily, bold, italic, underline, textAlignment, lineHeight, letterSpacing, autoWidth);
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

    // ── Text formatting getters ────────────────────────────────────────────────
    public String  getFontFamily()    { return fontFamily; }
    public boolean isBold()           { return bold; }
    public boolean isItalic()         { return italic; }
    public boolean isUnderline()      { return underline; }
    public String  getTextAlignment() { return textAlignment; }
    public double  getLineHeight()    { return lineHeight; }
    public double  getLetterSpacing() { return letterSpacing; }
    public boolean isAutoWidth()      { return autoWidth; }

    // ── Setters (used during move / resize) ────────────────────────────────────
    public void setX(double v)          { x = v; }
    public void setY(double v)          { y = v; }
    public void setW(double v)          { w = v; }
    public void setH(double v)          { h = v; }
    public void setText(String v)       { text = v; }
    public void setStrokeHex(String v)  { strokeHex = v; }
    public void setStrokeWidth(double v){ strokeWidth = v; }
    public void setFontSize(double v)   { fontSize = v; }

    // ── Text formatting setters ────────────────────────────────────────────────
    public void setFontFamily(String v)    { fontFamily = v; }
    public void setBold(boolean v)         { bold = v; }
    public void setItalic(boolean v)       { italic = v; }
    public void setUnderline(boolean v)    { underline = v; }
    public void setTextAlignment(String v) { textAlignment = v; }
    public void setLineHeight(double v)    { lineHeight = v; }
    public void setLetterSpacing(double v) { letterSpacing = v; }
    public void setAutoWidth(boolean v)    { autoWidth = v; }
}
