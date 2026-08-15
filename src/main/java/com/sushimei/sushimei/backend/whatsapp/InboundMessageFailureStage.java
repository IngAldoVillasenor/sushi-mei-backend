package com.sushimei.sushimei.backend.whatsapp;

public enum InboundMessageFailureStage {
    RECORD_INBOUND,
    HANDLE_MESSAGE,
    SEND_RESPONSE,
    MARK_COMPLETED
}
