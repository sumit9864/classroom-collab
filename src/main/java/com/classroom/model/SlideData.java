package com.classroom.model;

import java.io.Serializable;

public class SlideData implements Serializable {
    private static final long serialVersionUID = 4L;

    private final byte[] imageBytes;  // PNG-encoded rendered slide image
    private final int slideIndex;     // 0-based current slide index
    private final int totalSlides;    // total number of slides in the deck

    public SlideData(byte[] imageBytes, int slideIndex, int totalSlides) {
        this.imageBytes  = imageBytes;
        this.slideIndex  = slideIndex;
        this.totalSlides = totalSlides;
    }

    public byte[] getImageBytes()  { return imageBytes; }
    public int    getSlideIndex()  { return slideIndex; }
    public int    getTotalSlides() { return totalSlides; }
}
