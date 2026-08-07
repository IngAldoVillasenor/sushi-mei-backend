# Local AI conversation smoke test

This is a manual, development-only check of the production text conversation path with a local Ollama instance. It must not be used against production traffic and never sends a WhatsApp message.

## Enable the harness

The endpoint is available only when both conditions are true:

- Spring profile `local` is active.
- `DEV_AI_HARNESS_ENABLED=true` is set.

It exposes `POST /internal/dev/ai/chat`, reuses `ConversationManager.handleTextMessage(memoryId, phone, message)`, and returns JSON as `application/json`.

`memoryId` isolates `ChatMemory` only. `phone` identifies the operational cart used by legacy tools. Use a stable memory ID for the turns of one scenario and a unique fake phone number for each separate scenario; changing only `memoryId` does not isolate the cart.

## Windows PowerShell 5.1 UTF-8 client

PowerShell 5.1 can send a string body with the console's legacy encoding. Send UTF-8 bytes explicitly and configure the console for UTF-8 before displaying accented text:

```powershell
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

$payload = @{
    memoryId = 'smoke-ai-friday-001'
    phone = '5214770000001'
    message = '¿Qué venden?'
} | ConvertTo-Json -Compress

$response = Invoke-RestMethod `
    -Method Post `
    -Uri 'http://localhost:8080/internal/dev/ai/chat' `
    -ContentType 'application/json; charset=utf-8' `
    -Body $utf8.GetBytes($payload)

$response | ConvertTo-Json -Depth 3
```

Do not copy local credentials or customer messages into scripts, logs, or tickets.

## Expected behavior matrix

| Scenario | memoryId / phone | Messages in order | Expected operational behavior |
| --- | --- | --- | --- |
| A | `smoke-ai-friday-001` / `5214770000001` | `Hola`; `¿Qué venden?`; `Quiero un California`; `Dame también una Coca`; `¿Qué llevo?`; `Quita la Coca`; `¿Qué llevo?`; `Ya sería todo` | Greeting: no tool and no retrieval. Menu question: tool-free catalog path with menu retrieval; no `checkCart` or cart mutation tool. California and Coca: only the corresponding add operation, with a natural acknowledgement naming the product. Each cart question: exactly one `checkCart`. Removal: only Coca. Finish: no cart/order mutation and no claim that an `OrderRecord` exists or is processing. |
| B | `smoke-ai-friday-002` / `5214770000002` | `Quiero dos órdenes de camarones de 4`; `¿Qué llevo?` | If the menu context identifies one product, add exactly quantity two of that product. Do not invent a product or price. The cart question invokes `checkCart` once. |
| C | `smoke-ai-friday-003` / `5214770000003` | `Ponme un rollo y una bebida` | Ask for clarification. Do not choose a roll or beverage, do not mutate the cart, and do not call `checkCart`. |
| D | `smoke-ai-friday-004` / `5214770000004` | `¿Cuánto cuesta el California?`; `Agrégamelo` | The price uses the tool-free catalog path with menu context and does not call `checkCart` or a cart mutation tool. The pronoun follow-up asks the customer to name the product; it must not add any product. |

Across all scenarios, record only non-sensitive tool/outcome observations. Do not record customer messages, prompts, addresses, payments, receipts, tokens, or credentials.
