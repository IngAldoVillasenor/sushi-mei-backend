package com.sushimei.sushimei.backend.agent;

/**
 * Captures the model return value and any authoritative response produced by a cart tool during one AI turn.
 */
public record AiToolTurnResult<T>(T value,
                                  AiMutationTurnOutcome mutationOutcome,
                                  String authoritativeToolResponse,
                                  int successfulAddCount) {
}
