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

## Phase 5A2c: structured order foundations

Phase 5A2c adds `V5__add_structured_order_foundations.sql` for PostgreSQL and H2. It introduces immutable `order_lines` snapshots with exact `NUMERIC(19,2)` unit and line totals, source cart-item provenance, line ordering, and database checks for positive values and exact line-total arithmetic. Lines do not reference `cart_items`: an order must retain historical evidence after a cart changes, reopens, or is otherwise managed by legacy behavior.

`orders.source_cart_id` is nullable for historical rows and unique when present. It is deliberately not a cart foreign key; it is a provenance and idempotency boundary for future deterministic completion without coupling an immutable order to legacy cart lifecycle operations. Order-specific nullable metadata now includes `OrderSource` (`WHATSAPP_AI`, `ANDROID_MANUAL`, `COUNTER`), `OrderFulfillmentType`, `OrderPaymentMethod`, pickup name, and exact cash denomination. These types remain independent from conversation-domain enums. The legacy `orderDetails`, legacy delivery/payment fields, legacy `totalAmount`, and exact `totalAmountAmount` remain compatible and no production flow writes the new fields yet.

## Phase 5B1: atomic deterministic checkout core

Phase 5B1 implements the internal `OrderService` completion core. One short database transaction locks the exact `OPEN` source cart by ID, takes its validated immutable `CartSnapshot`, loads the exact `READY_TO_CONFIRM` `ConversationSession`, writes the structured `OrderRecord` and ordered lines, closes that same cart, and confirms that session through `ConversationStateMachine`. The service uses one UTC `Clock` instant for the persisted order timestamp and session transition. It performs no LLM, WhatsApp, HTTP, filesystem, or other external work inside its transaction.

`orders.source_cart_id` is the idempotency boundary. A compatible retry for the same normalized phone number and `OrderSource` returns `ALREADY_COMPLETED` without re-closing a cart or advancing a session; a cross-customer or structurally incompatible retry fails without returning another customer's order. `PESSIMISTIC_WRITE` locking of the exact cart row, followed by an idempotency recheck, serializes normal concurrent attempts, while the database unique constraint remains the final safeguard. If snapshot validation, order persistence, cart closure, or session invariants fail, the whole transaction rolls back, leaving no order, order lines, cart close, or confirmation.

Production routing remains deliberately disconnected: no WhatsApp, AI, `OrderTools`, REST controller, or typed-intent path invokes `OrderService` yet. The legacy `CartService.reopenCart` may reuse a closed cart identity, but a source cart is single-use once it creates an order because `orders.source_cart_id` is unique. Rejection/revision traffic therefore must not use deterministic completion until a future flow clones or creates a new cart identity. Before production routing, the application still needs a trusted command adapter, deterministic rejection/revision handling, response generation around completion outcomes, and production concurrency/operational validation.
## Phase 5B2: cart mutation serialization boundary

Phase 5B2 makes legacy `CartService.addItem`, `removeItem`, and `clearCart` acquire `PESSIMISTIC_WRITE` on an already-existing `OPEN` cart before inspecting or mutating it. This uses the same physical cart-row locking protocol as `OrderService`, so a checkout and a cart mutation serialize once both identify the same persisted cart. If checkout closes the cart first, the mutation lookup finds no `OPEN` row and follows the existing legacy new-cart behavior instead of appending to the closed source cart.

No row lock exists for an absent cart. Concurrent first-`OPEN`-cart creation for one phone remains deferred, and `CartSnapshotService` retains its multiple-active-cart fail-safe. A later production-hardening phase should add a database-enforced one-open-cart-per-customer invariant if multi-channel traffic requires it. Legacy reopen/rejection still has its separate source-cart-identity limitation, and production checkout routing remains disconnected.

## Phase 6A: operational catalog API

Phase 6A introduces menu_items as the operational source of truth for future deterministic ordering and Android ERP/POS integrations. It stores exact positive BigDecimal prices at NUMERIC(19,2), uses active for catalog membership and available for temporary sellability, supports soft deletion (active=false, available=false), and protects ERP updates with optimistic versioning. The DTO-only REST API under /api/v1/menu/items never exposes its JPA entity.

menu_embeddings remains a LangChain4j-owned AI retrieval artifact. It is not written, read, or synchronized by this catalog API, so AI menu answers do not automatically reflect catalog CRUD changes. A later explicit projection/synchronization design is required before AI can rely on operational catalog changes.

No manual-order endpoint or production checkout routing is added here. order_lines.source_cart_item_id remains NOT NULL because existing deterministic lines originate from persisted cart items; Android manual orders must not fabricate a cart or conversation session to satisfy it. Phase 6B must evolve line provenance for trusted cart-less manual orders and resolve final prices server-side from menu_items.

Write endpoints are not safe for unrestricted public internet exposure until an ERP/POS authentication and authorization boundary is implemented. Native Android does not require new browser CORS configuration.
## Phase 6A2: configurable catalog and deterministic quotes

Phase 6A2 makes menu configuration operational catalog data. A menu item has a standalone-orderable flag, zero or more data-managed tags, and zero or more active selection groups. Tags and groups are not restaurant-product Java enums: promotions, trays, boxes, roll classes, modifiers, quantities, and eligibility remain database configuration.

Selection rules target either one item or one tag and use the stable technical algorithms INCLUDED, PRICE_DIFFERENCE, FULL_ITEM_PRICE, or FIXED_SURCHARGE. The backend selects the uniquely highest-priority matching active rule; an unmatched or tied rule is rejected deterministically. All prices and adjustments use exact BigDecimal NUMERIC(19,2) arithmetic. The quote engine validates cardinality, duplicate policy, availability, nested configuration, cycles, and depth before returning an immutable resolved quote. A quote is computed from current catalog state only; it is not a reservation or a price guarantee, and future order creation must re-resolve it transactionally.

The operational configuration endpoint returns resolved selectable options and price adjustments, while the separate definition endpoint exposes raw tags, groups, rules, and algorithms only to ERP configuration editors. Android and future WhatsApp adapters must reuse this resolver rather than duplicate catalog pricing. menu_embeddings remains AI-only and is not synchronized by this phase.

Ingredients, special instructions, and external WhatsApp catalog references remain deferred. No manual order is created: Phase 6B must first evolve the NOT NULL order_lines.source_cart_item_id provenance for trusted cart-less orders, then resolve catalog prices and save historical configuration snapshots. Delivery-fee automation is also deferred. Management write APIs still require authentication and authorization before any unrestricted public exposure.
## Phase 6A3: temporal promotion engine

Phase 6A3 adds a data-managed temporal promotion aggregate. Promotions may contain one or more catalog item or active catalog tag targets, and apply only to root order-entry lines, and use a stable technical benefit algorithm: `FIXED_UNIT_PRICE` or `BUY_X_GET_Y_SAME_ITEM`. The business date is derived once per quote from the injected `Clock` in `America/Mexico_City`; validity dates and ISO weekdays determine applicability. Active matching promotions never stack: the uniquely highest priority wins, while a highest-priority tie is a deterministic configuration conflict.

The quote endpoint derives all promotion effects itself. A fixed-unit-price promotion changes only the root base charge, leaving configuration adjustments charged. A buy-X-get-Y-same-item promotion generates separately configurable reward units of the same root menu item with a charged base of zero; it does not flatten rewards into paid quantity or represent them primarily as a discount. Reward requests carry only a source-line correlation key, ordinal, and nested configuration; never a customer-supplied price, item, or promotion identity. Nested selections inside configurable products are not independently promotion eligible.

Quotes are current-catalog calculations, not reservations. They remain isolated from orders, carts, WhatsApp, AI/RAG, and checkout completion. Ingredients, external catalog references, manual order creation, historical configuration snapshots, delivery pricing, and authentication/authorization for management writes remain deferred.
## Future phases

### Trusted manual-order completion and production routing

Phase 5B1 already provides the internal deterministic checkout core. Future work must add trusted adapters, cart-less manual-order provenance, server-side catalog price resolution, production routing, rejection/revision handling, and response generation without making an LLM operational truth.

### Deterministic catalog integration

Phase 6A establishes the operational catalog. Future order services must resolve product identity, variants, availability, quantities, and final prices from that controlled data. The LLM may suggest an intent or candidate item, but the server will verify the item and determine the final price.

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
## Phase 6S1 - Backend Identity and Security

Phase 6S1 establishes the backend authentication boundary for trusted ERP/POS users. Flyway V9 adds application users, persistent device sessions, rotated opaque refresh-token history, and security audit events. The API uses four explicit roles (OWNER, MANAGER, CASHIER, and KITCHEN), RS256 `at+jwt` access tokens with a 15-minute lifetime, and opaque refresh tokens with a non-extendable 15-day absolute session lifetime. Refresh-token replay revokes the session immediately; every bearer request also validates the authoritative database session and current user state, so logout, session revocation, role changes, and user deactivation take effect immediately.

Only `GET` and `POST /api/whatsapp/webhook`, plus login and refresh, remain anonymous. Catalog, promotions, orders, uploads, and local internal development endpoints require authentication; internal development HTTP access is OWNER-only. Bootstrap of the first OWNER is opt-in through external environment configuration and has no default credentials. Passwords are BCrypt-protected with policy validation and a local denylist; no plaintext password, JWT, or refresh token is stored or audited.

Android Phase 6S2 (secure token storage and single-flight refresh), deployment proxy/Tailscale/ngrok work, and Phase 6B cart-less manual order creation remain deferred. Security-management write APIs still require deployment authentication/authorization review before unrestricted public exposure.

## Phase 6B - server-authoritative POS orders

Phase 6B adds the authenticated `POST /api/v1/orders` boundary for OWNER, MANAGER, and CASHIER users. Android supplies only a UUID request correlation value, fulfillment/payment details, and catalog configuration requests. It cannot submit prices, totals, promotion choices, reward identities, or cart/conversation identifiers. The service re-quotes the current operational catalog and temporal promotions in one short repeatable-read database transaction, then writes an `ANDROID_MANUAL` order with exact `BigDecimal` totals.

V10 preserves historical orders while adding a unique client request identifier, creator-user provenance, and a normalized request fingerprint. Compatible retries return the existing order; cross-user or changed-input reuse is rejected. Manual lines have no fabricated `source_cart_item_id`. They retain immutable source menu identity, name, catalog base, charged base, configuration adjustment, final unit/line values, promotion evidence, and explicit paid versus promotion-reward kind. Recursive resolved configuration selections are stored in immutable parent-linked snapshots, so catalog or promotion edits do not rewrite historical POS evidence.

Buy-X-get-Y rewards are persisted as separate backend-derived `PROMOTION_REWARD` lines. Their charged base is zero, but independently selected configuration adjustments remain charged; zero final reward lines are valid while paid lines remain strictly positive. Cart checkout, WhatsApp, AI, and conversational flows remain disconnected from this service. The legacy rejection endpoint explicitly refuses `ANDROID_MANUAL` orders before its cart-reopen and WhatsApp behavior.

This is not a quote reservation. The server re-resolves current catalog and promotion state during creation. Remaining work includes Android Phase 6S2 integration, trusted payment/lifecycle processing, manual-order editing/cancellation design, order snapshot display DTO refinement, and a dedicated POS/kitchen lifecycle that replaces the guarded legacy rejection workflow.

## Phase 6C - server-authoritative order lifecycle

Phase 6C centralizes the currently supported operational order transitions in `OrderLifecycleService`. The persisted `orders.status` column remains legacy-compatible text, while the lifecycle boundary uses a typed vocabulary and rejects absent or unknown historical statuses safely. `PENDING_VALIDATION -> PENDING` is allowed only for `TRANSFER` payment validation; `PENDING -> PREPARING`; and `PREPARING -> COMPLETED`. Repeated or skipped-state commands are conflicts rather than idempotent successes. `CANCELLED_CLARIFICATION` and `COMPLETED` orders cannot return to active preparation.

Each lifecycle mutation locks the exact order row with `PESSIMISTIC_WRITE`, rechecks the persisted state while locked, and commits only short database work. This serializes competing commands against the same source state without holding a transaction across AI, WhatsApp, cart, or filesystem work. `GET /api/orders/active` retains its endpoint and oldest-first behavior but now returns a dedicated operational DTO instead of the `OrderRecord` entity.

The legacy rejection endpoint retains its WhatsApp/cart-reopen orchestration only for compatible legacy orders. Its database cancellation transition is isolated before external work, so external delivery is not atomic with local persistence. `ANDROID_MANUAL` orders remain explicitly blocked from that legacy path; deterministic POS rejection/revision remains deferred, as do delivery tracking, notifications/outbox, printing, and broader lifecycle design.

## Phase 6D - server-authoritative operational order read model

Phase 6D adds the versioned read-only operational API at `GET /api/v1/orders/active` and `GET /api/v1/orders/{id}`. It reads persisted order, line, configuration-selection, and promotion-snapshot evidence only; it never re-quotes current catalog data or promotions. Recursive configuration is exposed as flat immutable selections linked by their persisted parent snapshot IDs, and promotion rewards remain explicit `PROMOTION_REWARD` lines linked to their paid source.

Historical orders remain readable when newer metadata or structured lines are absent. Their nullable values remain null, legacy order-details text remains available, and the total uses the exact numeric representation when present with the established parallel-money compatibility fallback. The existing `POST /api/v1/orders` contract, Phase 6C lifecycle commands, and legacy `/api/orders/active` endpoint remain unchanged. A future Android Kitchen release can migrate to this versioned DTO API; pagination, search/filtering, order editing, POS rejection/revision, delivery tracking, and event delivery remain deferred.
