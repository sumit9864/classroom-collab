package com.classroom.model;

import java.io.Serializable;

public class CodeData implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String code;      // The full code text
    private final String language;  // Display hint only (e.g. "Java", "Python") — not parsed

    public CodeData(String code, String language) {
        this.code = code != null ? code : "";
        this.language = language != null ? language : "Plain Text";
    }

    public String getCode()     { return code; }
    public String getLanguage() { return language; }
}
