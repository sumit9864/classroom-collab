package com.classroom;

import com.classroom.ui.LoginScreen;
import javafx.application.Application;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Classroom Collaboration");
        primaryStage.setScene(new LoginScreen().buildScene(primaryStage));
        primaryStage.setWidth(480);
        primaryStage.setHeight(360);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
