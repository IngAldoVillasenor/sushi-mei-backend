package com.cardovia.merkon.backend.whatsapp;

public enum InboundMessageFailureStage {
    RECORD_INBOUND,
    HANDLE_MESSAGE,
    SEND_RESPONSE,
    MARK_COMPLETED
}
