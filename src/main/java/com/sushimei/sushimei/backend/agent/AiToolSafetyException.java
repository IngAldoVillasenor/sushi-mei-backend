package com.sushimei.sushimei.backend.agent;

public class AiToolSafetyException extends RuntimeException {

    private final AiToolSafetyReason reason;

    public AiToolSafetyException(AiToolSafetyReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public AiToolSafetyReason getReason() {
        return reason;
    }
}
