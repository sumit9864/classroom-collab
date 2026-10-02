package com.classroom.ui;

import org.kordamp.ikonli.javafx.FontIcon;

public class Icons {
    public static FontIcon of(String code, int size) {
        FontIcon icon = new FontIcon(code);
        icon.setIconSize(size);
        return icon;
    }
}
