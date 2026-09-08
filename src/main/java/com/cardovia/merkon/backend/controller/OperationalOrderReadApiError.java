package com.cardovia.merkon.backend.controller;

/** Stable public error body for the versioned operational order read API. */
public record OperationalOrderReadApiError(String code, String message) {
}
