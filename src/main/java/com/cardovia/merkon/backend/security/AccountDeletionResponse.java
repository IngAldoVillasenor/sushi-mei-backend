package com.cardovia.merkon.backend.security;
record AccountDeletionResponse(String message) { static AccountDeletionResponse accepted() { return new AccountDeletionResponse("Solicitud de eliminación aceptada."); } }
