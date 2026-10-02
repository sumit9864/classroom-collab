package com.classroom.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.application.Platform;

public class ToastHelper {
    public static void showToast(StackPane toastPane, String message) {
        if (toastPane == null) return;
        Platform.runLater(() -> {
            Label toastLabel = new Label(message);
            toastLabel.getStyleClass().add("toast-label");
            
            StackPane.setAlignment(toastLabel, Pos.BOTTOM_CENTER);
            StackPane.setMargin(toastLabel, new Insets(0, 0, 40, 0));
            
            toastPane.getChildren().add(toastLabel);
            
            FadeTransition ft = new FadeTransition(Duration.millis(300), toastLabel);
            ft.setFromValue(0);
            ft.setToValue(1);
            ft.setDelay(Duration.millis(100));
            ft.play();
            
            PauseTransition pt = new PauseTransition(Duration.seconds(2.5));
            pt.setOnFinished(e -> {
                FadeTransition fadeOut = new FadeTransition(Duration.millis(300), toastLabel);
                fadeOut.setFromValue(1);
                fadeOut.setToValue(0);
                fadeOut.setOnFinished(e2 -> toastPane.getChildren().remove(toastLabel));
                fadeOut.play();
            });
            pt.play();
        });
    }
}
