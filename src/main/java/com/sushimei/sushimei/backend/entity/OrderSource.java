package com.sushimei.sushimei.backend.entity;

/**
 * Provenance of a structured order. These values are independent from
 * conversation-domain state and remain nullable for historical orders.
 */
public enum OrderSource {
    WHATSAPP_AI,
    ANDROID_MANUAL,
    COUNTER,
    VENDIS_IMPORT
}
