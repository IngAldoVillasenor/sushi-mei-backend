package com.sushimei.sushimei.backend.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
public class AiConversationService {

    private static final Logger log = LoggerFactory.getLogger(AiConversationService.class);
    private static final String FINISH_ORDER_RESPONSE = "Perfecto, tomo nota de que por ahora no deseas agregar m\u00e1s. "
            + "Si necesitas cambiar algo, dime el producto que deseas agregar o quitar.";
    private static final String GREETING_RESPONSE = "\u00a1Hola! Soy el asistente de Sushi Mei. \u00bfQu\u00e9 te gustar\u00eda pedir hoy?";
    private static final String ADD_CLARIFICATION_RESPONSE = "Claro. \u00bfQu\u00e9 producto deseas agregar?";
    private static final String REMOVE_CLARIFICATION_RESPONSE = "Claro. \u00bfQu\u00e9 producto deseas quitar?";
    private static final String AMBIGUOUS_REFERENCE_RESPONSE = "Claro. \u00bfQu\u00e9 producto deseas agregar o quitar?";
    private static final String MUTATION_FAILURE_RESPONSE =
            "No se pudo modificar el carrito en este momento. Intenta nuevamente o solicita ayuda del restaurante.";
    private static final String CONFIRMATION_BLOCKED_RESPONSE =
            "No puedo confirmar una orden todav\u00eda. La finalizaci\u00f3n se procesa por un flujo separado.";
    private static final String CATALOG_OPERATION_CLAIM_RESPONSE =
            "Puedo ayudarte con informaci\u00f3n del men\u00fa. \u00bfQu\u00e9 producto deseas consultar?";
    private static final Set<String> OPERATION_SUCCESS_TOKENS = Set.of(
            "agregue", "agrego", "agregado", "anadi", "anadio", "anadido",
            "quite", "quito", "quitado", "elimine", "elimino", "eliminado");
    private static final Set<String> OPERATION_TARGET_TOKENS = Set.of("carrito", "pedido", "orden");
    private static final Set<String> ORDER_COMPLETION_TOKENS = Set.of(
            "creado", "creada", "procesado", "procesada", "confirmado", "confirmada", "listo", "lista");

    private final SushiAgent sushiAgent;
    private final CatalogAgent catalogAgent;
    private final AiToolSafetyGuard toolSafetyGuard;
    private final ConversationRetrievalPolicy retrievalPolicy;

    public AiConversationService(SushiAgent sushiAgent,
                                 CatalogAgent catalogAgent,
                                 AiToolSafetyGuard toolSafetyGuard,
                                 ConversationRetrievalPolicy retrievalPolicy) {
        this.sushiAgent = sushiAgent;
        this.catalogAgent = catalogAgent;
        this.toolSafetyGuard = toolSafetyGuard;
        this.retrievalPolicy = retrievalPolicy;
    }

    public String chat(String memoryId, String phoneNumber, String message) {
        if (AiToolSafetyGuard.isSimpleGreeting(message)) {
            log.info("AI conversation outcome=SAFE_GREETING");
            return GREETING_RESPONSE;
        }
        if (AiToolSafetyGuard.isAmbiguousAddPronounRequest(message)) {
            log.info("AI conversation outcome=SAFE_ADD_CLARIFICATION");
            return ADD_CLARIFICATION_RESPONSE;
        }
        if (AiToolSafetyGuard.isAmbiguousRemovePronounRequest(message)) {
            log.info("AI conversation outcome=SAFE_REMOVE_CLARIFICATION");
            return REMOVE_CLARIFICATION_RESPONSE;
        }
        if (AiToolSafetyGuard.isStandaloneAmbiguousReference(message)) {
            log.info("AI conversation outcome=SAFE_AMBIGUOUS_REFERENCE_CLARIFICATION");
            return AMBIGUOUS_REFERENCE_RESPONSE;
        }
        if (AiToolSafetyGuard.isFinishOrderIntent(message)) {
            log.info("AI conversation outcome=SAFE_FINISH_ACKNOWLEDGEMENT");
            return FINISH_ORDER_RESPONSE;
        }
        if (retrievalPolicy.isReadOnlyCatalogTurn(message)) {
            String catalogResponse = catalogAgent.chat(message);
            if (containsOperationalClaim(catalogResponse)) {
                log.warn("AI conversation outcome=CATALOG_RESPONSE_BLOCKED reason=OPERATIONAL_CLAIM");
                return CATALOG_OPERATION_CLAIM_RESPONSE;
            }
            log.info("AI conversation outcome=CATALOG_AGENT_RESPONSE");
            return catalogResponse;
        }

        AiToolTurnResult<String> result = toolSafetyGuard.executeTextTurn(message,
                () -> invokeAgent(memoryId, phoneNumber, message));
        return safeResponseFor(result.mutationOutcome()).orElseGet(() -> authoritativeResponseFor(result).orElseGet(() -> {
            log.info("AI conversation outcome=MODEL_RESPONSE");
            return result.value();
        }));
    }

    private Optional<String> authoritativeResponseFor(AiToolTurnResult<String> result) {
        if (!result.mutationOutcome().isSuccessfulCartOperation() || result.authoritativeToolResponse() == null) {
            return Optional.empty();
        }
        log.info("AI conversation outcome=AUTHORITATIVE_TOOL_RESPONSE toolOutcome={}", result.mutationOutcome());
        return Optional.of(result.authoritativeToolResponse());
    }
    private boolean containsOperationalClaim(String response) {
        Set<String> responseTokens = AiToolSafetyGuard.tokens(response);
        boolean namesOperationalTarget = responseTokens.stream().anyMatch(OPERATION_TARGET_TOKENS::contains);
        return namesOperationalTarget && (responseTokens.stream().anyMatch(OPERATION_SUCCESS_TOKENS::contains)
                || responseTokens.stream().anyMatch(ORDER_COMPLETION_TOKENS::contains));
    }

    private String invokeAgent(String memoryId, String phoneNumber, String message) {
        try {
            return sushiAgent.chat(memoryId, phoneNumber, message);
        } catch (RuntimeException exception) {
            log.warn("AI conversation outcome=MODEL_FAILURE reason={}", exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private Optional<String> safeResponseFor(AiMutationTurnOutcome mutationOutcome) {
        return switch (mutationOutcome) {
            case NONE, ADD_SUCCEEDED, REMOVE_SUCCEEDED, CART_QUERY_SUCCEEDED -> Optional.empty();
            case ADD_BLOCKED -> safeToolResponse(mutationOutcome, ADD_CLARIFICATION_RESPONSE);
            case REMOVE_BLOCKED -> safeToolResponse(mutationOutcome, REMOVE_CLARIFICATION_RESPONSE);
            case ADD_FAILED, REMOVE_FAILED -> safeToolResponse(mutationOutcome, MUTATION_FAILURE_RESPONSE);
            case CONFIRMATION_BLOCKED -> safeToolResponse(mutationOutcome, CONFIRMATION_BLOCKED_RESPONSE);
        };
    }

    private Optional<String> safeToolResponse(AiMutationTurnOutcome outcome, String response) {
        log.info("AI conversation outcome=SAFE_TOOL_RESPONSE reason={}", outcome);
        return Optional.of(response);
    }
}
