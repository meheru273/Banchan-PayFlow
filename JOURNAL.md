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
encryption at rest. Entries below are appended as each step lands.
