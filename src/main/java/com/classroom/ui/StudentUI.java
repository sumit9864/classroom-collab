package com.classroom.ui;

import com.classroom.client.StudentClient;
import com.classroom.model.CodeData;
import com.classroom.model.FileShareData;
import com.classroom.model.Message;
import com.classroom.model.ShapeData;
import com.classroom.model.SlideData;
import com.classroom.model.StrokeData;
import com.classroom.ui.WhiteboardPane.FullState;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

public class StudentUI {

    // ── Theme constants ────────────────────────────────────────────────────
    private static final String THEME_DARK  = "/theme-dark.css";
    private static final String THEME_LIGHT = "/theme-light.css";
    private static final Color  DARK_CANVAS    = Color.web("#1a2035");
    private static final String DARK_CONTAINER = "#0d1117";
    private static final Color  LIGHT_CANVAS    = Color.WHITE;
    private static final String LIGHT_CONTAINER = "#e0e0e0";
    private static final String CODE_VIEWER_DARK  =
            "-fx-control-inner-background: #1e1e1e; -fx-text-fill: #d4d4d4; " +
            "-fx-background-color: #1e1e1e; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_NUMS_DARK    =
            "-fx-control-inner-background: #252526; -fx-text-fill: #858585; " +
            "-fx-background-color: #252526; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_AREA_DARK    = "-fx-background-color: #1e1e1e; -fx-border-width: 0;";
    private static final String CODE_VIEWER_LIGHT =
            "-fx-control-inner-background: #fafafa; -fx-text-fill: #1e1e1e; " +
            "-fx-background-color: #fafafa; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_NUMS_LIGHT   =
            "-fx-control-inner-background: #f0f0f0; -fx-text-fill: #888888; " +
            "-fx-background-color: #f0f0f0; -fx-background-insets: 0; -fx-padding: 0; " +
            "-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-border-width: 0;";
    private static final String CODE_AREA_LIGHT   = "-fx-background-color: #fafafa; -fx-border-width: 0;";

    // ── Theme state ────────────────────────────────────────────────────────
    private boolean isDarkTheme = false;
    private Scene   mainScene;

    // ── Code panel dynamic refs ────────────────────────────────────────────
    private TextArea codeViewer;
    private TextArea lineNumbers;
    private HBox     codeArea;

    // ── Core state ─────────────────────────────────────────────────────────
    private final Stage stage;
    private StudentClient client;
    private final Label statusLabel;
    private WhiteboardPane whiteboardPane;

    // Phase 3
    private ImageView  pptImageView;
    private Tab        pptTab;
    private TabPane    tabPane;
    private StackPane  pptSlidePanel;
    private WhiteboardPane pptWhiteboardPane;

    // Phase 4
    private Tab        codeTab;
    private Tab        whiteboardTab;
    private ScrollPane wbScroller; // kept as field for zoom-to-centre

    // Phase 5 — File receiving
    private Tab    fileTab;
    private VBox   fileListBox;
    private Label  fileEmptyLabel;
    private Button downloadAllBtn;          // enabled once ≥1 file is complete
    private boolean fileTabHasItems = false;
    private int forcedTabIndex = -1;

    /** Completed, ready-to-save transfers (used for the Download All as ZIP feature). */
    private final List<FileReceiveEntry> completedFiles = new ArrayList<>();

    /**
     * Tracks an in-progress file transfer on the student side.
     * All fields are accessed only on the FX thread (handleMessage is
     * dispatched via Platform.runLater in StudentClient).
     */
    private static final class FileReceiveEntry {
        final String fileName;       // original name — used by ZIP feature
        final int    totalChunks;
        int     receivedChunks = 0;
        File    tempFile;
        FileOutputStream fos;
        ProgressBar progressBar;
        Label       statusLabel;
        Button      saveButton;
        boolean     failed = false;

        FileReceiveEntry(String fileName, int totalChunks) {
            this.fileName    = fileName;
            this.totalChunks = totalChunks;
        }
    }

    /** Active transfers keyed by transferId. Accessed only on FX thread. */
    private final Map<String, FileReceiveEntry> pendingFiles = new HashMap<>();

    // ── Constructor ────────────────────────────────────────────────────────
    public StudentUI(Stage stage, StudentClient client) {
        this.stage = stage;
        this.client = client;
        this.statusLabel = new Label("Connected");
        this.statusLabel.getStyleClass().add("lbl-status-ok");
    }

    public void setClient(StudentClient client) { this.client = client; }

    // ── Theme application ──────────────────────────────────────────────────
    private void applyTheme(boolean dark) {
        isDarkTheme = dark;
        if (mainScene == null) return;
        mainScene.getStylesheets().clear();
        mainScene.getStylesheets().add(
                getClass().getResource(dark ? THEME_DARK : THEME_LIGHT).toExternalForm());
        whiteboardPane.setCanvasBgColor(dark ? DARK_CANVAS : LIGHT_CANVAS,
                                        dark ? DARK_CONTAINER : LIGHT_CONTAINER);
        pptWhiteboardPane.setCanvasBgColor(dark ? DARK_CANVAS : LIGHT_CANVAS,
                                           dark ? DARK_CONTAINER : LIGHT_CONTAINER);
        if (codeViewer  != null) codeViewer .setStyle(dark ? CODE_VIEWER_DARK : CODE_VIEWER_LIGHT);
        if (lineNumbers != null) lineNumbers.setStyle(dark ? CODE_NUMS_DARK   : CODE_NUMS_LIGHT);
        if (codeArea    != null) codeArea   .setStyle(dark ? CODE_AREA_DARK   : CODE_AREA_LIGHT);
    }

    // ── Zoom preserving viewport centre ──────────────────────────────────────
    private void zoomPane(WhiteboardPane p, double delta) {
        if (p == null || wbScroller == null) return;
        double oldZoom = p.getZoom();
        javafx.geometry.Bounds vp = wbScroller.getViewportBounds();
        double vpW = vp.getWidth(),  vpH = vp.getHeight();
        double cw  = p.getPrefWidth(), ch = p.getPrefHeight();
        double holderW = Math.max(vpW, cw * oldZoom);
        double holderH = Math.max(vpH, ch * oldZoom);
        double scrollX = wbScroller.getHvalue() * Math.max(0, holderW - vpW);
        double scrollY = wbScroller.getVvalue() * Math.max(0, holderH - vpH);
        double vcX = scrollX + vpW / 2;
        double vcY = scrollY + vpH / 2;
        double canvasLeft = Math.max(0, (holderW - cw * oldZoom) / 2);
        double canvasTop  = Math.max(0, (holderH - ch * oldZoom) / 2);
        double focusX = (vcX - canvasLeft) / oldZoom;
        double focusY = (vcY - canvasTop)  / oldZoom;
        p.setZoom(p.getZoom() + delta);
        final double nz = p.getZoom();
        javafx.application.Platform.runLater(() -> {
            double newHolderW = Math.max(vpW, cw * nz);
            double newHolderH = Math.max(vpH, ch * nz);
            double newCanvasLeft = Math.max(0, (newHolderW - cw * nz) / 2);
            double newCanvasTop  = Math.max(0, (newHolderH - ch * nz) / 2);
            double newScrollX = focusX * nz + newCanvasLeft - vpW / 2;
            double newScrollY = focusY * nz + newCanvasTop  - vpH / 2;
            wbScroller.setHvalue(Math.max(0, Math.min(1, newScrollX / Math.max(1, newHolderW - vpW))));
            wbScroller.setVvalue(Math.max(0, Math.min(1, newScrollY / Math.max(1, newHolderH - vpH))));
        });
    }

    // ── show() ─────────────────────────────────────────────────────────────
    public void show() {

        // ── THEME TOGGLE ───────────────────────────────────────────────────
        Button themeBtn = new Button("\u263E  Dark Mode");
        themeBtn.getStyleClass().add("btn-theme");
        themeBtn.setOnAction(e -> {
            isDarkTheme = !isDarkTheme;
            applyTheme(isDarkTheme);
            themeBtn.setText(isDarkTheme ? "\u2600  Light Mode" : "\u263E  Dark Mode");
        });

        // ── TOP BAR ───────────────────────────────────────────────────────
        Label titleLabel = new Label("Classroom Collaboration");
        titleLabel.getStyleClass().add("lbl-topbar");

        Separator titleSep = new Separator(javafx.geometry.Orientation.VERTICAL);
        titleSep.setPadding(new Insets(0, 4, 0, 4));

        Label roleLabel = new Label("Student");
        roleLabel.getStyleClass().add("role-student");
        HBox.setHgrow(roleLabel, Priority.ALWAYS);

        Button disconnectButton = new Button("Disconnect");
        disconnectButton.getStyleClass().add("btn-danger");
        disconnectButton.setOnAction(e -> {
            if (client != null) client.disconnect();
            stage.close();
        });

        HBox topBar = new HBox(10, titleLabel, titleSep, roleLabel, themeBtn, disconnectButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");

        // ── WHITEBOARDS ────────────────────────────────────────────────────
        whiteboardPane    = new WhiteboardPane(false, null);
        pptWhiteboardPane = new WhiteboardPane(false, null);
        pptWhiteboardPane.setTransparentBackground(true);

        // ── TAB 1: WHITEBOARD ──────────────────────────────────────────────
        javafx.scene.Group canvasGroup = new javafx.scene.Group(whiteboardPane);
        // Centering holder: always at least as large as the viewport so the
        // canvas stays centred; grows beyond viewport when zoomed in (scrollbars appear).
        javafx.scene.layout.StackPane centeredHolder = new javafx.scene.layout.StackPane(canvasGroup);
        centeredHolder.setAlignment(Pos.CENTER);
        centeredHolder.getStyleClass().add("canvas-holder");
        wbScroller = new ScrollPane(centeredHolder);
        wbScroller.setPannable(false); // prevent drag-to-scroll during freehand drawing
        wbScroller.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-background-color: transparent;");
        // Keep canvas centred whenever the viewport is resized
        wbScroller.viewportBoundsProperty().addListener((obs, old, b) -> {
            centeredHolder.setMinWidth(b.getWidth());
            centeredHolder.setMinHeight(b.getHeight());
        });
        whiteboardTab = new Tab("  Whiteboard  ", wbScroller);
        whiteboardTab.setClosable(false);

        // ── TAB 2: PPT SLIDE ───────────────────────────────────────────────
        pptImageView = new ImageView();
        pptImageView.setPreserveRatio(true);
        pptImageView.setSmooth(true);

        Label waitingLabel = new Label("Waiting for teacher to share a slide...");
        waitingLabel.getStyleClass().add("lbl-muted");

        pptSlidePanel = new StackPane();
        pptSlidePanel.getStyleClass().add("ppt-center");
        pptSlidePanel.setAlignment(Pos.CENTER);
        pptSlidePanel.getChildren().add(waitingLabel);
        pptImageView.fitWidthProperty().bind(pptSlidePanel.widthProperty());
        pptImageView.fitHeightProperty().bind(pptSlidePanel.heightProperty());

        pptTab = new Tab("  PPT Slide  ", pptSlidePanel);
        pptTab.setClosable(false);

        // ── TAB 3: CODE ────────────────────────────────────────────────────
        codeViewer = new TextArea();
        codeViewer.setEditable(false);
        codeViewer.setPromptText("Waiting for teacher to share code...");
        codeViewer.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        codeViewer.setWrapText(false);
        codeViewer.setStyle(CODE_VIEWER_LIGHT);
        HBox.setHgrow(codeViewer, Priority.ALWAYS);

        lineNumbers = new TextArea("1");
        lineNumbers.setEditable(false);
        lineNumbers.setFocusTraversable(false);
        lineNumbers.setPrefWidth(45);
        lineNumbers.setMinWidth(45);
        lineNumbers.setMaxWidth(45);
        lineNumbers.setFont(javafx.scene.text.Font.font("Monospaced", 14));
        lineNumbers.setWrapText(false);
        lineNumbers.setStyle(CODE_NUMS_LIGHT);

        codeViewer.textProperty().addListener((obs, old, text) -> {
            String[] lines = text.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= lines.length; i++) { if (i > 1) sb.append("\n"); sb.append(i); }
            lineNumbers.setText(sb.toString());
        });

        codeArea = new HBox(lineNumbers, codeViewer);
        codeArea.setStyle(CODE_AREA_LIGHT);
        VBox.setVgrow(codeArea, Priority.ALWAYS);

        Button copyBtn = new Button("⎘  Copy to Clipboard");
        copyBtn.getStyleClass().add("btn-copy");

        PauseTransition copyReset = new PauseTransition(Duration.seconds(2));
        copyReset.setOnFinished(ev -> {
            copyBtn.setText("⎘  Copy to Clipboard");
            copyBtn.getStyleClass().setAll("button", "btn-copy");
        });
        copyBtn.setOnAction(e -> {
            javafx.scene.input.ClipboardContent clip = new javafx.scene.input.ClipboardContent();
            clip.putString(codeViewer.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
            copyBtn.setText("✓  Copied!");
            copyBtn.getStyleClass().setAll("button", "btn-copy-success");
            copyReset.playFromStart();
        });

        HBox codeToolbar = new HBox(copyBtn);
        codeToolbar.setAlignment(Pos.CENTER_LEFT);
        codeToolbar.getStyleClass().add("code-toolbar");

        VBox codeTabContent = new VBox(codeToolbar, codeArea);
        VBox.setVgrow(codeTabContent, Priority.ALWAYS);
        codeTab = new Tab("  Code  ", codeTabContent);
        codeTab.setClosable(false);

        // ── TAB 4: FILES ───────────────────────────────────────────────────
        fileTab = buildFileTab();

        // ── TABPANE ────────────────────────────────────────────────────────
        tabPane = new TabPane(whiteboardTab, pptTab, codeTab, fileTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (forcedTabIndex != -1 && tabPane.getSelectionModel().getSelectedIndex() != forcedTabIndex) {
                javafx.application.Platform.runLater(() -> tabPane.getSelectionModel().select(forcedTabIndex));
            }
        });

        // ── BOTTOM STATUS BAR ──────────────────────────────────────────────
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button zoomInBtn  = new Button("Zoom +");
        Button zoomOutBtn = new Button("Zoom \u2212");
        zoomInBtn.setOnAction(e -> {
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel == codeTab || sel == fileTab) return;
            WhiteboardPane active = (sel == pptTab) ? pptWhiteboardPane : whiteboardPane;
            if (sel == whiteboardTab) {
                zoomPane(active, 0.1);
            } else {
                active.setZoom(active.getZoom() + 0.1);
            }
        });
        zoomOutBtn.setOnAction(e -> {
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel == codeTab || sel == fileTab) return;
            WhiteboardPane active = (sel == pptTab) ? pptWhiteboardPane : whiteboardPane;
            if (sel == whiteboardTab) {
                zoomPane(active, -0.1);
            } else {
                active.setZoom(active.getZoom() - 0.1);
            }
        });

        Label dotLabel = new Label("●");
        dotLabel.getStyleClass().add("text-success");

        HBox statusBar = new HBox(8, dotLabel, statusLabel, spacer, zoomInBtn, zoomOutBtn);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        // ── ROOT ───────────────────────────────────────────────────────────
        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(tabPane);
        root.setBottom(statusBar);

        stage.setOnCloseRequest(e -> { if (client != null) client.disconnect(); });
        stage.setMinWidth(800);
        stage.setMinHeight(540);

        if (client != null) {
            client.setOnDisconnect(() -> {
                if (stage.isShowing()) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Connection Lost");
                    alert.setHeaderText("Disconnected from teacher");
                    alert.setContentText("The session has ended or the network connection was lost.");
                    alert.getDialogPane().getStylesheets().add(
                            getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
                    alert.showAndWait();
                    stage.close();
                }
            });
        }

        mainScene = stage.getScene();
        if (mainScene != null) {
            mainScene.setRoot(root);
            mainScene.getStylesheets().clear();
            mainScene.getStylesheets().add(getClass().getResource(THEME_LIGHT).toExternalForm());
        } else {
            Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
            mainScene = new Scene(root, screenBounds.getWidth(), screenBounds.getHeight());
            mainScene.getStylesheets().add(getClass().getResource(THEME_LIGHT).toExternalForm());
            stage.setScene(mainScene);
        }

        whiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);
        pptWhiteboardPane.setCanvasBgColor(LIGHT_CANVAS, LIGHT_CONTAINER);

        stage.setTitle("Classroom Collaboration — Student");
        stage.setMaximized(true);
        stage.show();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FILE RECEIVING — Tab builder + message handlers
    // ════════════════════════════════════════════════════════════════════════

    /** Builds the Files tab (empty state — populated as files arrive). */
    private Tab buildFileTab() {
        Label headerLbl = new Label("Files from Teacher");
        headerLbl.getStyleClass().add("lbl-panel-header");

        Label subLbl = new Label("Files shared during this session appear here.");
        subLbl.getStyleClass().add("lbl-subtitle");
        HBox.setHgrow(subLbl, Priority.ALWAYS);

        downloadAllBtn = new Button("\u2B07  Download All as ZIP");
        downloadAllBtn.getStyleClass().add("btn-primary");
        downloadAllBtn.setDisable(true);
        downloadAllBtn.setOnAction(e -> downloadAllAsZip());

        HBox hdr = new HBox(10, headerLbl, subLbl, downloadAllBtn);
        hdr.setAlignment(Pos.CENTER_LEFT);
        hdr.getStyleClass().add("ppt-controls");

        fileEmptyLabel = new Label("No files shared yet.\nFiles sent by the teacher will appear here automatically.");
        fileEmptyLabel.getStyleClass().add("lbl-muted");
        fileEmptyLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        fileListBox = new VBox(6);
        fileListBox.setPadding(new Insets(10));
        fileListBox.setAlignment(Pos.TOP_LEFT);
        fileListBox.getChildren().add(fileEmptyLabel);

        ScrollPane fileScroll = new ScrollPane(fileListBox);
        fileScroll.setFitToWidth(true);
        fileScroll.setStyle("-fx-focus-color: transparent; -fx-faint-focus-color: transparent;");

        VBox panel = new VBox(hdr, fileScroll);
        VBox.setVgrow(fileScroll, Priority.ALWAYS);

        Tab tab = new Tab("  \uD83D\uDCC1 Files  ", panel);
        tab.setClosable(false);
        return tab;
    }

    /**
     * Called when FILE_SHARE_START arrives.
     * Creates a temp file, opens its FileOutputStream, and adds a UI row.
     * All I/O errors are caught and surfaced in the UI — no exception propagates.
     */
    private void handleFileStart(FileShareData data) {
        String transferId = data.getTransferId();
        String fileName   = data.getFileName();
        long   totalBytes = data.getTotalBytes();
        int totalChunks   = data.getTotalChunks();

        // Guard: ignore duplicate START for same transferId
        if (pendingFiles.containsKey(transferId)) return;

        // Build UI row first (always shown, even if temp file creation fails)
        Label nameLbl = new Label(fileName);
        nameLbl.setMaxWidth(320);
        nameLbl.setTooltip(new Tooltip(fileName + " \u2014 " + formatFileSize(totalBytes)));

        Label sizeLbl = new Label(formatFileSize(totalBytes));
        sizeLbl.getStyleClass().add("lbl-section");
        sizeLbl.setMinWidth(75);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(140);

        Label statusLbl = new Label("Receiving...");
        statusLbl.getStyleClass().add("lbl-subtitle");

        Button saveBtn = new Button("\uD83D\uDCBE  Save File");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setVisible(false);
        saveBtn.setManaged(false);

        HBox row = new HBox(12, nameLbl, sizeLbl, progressBar, statusLbl, saveBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));

        if (!fileTabHasItems) {
            fileTabHasItems = true;
            fileListBox.getChildren().remove(fileEmptyLabel);
        }
        fileListBox.getChildren().add(row);


        // Create entry
        FileReceiveEntry entry = new FileReceiveEntry(fileName, totalChunks);
        entry.progressBar = progressBar;
        entry.statusLabel = statusLbl;
        entry.saveButton  = saveBtn;

        // Try to create temp file
        try {
            // Use a safe prefix — strip any path separators from fileName
            String safeBase = fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
            entry.tempFile = File.createTempFile("cc_recv_", "_" + safeBase);
            entry.tempFile.deleteOnExit(); // clean up on JVM exit
            entry.fos = new FileOutputStream(entry.tempFile);
        } catch (IOException ex) {
            entry.failed = true;
            statusLbl.setText("\u2717 Cannot write temp file: " + ex.getMessage());
            statusLbl.getStyleClass().setAll("text-error");
        }

        pendingFiles.put(transferId, entry);

        // Wire save button (captured variables are effectively final via entry reference)
        saveBtn.setOnAction(e -> saveFile(entry, fileName));
    }

    /**
     * Called when FILE_CHUNK arrives.
     * Writes the chunk bytes to the temp file. On error, marks entry as failed.
     * Called on the FX thread — FileOutputStream writes are fast for 256 KB chunks
     * on modern hardware and do not cause noticeable jank on LAN.
     */
    private void handleFileChunk(FileShareData data) {
        FileReceiveEntry entry = pendingFiles.get(data.getTransferId());
        if (entry == null || entry.failed) return; // no START received or already failed

        byte[] chunkData = data.getChunkData();
        if (chunkData == null || chunkData.length == 0) return; // malformed chunk, skip

        try {
            entry.fos.write(chunkData);
            entry.receivedChunks++;
            int total = data.getTotalChunks() > 0 ? data.getTotalChunks() : entry.totalChunks;
            double progress = total > 0 ? (double) entry.receivedChunks / total : 0;
            entry.progressBar.setProgress(Math.min(progress, 1.0));
        } catch (IOException ex) {
            entry.failed = true;
            entry.statusLabel.setText("\u2717 Write error: " + ex.getMessage());
            entry.statusLabel.getStyleClass().setAll("text-error");
            closeFos(entry);
        }
    }

    /**
     * Called when FILE_SHARE_COMPLETE arrives.
     * Closes the FileOutputStream and either shows the Save button or an error label.
     */
    private void handleFileComplete(FileShareData data) {
        FileReceiveEntry entry = pendingFiles.remove(data.getTransferId());
        if (entry == null) return; // no matching START — ignore

        closeFos(entry); // safe to call even if already closed

        if (entry.failed) {
            entry.progressBar.setProgress(0);
            // statusLabel already set by handleFileChunk's error handler
            return;
        }

        entry.progressBar.setProgress(1.0);
        entry.statusLabel.setText("\u2713 Ready to save");
        entry.statusLabel.getStyleClass().setAll("text-success-bold");
        entry.saveButton.setVisible(true);
        entry.saveButton.setManaged(true);

        // Register in completed list and enable the Download All button
        completedFiles.add(entry);
        if (downloadAllBtn != null) downloadAllBtn.setDisable(false);
    }

    /**
     * Zips all successfully received files into a single archive chosen by the user.
     * Runs on the FX thread — file I/O is fast for session-sized batches.
     * Duplicate file names are disambiguated with a numeric suffix.
     */
    private void downloadAllAsZip() {
        // Filter to entries that still have their temp file on disk
        List<FileReceiveEntry> ready = new ArrayList<>();
        for (FileReceiveEntry e : completedFiles) {
            if (!e.failed && e.tempFile != null && e.tempFile.exists()) ready.add(e);
        }
        if (ready.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Nothing to Download",
                    "No completed files available",
                    "All temp files may have been cleaned up. Try saving files individually.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Save All Files as ZIP");
        fc.setInitialFileName("classroom_files.zip");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP Archive", "*.zip"));
        File dest = fc.showSaveDialog(stage);
        if (dest == null) return; // user cancelled

        // Ensure the file ends with .zip
        if (!dest.getName().toLowerCase().endsWith(".zip")) {
            dest = new File(dest.getParentFile(), dest.getName() + ".zip");
        }

        // Track used names within the ZIP to handle duplicates
        Map<String, Integer> nameCount = new HashMap<>();
        int failures = 0;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dest))) {
            for (FileReceiveEntry entry : ready) {
                String name = entry.fileName;
                // Disambiguate duplicate names: "file.txt" → "file (2).txt"
                int count = nameCount.getOrDefault(name, 0) + 1;
                nameCount.put(name, count);
                if (count > 1) {
                    String ext = getExtension(name);
                    String base = ext.isEmpty() ? name : name.substring(0, name.length() - ext.length() - 1);
                    name = base + " (" + count + ")" + (ext.isEmpty() ? "" : "." + ext);
                }
                try {
                    zos.putNextEntry(new ZipEntry(name));
                    Files.copy(entry.tempFile.toPath(), zos);
                    zos.closeEntry();
                } catch (IOException ex) {
                    failures++;
                }
            }
        } catch (IOException ex) {
            showAlert(Alert.AlertType.ERROR, "ZIP Failed",
                    "Could not create ZIP file",
                    "Error: " + ex.getMessage());
            return;
        }

        String msg = ready.size() + " file(s) saved to: " + dest.getName();
        if (failures > 0) msg += "\n(" + failures + " file(s) could not be added)";
        showAlert(Alert.AlertType.INFORMATION, "Download Complete", "ZIP archive saved", msg);
    }

    /**
     * Opens a Save dialog and copies the temp file to the chosen destination.
     * Handles all I/O errors with an alert so no exception propagates to the FX thread.
     */
    private void saveFile(FileReceiveEntry entry, String originalFileName) {
        if (entry.tempFile == null || !entry.tempFile.exists()) {
            showAlert(Alert.AlertType.ERROR, "Save Failed",
                    "Temp file not found",
                    "The temporary download file is missing. The transfer may have failed.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Save File As");
        fc.setInitialFileName(originalFileName);

        // Offer the file's own extension as the first filter so the OS pre-selects it
        String ext = getExtension(originalFileName);
        if (!ext.isEmpty()) {
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(ext.toUpperCase() + " File", "*." + ext));
        }
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("All Files", "*.*"));

        File dest = fc.showSaveDialog(stage);
        if (dest == null) return; // user cancelled

        try {
            Files.copy(entry.tempFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            entry.saveButton.setText("\u2713  Saved");
            entry.saveButton.setDisable(true);
            entry.statusLabel.setText("\u2713 Saved to: " + dest.getName());
        } catch (IOException ex) {
            showAlert(Alert.AlertType.ERROR, "Save Failed",
                    "Could not save the file",
                    "Error: " + ex.getMessage() + "\n\nCheck that you have write permission to that location.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Message dispatcher (called on FX thread via Platform.runLater)
    // ════════════════════════════════════════════════════════════════════════

    public void handleMessage(Message msg) {
        boolean isPpt = "Teacher_PPT".equals(msg.getSenderName());
        WhiteboardPane targetPane = isPpt ? pptWhiteboardPane : whiteboardPane;

        switch (msg.getType()) {

            // ── Session control ────────────────────────────────────────────
            case STUDENT_LIST_UPDATE:
                System.out.println("[StudentUI] Student list updated: " + msg.getPayload());
                break;
            case HEARTBEAT:
                break; // keep-alive — no action needed
            case DISCONNECT:
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Session Ended");
                alert.setHeaderText(null);
                alert.setContentText("Session ended by teacher.");
                alert.getDialogPane().getStylesheets().add(
                        getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
                alert.showAndWait();
                stage.close();
                break;

            // ── Whiteboard & annotation ────────────────────────────────────
            case STROKE_PROGRESS:
                if (targetPane != null) targetPane.applyStrokeProgress((StrokeData) msg.getPayload()); break;
            case WHITEBOARD_STROKE:
                if (targetPane != null) targetPane.applyStroke((StrokeData) msg.getPayload()); break;
            case ANNOTATION_STROKE:
                if (targetPane != null) targetPane.applyStroke((StrokeData) msg.getPayload()); break;
            case WHITEBOARD_CLEAR:
                if (targetPane != null) targetPane.clearWhiteboard(); break;
            case ANNOTATION_CLEAR:
                if (targetPane != null) targetPane.clearAnnotations(); break;
            case UNDO:
                if (targetPane != null) targetPane.undo(); break;
            case REDO:
                if (targetPane != null) targetPane.redo(); break;
            case CANVAS_RESIZE:
                if (targetPane != null) {
                    double[] size = (double[]) msg.getPayload();
                    targetPane.setCanvasSize(size[0], size[1]);
                }
                break;
            case SHAPE_ADD:
                if (targetPane != null) targetPane.addShape((ShapeData) msg.getPayload()); break;
            case SHAPE_UPDATE:
                if (targetPane != null) targetPane.updateShape((ShapeData) msg.getPayload()); break;
            case SHAPE_REMOVE:
                if (targetPane != null) targetPane.removeShape((String) msg.getPayload()); break;
            case FULL_STATE:
                if (targetPane != null) targetPane.applyFullState((FullState) msg.getPayload()); break;

            // ── PPT Sharing ────────────────────────────────────────────────
            case PPT_SLIDE:
                SlideData sd = (SlideData) msg.getPayload();
                Image fxImg = new Image(new ByteArrayInputStream(sd.getImageBytes()));
                pptImageView.setImage(fxImg);
                if (!pptSlidePanel.getChildren().contains(pptImageView)) {
                    pptSlidePanel.getChildren().clear();
                    javafx.scene.Group overlayGroup = new javafx.scene.Group(pptWhiteboardPane);
                    pptSlidePanel.getChildren().addAll(pptImageView, overlayGroup);
                }

                break;

            // ── Code Sharing ───────────────────────────────────────────────
            case CODE_SHARE:
                CodeData cd = (CodeData) msg.getPayload();
                codeViewer.setText(cd.getCode());
                break;

            // ── File Sharing (Phase 5) ─────────────────────────────────────
            case FILE_SHARE_START:
                handleFileStart((FileShareData) msg.getPayload());
                break;
            case FILE_CHUNK:
                handleFileChunk((FileShareData) msg.getPayload());
                break;
            case FILE_SHARE_COMPLETE:
                handleFileComplete((FileShareData) msg.getPayload());
                break;

            // ── Tab Sync (Phase 6) ─────────────────────────────────────────
            case TAB_SWITCH:
                int tabIndex = (Integer) msg.getPayload();
                forcedTabIndex = tabIndex;
                if (tabIndex != -1) {
                    tabPane.getSelectionModel().select(tabIndex);
                }
                break;

            default:
                System.out.println("[StudentUI] Unhandled message: " + msg.getType());
                break;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Safely closes the FileOutputStream for the given entry (idempotent). */
    private static void closeFos(FileReceiveEntry entry) {
        if (entry.fos != null) {
            try { entry.fos.close(); } catch (IOException ex) { /* ignore */ }
            entry.fos = null;
        }
    }

    /** Returns the lowercase extension of a filename, or "" if none. */
    private static String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    /** Human-readable file size string. */
    private static String formatFileSize(long bytes) {
        if (bytes < 1024L)                return bytes + " B";
        if (bytes < 1024L * 1024)         return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)  return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** Shows a themed alert. Must be called on the FX thread. */
    private void showAlert(Alert.AlertType type, String title, String header, String content) {
        Alert a = new Alert(type);
        a.setTitle(title);
        a.setHeaderText(header);
        a.setContentText(content);
        a.getDialogPane().getStylesheets().add(
                getClass().getResource(isDarkTheme ? THEME_DARK : THEME_LIGHT).toExternalForm());
        a.showAndWait();
    }
}
