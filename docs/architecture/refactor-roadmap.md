# Architecture refactor roadmap

## Current architecture

The application is a Spring Boot monolith that receives WhatsApp webhooks, invokes the `SushiAgent` LangChain4j service, and persists carts and orders with Spring Data JPA. PostgreSQL with pgvector stores menu embeddings, and Ollama provides both active chat and embedding models.

## Current reliability issues

- Checkout progression is still expressed in prompt instructions, so conversational history can be mistaken for operational state.
- The LLM currently coordinates language and legacy checkout behavior, which makes deterministic validation difficult.
- External provider and infrastructure failures must remain isolated from core order and checkout data.

## Phase 0: foundation

Phase 0 makes configuration environment-backed, keeps PostgreSQL, pgvector, and Ollama active, aligns LangChain4j dependencies, and makes the test suite self-contained. It does not change the `SushiAgent` prompt, tool signatures, cart behavior, webhook flow, or order status flow.

## Phase 1: persistent conversation sessions (shadow mode)

Phase 1 adds a durable `ConversationSession` keyed by normalized customer phone number. It stores typed future checkout fields and a lifecycle state, but production handling only records inbound activity and successfully downloaded transfer-receipt paths. These shadow writes do not select a response branch, change the `SushiAgent` prompt or memory, create an order, or transition checkout state.

Chat memory remains useful for conversational tone and continuity. It is not a checkout source of truth, and neither are the Phase 1 shadow fields until explicit commands are routed to deterministic orchestration.

## Phase 2: ConversationManager boundary

Phase 2 introduces `ConversationManager` as the application boundary for inbound WhatsApp messages. The webhook controller continues to own Meta payload parsing, Mexico phone normalization, media download, response delivery, and HTTP acknowledgements. The manager coordinates the existing `SushiAgent`, shadow session writes, and receipt-to-order association without changing checkout progression.

`ConversationSession` remains shadow-only in production: customer text, media content, prompts, and LLM output are not persisted as checkout state. The LLM retains the current language behavior only.

## Phase 3: deterministic transition engine

Phase 3 adds `ConversationStateMachine` and `ConversationTransitionService`. The state machine is a pure deterministic domain component: it has no repository, LLM, HTTP, or transaction dependency. The transition service owns short database transactions, loads existing sessions, obtains one `Clock` instant per command, invokes the state machine, and persists the result.

This strict command layer is intentionally **not invoked by production WhatsApp messages yet**. `ConversationManager`, the webhook controller, `SushiAgent`, carts, order creation, and `OrderRecord` behavior remain unchanged. Shadow receipt recording through `ConversationSessionService.recordTransferReceipt` is still permissive and never advances state; `ConversationTransitionService.provideTransferReceipt` is the separate strict command that requires the transfer-receipt state and validates readiness.

### Transition matrix

| Current state | Command | Next state |
| --- | --- | --- |
| `ORDERING` | request checkout review | `WAITING_CART_CONFIRMATION` |
| `WAITING_CART_CONFIRMATION` | confirm cart | `WAITING_FULFILLMENT_TYPE` |
| `WAITING_CART_CONFIRMATION` | continue ordering | `ORDERING` |
| `WAITING_FULFILLMENT_TYPE` | select delivery | `WAITING_DELIVERY_ADDRESS` |
| `WAITING_FULFILLMENT_TYPE` | select pickup | `WAITING_PICKUP_NAME` |
| `WAITING_DELIVERY_ADDRESS` | provide delivery address | `WAITING_PAYMENT_METHOD` |
| `WAITING_PICKUP_NAME` | provide pickup name | `WAITING_PAYMENT_METHOD` |
| `WAITING_PAYMENT_METHOD` | select cash | `WAITING_CASH_DENOMINATION` |
| `WAITING_PAYMENT_METHOD` | select transfer | `WAITING_TRANSFER_RECEIPT` |
| `WAITING_PAYMENT_METHOD` | select card for pickup only | `READY_TO_CONFIRM` |
| `WAITING_CASH_DENOMINATION` | provide cash denomination | `READY_TO_CONFIRM` |
| `WAITING_TRANSFER_RECEIPT` | provide transfer receipt | `READY_TO_CONFIRM` |
| `READY_TO_CONFIRM` | confirm order | `ORDER_CONFIRMED` |
| Active non-terminal states | cancel checkout | `CANCELLED` |

`ORDER_CONFIRMED` and `CANCELLED` reject ordinary checkout transitions. Cancellation preserves already collected fields for audit; reset is the distinct explicit operation that clears checkout data and returns to `ORDERING`.

### Field invariants

Every command validates its exact source state and all input before mutating the session. Ready-to-confirm requires a valid fulfillment branch and payment branch:

- Delivery requires a non-blank address between 5 and 500 characters.
- Pickup requires a non-blank name between 2 and 120 characters. The existing legacy `OrderTools` combines address and pickup validation at five characters; the deterministic domain uses two because short real pickup names are valid.
- Cash requires a positive `BigDecimal` that fits precision 19 and scale 2.
- Transfer requires a non-blank receipt path up to 1024 characters.
- Card is supported only for pickup with a valid pickup name, matching the existing business behavior; delivery card selection is rejected.

The state machine re-checks these invariants before every transition to `READY_TO_CONFIRM` and before confirmation, so inconsistent persisted sessions cannot be confirmed merely because earlier steps normally supplied the data.

Conversational checkout state and the persisted `OrderRecord` lifecycle are separate domains. The new engine does not create orders, clear carts, select kitchen statuses, or perform deterministic checkout completion yet.

## Phase 4: typed intent routing boundary

Phase 4 introduces `CheckoutIntent` and `CheckoutIntentRouter`. A `CheckoutIntent` is an already typed, trusted Java object; the router maps it to exactly one `ConversationTransitionService` command and returns only safe operational metadata. The router has no transaction, repository, HTTP, WhatsApp, LLM, or response-generation responsibility. `ConversationTransitionService` retains the short transactional boundary, and `ConversationStateMachine` remains the sole authority for state, field, and business-option validation.

Phase 4 does not recognize intents from raw customer text, image captions, JSON payloads, or chat history. It does not accept LLM output as operational truth. The router is intentionally not connected to production WhatsApp traffic.

The current `ORDER_CONFIRMED` value remains conversational session state only. It does not atomically create an `OrderRecord` or clear a cart. Legacy `OrderTools` continues to create actual orders and clear carts. Connecting the router to customer traffic before deterministic checkout completion would risk split-brain state between the session and order domains.

### Distinct future boundaries

1. **Intent recognition** converts untrusted natural language into a candidate typed intent. It is not implemented in Phase 4.
2. **Typed intent routing** maps one trusted `CheckoutIntent` to one transition command. This is Phase 4.
3. **State transition** validates and mutates `ConversationSession` through `ConversationStateMachine` and `ConversationTransitionService`.
4. **Checkout completion** must atomically create an `OrderRecord` and clear the cart. It is not implemented yet.
5. **Language generation** produces human-readable replies and remains on the existing conversational path.
## Future phases

### Deterministic checkout completion (next phase)

Move validated order creation, cart closure, payment gating, and explicit confirmation into deterministic application services. The system will reject invalid transitions regardless of an LLM response.

### Safe catalog and price resolution

Resolve product identifiers, variants, availability, quantities, and prices using controlled catalog data. The LLM may suggest an intent or candidate item, but the server will verify the item and determine the final price.

### Provider abstraction

Add a provider abstraction after deterministic boundaries are in place. Ollama remains the initial provider; Gemini can be added behind the abstraction without changing checkout logic or leaking provider settings into business workflows.

### Persistent chat memory and test scenarios

Keep chat memory for tone, continuity, and natural response generation, but never treat it as the source of truth for checkout state. Future tests will cover duplicate and out-of-order messages, typed intent routing, payment-proof handling, rejected orders, cart reopening, catalog-price verification, optimistic-lock conflicts, and provider outages.
