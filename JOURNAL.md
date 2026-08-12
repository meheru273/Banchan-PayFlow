# Build Journal

A running log of what was built, in what order, and — more importantly — *why*.
Newest entries at the bottom of each tier.

---

## Tier 1 — deployable base (12 Aug 2026) ✅ live

**Goal:** the smallest thing that is honestly deployable: a Spring Boot payment API with real
persistence, deployed publicly, before any messaging/security complexity is added.

### Maven multi-module skeleton (`common` + `payment-service`)
Spring Boot 3.5.3 on Java 21, Maven. `common` starts nearly empty (just the
`PaymentCompletedEvent` record) but exists from day one so the Tier 2 `ledger-worker` has a
place to share code without services depending on each other.

### One Flyway migration, four tables
`wallet`, `payment`, `ledger_entry`, `idempotency_record` in `V1__init.sql`. Written to run on
both PostgreSQL (prod) and H2 in PostgreSQL mode (dev): UUIDs are app-generated (no
`gen_random_uuid()`), `VARCHAR` instead of `TEXT`/`CHAR` so Hibernate's `validate` mode agrees
with the schema on both engines. Wallets carry a `balance` column guarded by optimistic locking
(`@Version`); the invariant is that balance always equals the signed sum of the wallet's ledger
entries.

### The dev profile exists because this machine has no Docker
Local runs use in-memory H2 in PostgreSQL compatibility mode (`dev` profile) — same Flyway
scripts, same JPA mappings. Deployment doesn't need local Docker either: Render builds the
multi-stage Dockerfile in the cloud. This kept "no Docker Desktop" from blocking anything.

### Claim-based idempotency
`POST /payments` requires an `Idempotency-Key`. The service *inserts a claim row first* and
lets the primary key arbitrate races — a concurrent duplicate cannot execute twice. Same key +
same body (SHA-256 of canonical JSON) replays the stored response; same key + different body →
409; failure releases the claim so retries work.

**Bug caught by the integration test before first deploy:** Spring Data's `save()` on an
entity with a client-assigned id calls `merge()`, which silently *updated* the existing claim
row instead of raising the duplicate-key violation — the double-charge the design exists to
prevent. Fix: implement `Persistable` so inserts always use `persist()`. This is the
merge-vs-persist pitfall, and the reason the double-entry/idempotency tests exist.

### Synchronous ledger posting (a deliberate Tier 1 shortcut)
Payments complete in-process: one transaction posts the treasury DEBIT + customer CREDIT and
asserts the signed sum is exactly zero. The journal notes this explicitly because Tier 2's
whole point is to move this behind the provider webhook and RabbitMQ.

### RFC 7807 everywhere, Swagger UI, demo seeding
`@RestControllerAdvice` extending `ResponseEntityExceptionHandler`; validation errors carry a
per-field `errors` map; no stack traces to clients. Demo data seeds *through the real payment
path* so seeded rows obey the same invariants. `POST /api/v1/demo/reset` reseeds.

### Deployment (Render + Neon)
`render.yaml` blueprint, Docker runtime, Frankfurt, `JAVA_OPTS=-XX:MaxRAMPercentage=75.0` for
the 512 MB free instance, plain `/health` endpoint (no actuator — dependency minimalism).
**Neon lesson:** the connection string Neon hands out is the *pooled* endpoint; Flyway's
session-level advisory locks don't survive PgBouncer transaction pooling, so the app uses the
direct endpoint (host without `-pooler`). Live at https://payflow-api-zkxz.onrender.com.

### Post-deploy: quieting the replay path
Routine replays hit the claim insert and logged scary `duplicate key` ERRORs (harmless but
noisy — they were the mechanism working). Now the service checks for the stored record before
attempting the insert; the collision path only fires in true sub-millisecond races. Root URL
`/` now 302s to Swagger UI so the bare portfolio link lands somewhere useful.

---

## Tier 2 — messaging + security (started 13 Aug 2026) 🚧

**Goal:** make the payment flow asynchronous and production-shaped: a simulated provider
confirms payments via an HMAC-signed webhook, completion publishes `payment.completed` to
RabbitMQ, and a separate `ledger-worker` posts the ledger entries — plus JWT auth and AES-GCM
encryption at rest.

### Shared domain moved into `common`
`ledger-worker` writes the same tables as the API, so entities, repositories and the ledger
posting rules moved from `payment-service` to `common` — one source of truth both services
compile against, instead of two mappings that could drift. This is what `common` was reserved
for in Tier 1. `LedgerPostingService` carries the double-entry invariant and is deliberately
idempotent (existing entries short-circuit) because RabbitMQ is at-least-once: a redelivered
event must never double-book a wallet.

### The flow went asynchronous
`POST /payments` now creates a `PENDING` payment and returns. A `ProviderSimulator` stands in
for a real provider, but honestly: after a short delay it calls back **over real HTTP** with a
signed webhook to `/api/v1/webhooks/provider`. That handler verifies and publishes
`payment.completed`; the worker posts the ledger and flips the payment to `COMPLETED`. The
idempotency snapshot stores the `PENDING` response — replays return exactly what the original
call returned, which is how Stripe behaves too.

### Webhook security decisions
Signature = HMAC-SHA256 over `timestamp + "." + body`, compared with `MessageDigest.isEqual`
(constant-time — a plain `equals` leaks how many bytes matched through timing). The timestamp
is checked against a ±5 min window *before* any crypto and is part of the signed content, so a
captured webhook can't be replayed later or re-dated. Rejections are 401 problem details.

### Messaging topology
Durable topic exchange `payflow.events` → queue `ledger.payment.completed` (with
`x-dead-letter-exchange`) → DLQ. Retries: 5 attempts, exponential backoff 1s→10s, then reject
without requeue so a poison message lands in the DLQ instead of spinning forever. Declarations
live once in `common` and are declared lazily by whichever service connects first — which is
also why payment-service still boots with no broker configured.

### Dev mode without a broker (and without Docker)
`payflow.messaging.enabled=false` (the dev profile) makes the webhook handler post the ledger
in-process through the same `LedgerPostingService` the worker uses. Same code path, same
invariant, no RabbitMQ — the full create→webhook→ledger flow runs and is integration-tested on
a machine with nothing installed but a JDK.

### JWT auth, scoped deliberately small
HS256 access + refresh tokens (refresh is single-purpose via a `token_use` claim). Reads and
payment creation stay public — a portfolio demo that demands login is a demo nobody clicks —
but destructive admin ops (`/demo/reset`) need the ADMIN role. No user table: the data model
doesn't have one, so the single admin identity comes from env config, checked in constant time.

### AES-GCM at rest via AttributeConverter
The card reference column is encrypted transparently: AES-256-GCM, fresh random 12-byte IV per
value (IV reuse breaks GCM), 128-bit tag, stored as base64(IV‖ciphertext). The key is env-only
and may be raw base64 or any string (SHA-256-derived), so Render's generated secrets work.
Reads that fail authentication (tampering, wrong key, or Tier 1's legacy plaintext rows)
surface `null` rather than a 500 — a deliberate availability-over-strictness call, noted here
honestly.
