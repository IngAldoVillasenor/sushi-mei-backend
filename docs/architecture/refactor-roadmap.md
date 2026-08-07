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
## WhatsApp Reliability 1: inbound webhook idempotency

Flyway V4 adds `whatsapp_inbound_messages`, keyed by Meta `messages[].id`. The webhook claims that ID in a short database transaction before any shadow activity write, media download, agent invocation, cart/order mutation, or WhatsApp response. A duplicate claim returns `EVENT_RECEIVED` without repeating any operational work. Newly claimed events move from `PROCESSING` to `COMPLETED` only after the response sender reports success; failures are recorded as `FAILED` in a separate short transaction and retain the existing controlled acknowledgement.

Automatic replay of `FAILED` events is deliberately deferred. The external WhatsApp send and local completion update cannot be one atomic transaction: a send that succeeds immediately before local completion recording fails remains an acknowledged-delivery uncertainty that needs a later retry/outbox design.

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

## Phase 5A1: deterministic cart snapshot foundation

Phase 5A1 introduces deterministic money validation and an immutable checkout-cart snapshot boundary while preserving the current persisted schema and legacy production flow. `CartItem.unitPrice` remains a legacy `Double` during this interim phase. The snapshot adapter first verifies that the value is finite, converts it once with `BigDecimal.valueOf`, and performs every later calculation with `BigDecimal` only.

Checkout money is positive, exact, and normalized to precision 19 and scale 2. Values requiring rounding, non-finite values, zero or negative prices, invalid quantities, and total overflows are rejected before they can be used by deterministic checkout. No customer-facing wording is produced by this boundary.

`CartSnapshotService` reads exactly one existing `OPEN` cart in a short read-only transaction. It never creates, merges, changes, or closes carts, and it does not read formatted cart text. It returns immutable `CartSnapshot` and `CartLineSnapshot` records ordered by persisted cart-item ID. Missing, empty, or multiply-active carts are explicit errors rather than opportunities to select or create a cart arbitrarily.

Phase 5A1 does not create an order, close a cart, confirm a conversation session, route trusted intents into production traffic, or change the legacy `OrderTools` checkout behavior. Persisted `Double` money remains technical debt until Phase 5A2.

## Phase 5A2a: Flyway baseline ownership

Phase 5A2a adopts Flyway 12.4.0, managed by Spring Boot 4.1, for the current application schema. `B1__current_application_schema.sql` is a clean-database baseline at version 1 for PostgreSQL and H2. It creates `cart`, `cart_items`, `orders`, `conversation_sessions`, and the intentionally preserved unmapped legacy `carts` table without inserting production data.

Existing `sushidb` deployments are not migrated by B1. An authorized operator must first verify the reviewed schema fingerprint, backup, aggregate profile, absence of `flyway_schema_history`, and preservation of `public.carts`, then run an explicit Flyway baseline at version 1. `baselineOnMigrate` remains false so a non-empty unexpected database fails instead of being silently accepted. Hibernate now validates the schema; it no longer creates or updates it.

Flyway owns the five application and legacy transactional tables. Infrastructure owns the PostgreSQL `vector` extension. LangChain4j continues to own `menu_embeddings`; B1 neither creates nor changes that table. Clean PostgreSQL environments therefore require infrastructure to provision the extension before RAG startup. The H2 test profile excludes RAG, so it does not require either externally managed object.

Phase 5A2a changes no monetary column, order model, cart behavior, production routing, or deterministic completion. Future versioned migrations begin at `V2` or higher.

## Phase 5A2b1: parallel monetary columns and compatibility

Phase 5A2b1 requires the verified Flyway version-1 baseline history row before `V2` may run. `V2__add_parallel_numeric_money_columns.sql` adds nullable, default-free `NUMERIC(19,2)` columns beside—not instead of—the legacy floating-point values: `cart_items.unit_price_amount` and `orders.total_amount_amount`. It neither backfills historical data nor adds constraints, indexes, triggers, or generated values.

New cart-item and order-total writes prepare one exact `ParallelMoney` pair: the numeric value is validated at precision 19 and scale 2 with `RoundingMode.UNNECESSARY`, and the legacy `Double` is accepted only when a `BigDecimal.valueOf` round trip reproduces that exact value. Reads prefer numeric values, fall back to valid legacy values, and reject absent, invalid, or disagreeing representations explicitly. The new nullable entity fields remain hidden from legacy JSON responses.

Cart reopening clones both validated representations. The legacy order flow still creates an order and then closes its cart non-atomically; that behavior is intentionally unchanged until Phase 5B. This release adds no historical backfill, order lines, idempotency key, typed order metadata, production typed-intent routing, or deterministic order completion. Persisted `Double` money remains technical debt.

## Phase 5A2b2: exact historical backfill and convergence

Read-only production profiling verified all historical monetary values before this migration: 16 `cart_items` values and 3 `orders` totals were finite, positive, exact at scale 2, and compatible with the parallel `NUMERIC(19,2)` representation. `V3__backfill_and_constrain_numeric_money.sql` first revalidates that compatibility, backfills only exact legacy values, verifies convergence, and then makes `unit_price_amount` and `total_amount_amount` `NOT NULL` and positive. Named constraints also require exact agreement whenever a legacy floating-point value remains present; numeric-only rows remain supported for the later cutover.

The legacy floating-point columns and legacy reads remain temporarily for compatibility until a separately verified cutover. PostgreSQL V3 owns `public.checkout_money_java_double_to_numeric(double precision)`: it fixes `extra_float_digits` to shortest-precise output and `search_path` to `pg_catalog`, so its schema-qualified use in the agreement checks is independent of caller-session configuration. The helper remains only until a later migration first removes the agreement checks and legacy floating-point columns after verified cutover. Phase 5A2b2 introduces neither structured order lines nor source-cart idempotency nor atomic checkout behavior.

## Phase 5A2c: structured orders and idempotency

Phase 5A2c will add structured order lines, nullable historical source-cart identity, a unique idempotency constraint for non-null source carts, and order-specific fulfillment and payment metadata. Legacy `orderDetails` remains compatible during that transition.

## Phase 5B: atomic deterministic checkout completion

Phase 5B will atomically use the validated cart snapshot to create the real order and its lines, close the source cart, and confirm the conversation session. It must enforce source-cart idempotency and leave no split-brain state between conversation confirmation and `OrderRecord` creation.

## Future phases

### Deterministic checkout completion (next phase)

Move validated order creation, cart closure, payment gating, and explicit confirmation into deterministic application services. The system will reject invalid transitions regardless of an LLM response.

### Safe catalog and price resolution

Resolve product identifiers, variants, availability, quantities, and prices using controlled catalog data. The LLM may suggest an intent or candidate item, but the server will verify the item and determine the final price.

### Provider abstraction

Add a provider abstraction after deterministic boundaries are in place. Ollama remains the initial provider; Gemini can be added behind the abstraction without changing checkout logic or leaking provider settings into business workflows.

### Persistent chat memory and test scenarios

Keep chat memory for tone, continuity, and natural response generation, but never treat it as the source of truth for checkout state. Future tests will cover duplicate and out-of-order messages, typed intent routing, payment-proof handling, rejected orders, cart reopening, catalog-price verification, optimistic-lock conflicts, and provider outages.
## AI conversation stabilization

The conversational layer now uses a concise `SushiAgent` prompt focused on Sushi Mei's warm Spanish tone, menu grounding, exact phone-number tool safety, explicit add/remove requests, current-cart queries, multi-item requests, and natural confirmations. It removes the former long checkout script, mandatory upsell and closing rules, and instructions to treat chat history as checkout state.

`ChatMemory` remains useful only for conversational continuity. It is not a source of truth for fulfillment, payment, receipt, confirmation, or any `OrderRecord` lifecycle data. When a customer says they have finished ordering, the text path returns a fixed safe acknowledgement. It does not invoke the legacy confirmation tool or claim that a real order was created. Deterministic order completion remains deferred to the future `OrderService` work.

For manual local Ollama checks, the disabled-by-default `/internal/dev/ai/chat` harness requires both the `local` profile and `DEV_AI_HARNESS_ENABLED=true`. It calls the same `ConversationManager` text path as inbound messages, without WhatsApp send behavior. See [the local AI smoke-test matrix](ai-conversation-smoke-test.md).

The menu `ContentRetriever` was previously auto-wired into every `SushiAgent` turn. A narrow retrieval policy now skips greetings, explicit cart queries, named removals, finish intent, generic category requests, and ambiguous pronouns; menu and identifiable product requests still retrieve. The existing `max-results: 15` and `min-score: 0.0` remain unchanged because no retrieval-score evidence supports choosing a new threshold. Retrieval logs expose only execution, result count, and top score.
Read-only menu, product, ingredient, availability, and price turns now use a separate programmatically constructed `CatalogAgent`. It receives the same Ollama chat model and selective `ContentRetriever`, but the builder is never given `OrderTools` or any tool provider; therefore its requests contain no tool specifications and it cannot mutate a cart. A narrow response check also replaces an affirmative cart/order-success claim with a safe informational fallback. Explicit add/remove requests and current-cart questions remain on the guarded `SushiAgent` path. `CatalogAgent` is deliberately stateless for now: sharing the legacy per-agent `ChatMemory` cache across two AI-service proxies would not provide one safely shared history. This affects conversational continuity for catalog-only answers only; it does not create operational state in memory.

A per-turn AI tool guard also blocks cart mutations unless the current message explicitly requests the same named product, allows only one cart query for a current-cart question, and blocks conversational use of the legacy confirmation tool. The active turn records blocked and failed cart mutations; when either occurs, `AiConversationService` discards the LLM final wording and returns a deterministic safe response so a tool failure cannot become a false customer-facing success claim. Simple greetings and ambiguous add/remove pronouns are also answered before model invocation. This remains a narrow safety boundary: it does not model checkout state or replace the future deterministic `OrderService`.
