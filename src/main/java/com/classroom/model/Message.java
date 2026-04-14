package com.classroom.model;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    private MessageType type;    // What kind of message this is
    private Object payload;      // Data carried by the message (String, List, byte[], etc.)
    private String senderName;   // Name of sender (student name or "Teacher")

    public Message(MessageType type, Object payload, String senderName) {
        this.type = type;
        this.payload = payload;
        this.senderName = senderName;
    }

    public MessageType getType() {
        return type;
    }

    public Object getPayload() {
        return payload;
    }

    public String getSenderName() {
        return senderName;
    }
}
