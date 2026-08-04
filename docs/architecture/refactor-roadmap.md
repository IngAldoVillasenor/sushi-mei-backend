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

## Phase 1: persistent conversation sessions (shadow mode)

Phase 1 adds a durable `ConversationSession` keyed by normalized customer phone number. It stores future operational checkout fields and a typed lifecycle state, but the webhook only records inbound activity and successfully downloaded transfer-receipt paths. Session data is written in shadow mode: it does not select a response branch, change the `SushiAgent` prompt or memory, create an order, or transition checkout state. There are no automatic transitions based on message text, captions, images, or LLM output.

Chat memory remains unchanged and continues to support conversational tone and continuity. It is not a checkout source of truth, and neither is the Phase 1 session until deterministic orchestration is introduced.

## Future phases

### ConversationSession persistence

Phase 1 establishes the durable `ConversationSession` keyed by customer identity. Future work will use it to persist and advance deterministic checkout data independently from prompt history.

### ConversationManager (Phase 2)

Phase 2 will introduce a `ConversationManager` that loads, creates, and advances a session for each inbound message. It will coordinate the agent, deterministic services, and persistence boundaries without letting the agent become the source of truth.

### Deterministic state transitions (Phase 3)

Phase 3 will introduce explicit, validated `OrderStateMachine` transitions for cart review, fulfillment selection, address or pickup identity, payment collection, payment validation, kitchen preparation, and completion. This is intentionally not introduced in Phase 1.

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
