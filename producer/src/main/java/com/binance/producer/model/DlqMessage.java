package com.binance.producer.model;

public class DlqMessage {
    public String rawPayload;
    public String error;
    public long   ingestionTimeMs;

    public DlqMessage(String rawPayload, String error) {
        this.rawPayload      = rawPayload;
        this.error           = error;
        this.ingestionTimeMs = System.currentTimeMillis();
    }
}
