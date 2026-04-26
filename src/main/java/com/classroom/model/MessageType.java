package com.classroom.model;

public enum MessageType {
    AUTH_REQUEST,        // Student → Teacher: join request with student name
    AUTH_SUCCESS,        // Teacher → Student: join accepted
    STUDENT_LIST_UPDATE, // Teacher → All: updated list of connected student names
    DISCONNECT,          // Either direction: graceful disconnect notice
    HEARTBEAT,           // Either direction: keep-alive ping (Phase 5 use)

    // Phase 2 — Whiteboard & Annotation
    WHITEBOARD_STROKE,   // Teacher → All Students: StrokeData payload for whiteboard layer
    WHITEBOARD_CLEAR,    // Teacher → All Students: null payload, clear whiteboard canvas
    ANNOTATION_STROKE,   // Teacher → All Students: StrokeData payload for annotation layer
    ANNOTATION_CLEAR,    // Teacher → All Students: null payload, clear annotation canvas
    UNDO,                // Teacher → All Students: null payload, undo last action
    REDO,                // Teacher → All Students: null payload, redo last undone action
    CANVAS_RESIZE,       // Teacher → All Students: double[] {w, h} payload, resize canvas
    SHAPE_ADD,           // Teacher → All Students: ShapeData payload, new shape placed
    SHAPE_UPDATE,        // Teacher → All Students: ShapeData payload, shape moved/resized
    SHAPE_REMOVE,        // Teacher → All Students: String id payload, shape deleted
    FULL_STATE,          // Teacher → New Student: WhiteboardPane.FullState snapshot for late-join sync
    STROKE_PROGRESS,     // Teacher → All Students: StrokeData payload for an in-progress stroke (never added to history)

    // Phase 3 — PPT Sharing
    PPT_SLIDE,           // Teacher → All Students: SlideData payload (PNG bytes + index + total)

    // Phase 4 — Code Sharing
    CODE_SHARE,          // Teacher → All Students (and late-join sync): CodeData payload

    // Phase 5 — File Sharing
    FILE_SHARE_START,    // Teacher → All Students: FileShareData metadata (fileName, size, totalChunks)
    FILE_CHUNK,          // Teacher → All Students: FileShareData with one chunk of file bytes
    FILE_SHARE_COMPLETE, // Teacher → All Students: FileShareData signals transfer finished

    // Phase 6 — Tab Sync
    TAB_SWITCH           // Teacher → All Students: Integer index of the currently active tab (or -1 to unlock)
}
