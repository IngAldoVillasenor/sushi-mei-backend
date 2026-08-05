package com.sushimei.sushimei.backend.service;

/** Non-sensitive reason why a closed cart cannot safely be reopened. */
public enum CartReopenFailureReason {
    UNIT_PRICE_MISMATCH
}
