# Architecture refactor roadmap

## Current architecture

The application is a Spring Boot monolith that receives WhatsApp webhooks, invokes the `SushiAgent` LangChain4j service, and persists carts and orders with Spring Data JPA. The current agent owns natural-language interaction and initiates the existing cart and checkout tool calls. PostgreSQL with pgvector stores menu embeddings, and Ollama provides both the chat and embedding models.

## Current reliability issues

- Configuration and secrets were previously committed or hardcoded in Java classes.
- Provider dependencies were mixed across incompatible Spring AI and LangChain4j versions.
- The checkout progression is encoded in prompt instructions, so conversation history can be mistaken for business state.
- External services made the context-load test dependent on developer infrastructure.

## Phase 0: foundation

Phase 0 makes configuration environment-backed, keeps PostgreSQL, pgvector, and Ollama active, aligns LangChain4j dependencies, and makes the test suite self-contained. It does not change the `SushiAgent` prompt, tool signatures, cart behavior, webhook flow, or order status flow.

## Future phases

### ConversationSession persistence

Introduce a durable `ConversationSession` keyed by the customer identity. It will persist the active conversation, selected fulfillment method, collected checkout fields, and relevant timestamps independently from prompt history.

### ConversationManager

Introduce a `ConversationManager` that loads, creates, and advances a session for each inbound message. It will coordinate the agent, deterministic services, and persistence boundaries without letting the agent become the source of truth.

### OrderStateMachine

Introduce an `OrderStateMachine` with explicit, validated transitions for cart review, fulfillment selection, address or pickup identity, payment collection, payment validation, kitchen preparation, and completion. This is intentionally not introduced in Phase 0.

### Deterministic checkout

Move checkout field validation, transition rules, order creation, cart closure, and payment gating into deterministic application services. The system will reject invalid transitions regardless of an LLM response.

### Safe catalog and price resolution

Resolve product identifiers, variants, availability, quantities, and prices using controlled catalog data. The LLM may suggest an intent or candidate item, but the server will verify the item and determine the final price.

### Provider abstraction

Add a provider abstraction after the deterministic boundaries are in place. Ollama remains the initial provider; Gemini can be added behind the abstraction without changing checkout logic or leaking provider settings into business workflows.

### Persistent chat memory

Keep chat memory for tone, continuity, and natural response generation, but never treat it as the source of truth for checkout state. Durable session and order data will own all operational facts.

### Test scenarios

Add tests for repeated or out-of-order messages, payment-proof handling, rejected orders, cart reopening and merge behavior, catalog-price verification, duplicated webhook delivery, deterministic state transitions, and provider outage handling.
