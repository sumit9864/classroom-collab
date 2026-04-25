package com.classroom.ui;

import com.classroom.client.StudentClient;
import com.classroom.server.TeacherServer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

public class LoginScreen {

    public Scene buildScene(Stage primaryStage) {

        // ── App Branding ───────────────────────────────────────────────────
        Label logoLabel = new Label("CC");
        logoLabel.getStyleClass().add("login-logo-label");

        HBox logoBox = new HBox(logoLabel);
        logoBox.getStyleClass().add("login-logo-box");
        logoBox.setAlignment(Pos.CENTER);

        Label appName = new Label("Classroom Collaboration");
        appName.getStyleClass().add("login-app-name");

        Label appDesc = new Label("LAN-based real-time teaching platform");
        appDesc.getStyleClass().add("login-app-desc");

        VBox brandingBox = new VBox(8, logoBox, appName, appDesc);
        brandingBox.setAlignment(Pos.CENTER);

        // ── Role Selection (styled ToggleButtons) ──────────────────────────
        ToggleGroup roleGroup = new ToggleGroup();

        ToggleButton hostBtn = new ToggleButton("Teacher\nHost a Session");
        hostBtn.setToggleGroup(roleGroup);
        hostBtn.setSelected(true);
        hostBtn.getStyleClass().add("role-toggle");
        hostBtn.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        ToggleButton joinBtn = new ToggleButton("Student\nJoin a Session");
        joinBtn.setToggleGroup(roleGroup);
        joinBtn.getStyleClass().add("role-toggle");
        joinBtn.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        // Prevent deselecting both buttons
        roleGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) oldT.setSelected(true);
        });

        HBox roleBox = new HBox(12, hostBtn, joinBtn);
        roleBox.setAlignment(Pos.CENTER);

        // ── Dynamic Form Area ──────────────────────────────────────────────
        VBox formArea = new VBox(10);
        formArea.setAlignment(Pos.CENTER_LEFT);

        // Host form
        Label portFieldLabel = new Label("Port");
        portFieldLabel.getStyleClass().add("login-field-label");
        TextField hostPortField = new TextField("5000");
        hostPortField.getStyleClass().add("login-field");
        VBox hostForm = new VBox(6, portFieldLabel, hostPortField);
        hostForm.setAlignment(Pos.CENTER_LEFT);

        // Join form
        Label ipLbl = new Label("Teacher's IP Address");
        ipLbl.getStyleClass().add("login-field-label");
        TextField joinIpField = new TextField();
        joinIpField.setPromptText("e.g. 192.168.1.5");
        joinIpField.getStyleClass().add("login-field");

        Label joinPortLbl = new Label("Port");
        joinPortLbl.getStyleClass().add("login-field-label");
        TextField joinPortField = new TextField("5000");
        joinPortField.getStyleClass().add("login-field");

        Label joinNameLbl = new Label("Your Name");
        joinNameLbl.getStyleClass().add("login-field-label");
        TextField joinNameField = new TextField();
        joinNameField.setPromptText("Enter your display name");
        joinNameField.getStyleClass().add("login-field");

        VBox joinForm = new VBox(6,
                ipLbl,       joinIpField,
                joinPortLbl, joinPortField,
                joinNameLbl, joinNameField);
        joinForm.setAlignment(Pos.CENTER_LEFT);

        formArea.getChildren().add(hostForm);

        roleGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) return;
            formArea.getChildren().clear();
            if (newT == hostBtn) formArea.getChildren().add(hostForm);
            else                 formArea.getChildren().add(joinForm);
        });

        // ── Action Button ──────────────────────────────────────────────────
        Button actionButton = new Button("Start Session");
        actionButton.setDefaultButton(true);
        actionButton.getStyleClass().add("btn-primary");
        actionButton.setMaxWidth(Double.MAX_VALUE);

        hostBtn.selectedProperty().addListener((obs, was, isNow) ->
                actionButton.setText(isNow ? "Start Session" : "Connect to Classroom"));
        joinBtn.selectedProperty().addListener((obs, was, isNow) ->
                actionButton.setText(isNow ? "Connect to Classroom" : "Start Session"));

        actionButton.setOnAction(e -> {
            if (roleGroup.getSelectedToggle() == hostBtn) {
                handleHost(primaryStage, hostPortField);
            } else {
                handleJoin(primaryStage, joinIpField, joinPortField, joinNameField);
            }
        });

        // ── Card Layout ────────────────────────────────────────────────────
        VBox card = new VBox(20,
                brandingBox,
                new Separator(),
                roleBox,
                formArea,
                new Separator(),
                actionButton);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("login-card");

        StackPane root = new StackPane(card);
        root.getStyleClass().add("login-bg");
        root.setPadding(new Insets(40));

        Scene scene = new Scene(root, 500, 560);
        scene.getStylesheets().add(
                getClass().getResource("/theme-light.css").toExternalForm());
        return scene;
    }

    // ── Host Handler ───────────────────────────────────────────────────────
    private void handleHost(Stage primaryStage, TextField portField) {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Invalid Port", "Please enter a valid port number (e.g. 5000).");
            return;
        }
        if (port < 1025 || port > 65535) {
            showError("Invalid Port", "Port must be between 1025 and 65535.");
            return;
        }
        TeacherUI teacherUI = new TeacherUI(primaryStage, null);
        TeacherServer server = new TeacherServer(port, teacherUI::refreshStudentList);
        teacherUI.setServer(server);
        try {
            server.start();
        } catch (Exception ex) {
            showError("Server Error", ex.getMessage());
            return;
        }
        teacherUI.show();
    }

    // ── Join Handler ───────────────────────────────────────────────────────
    private void handleJoin(Stage primaryStage, TextField ipField,
                            TextField portField, TextField nameField) {
        String ip   = ipField.getText().trim();
        String name = nameField.getText().trim();
        if (ip.isEmpty()) {
            showError("Missing IP", "Please enter the teacher's IP address.");
            return;
        }
        if (name.isEmpty()) {
            showError("Missing Name", "Please enter your name.");
            return;
        }
        if (name.equalsIgnoreCase("Teacher") || name.equalsIgnoreCase("Teacher_PPT")) {
            showError("Reserved Name",
                "\"Teacher\" and \"Teacher_PPT\" are reserved names. Please choose a different name.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Invalid Port", "Please enter a valid port number (e.g. 5000).");
            return;
        }
        if (port < 1025 || port > 65535) {
            showError("Invalid Port", "Port must be between 1025 and 65535.");
            return;
        }
        StudentUI studentUI = new StudentUI(primaryStage, null);
        StudentClient client = new StudentClient(ip, port, name, studentUI::handleMessage);
        studentUI.setClient(client);
        try {
            client.connect();
        } catch (Exception ex) {
            showError("Connection Failed", ex.getMessage());
            return;
        }
        studentUI.show();
    }

    // ── Error Dialog ───────────────────────────────────────────────────────
    private void showError(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/theme-light.css").toExternalForm());
        alert.showAndWait();
    }
}
