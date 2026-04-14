package com.classroom.ui;

import com.classroom.client.StudentClient;
import com.classroom.server.TeacherServer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

public class LoginScreen {

    public Scene buildScene(Stage primaryStage) {

        // ── Title ──────────────────────────────────────────────────────────
        Label titleLabel = new Label("Classroom Collaboration");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 22));

        // ── Role selection ─────────────────────────────────────────────────
        ToggleGroup roleGroup = new ToggleGroup();

        RadioButton hostRadio = new RadioButton("Host a Session  (Teacher)");
        hostRadio.setToggleGroup(roleGroup);
        hostRadio.setSelected(true);

        RadioButton joinRadio = new RadioButton("Join a Session  (Student)");
        joinRadio.setToggleGroup(roleGroup);

        HBox roleBox = new HBox(20, hostRadio, joinRadio);
        roleBox.setAlignment(Pos.CENTER);

        // ── Dynamic form area ──────────────────────────────────────────────
        VBox formArea = new VBox(10);
        formArea.setAlignment(Pos.CENTER_LEFT);
        formArea.setPadding(new Insets(0, 40, 0, 40));

        // Host fields
        TextField hostPortField = new TextField("5000");
        hostPortField.setMaxWidth(200);
        VBox hostForm = new VBox(6,
                new Label("Port:"), hostPortField);
        hostForm.setAlignment(Pos.CENTER_LEFT);

        // Join fields
        TextField joinIpField   = new TextField();
        joinIpField.setPromptText("Teacher's IP");
        joinIpField.setMaxWidth(200);

        TextField joinPortField = new TextField("5000");
        joinPortField.setMaxWidth(200);

        TextField joinNameField = new TextField();
        joinNameField.setPromptText("Your name");
        joinNameField.setMaxWidth(200);

        VBox joinForm = new VBox(6,
                new Label("Teacher IP:"), joinIpField,
                new Label("Port:"),       joinPortField,
                new Label("Your Name:"), joinNameField);
        joinForm.setAlignment(Pos.CENTER_LEFT);

        // Start with host form shown
        formArea.getChildren().add(hostForm);

        roleGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            formArea.getChildren().clear();
            if (newT == hostRadio) {
                formArea.getChildren().add(hostForm);
            } else {
                formArea.getChildren().add(joinForm);
            }
        });

        // ── Action button ──────────────────────────────────────────────────
        Button actionButton = new Button("Start Session");
        actionButton.setDefaultButton(true);
        actionButton.setPrefWidth(160);

        hostRadio.selectedProperty().addListener((obs, was, isNow) ->
                actionButton.setText(isNow ? "Start Session" : "Connect"));
        joinRadio.selectedProperty().addListener((obs, was, isNow) ->
                actionButton.setText(isNow ? "Connect" : "Start Session"));

        actionButton.setOnAction(e -> {
            if (roleGroup.getSelectedToggle() == hostRadio) {
                handleHost(primaryStage, hostPortField);
            } else {
                handleJoin(primaryStage, joinIpField, joinPortField, joinNameField);
            }
        });

        // ── Root layout ────────────────────────────────────────────────────
        VBox root = new VBox(20,
                titleLabel,
                new Separator(),
                roleBox,
                formArea,
                actionButton);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));

        return new Scene(root, 480, 360);
    }

    // ── Host handler ───────────────────────────────────────────────────────
    private void handleHost(Stage primaryStage, TextField portField) {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Invalid Port", "Please enter a valid port number (e.g. 5000).");
            return;
        }

        TeacherUI teacherUI = new TeacherUI(primaryStage, null); // server set below
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

    // ── Join handler ───────────────────────────────────────────────────────
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

        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            showError("Invalid Port", "Please enter a valid port number (e.g. 5000).");
            return;
        }

        StudentUI studentUI = new StudentUI(primaryStage, null); // client set below
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

    // ── Helpers ────────────────────────────────────────────────────────────
    private void showError(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
