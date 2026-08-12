# PayFlow — Build Brief

> **Setup:** copy this file into the new project root and rename it `CLAUDE.md`.
> Claude Code loads it automatically as project instructions.

---

## What we are building

**PayFlow** is a payment + double-entry wallet service. A client creates a payment, the service
verifies a signed webhook from a simulated payment provider, publishes an event to RabbitMQ, and a
separate worker consumes it and posts balanced ledger entries.

This is a **portfolio project**. It must end up with a public, clickable demo and a README a
recruiter can skim in 60 seconds. Correctness and clean structure matter more than feature count.

## Stack (do not substitute)

| Layer | Choice |
|---|---|
| Language / framework | **Java 21**, **Spring Boot 3.x**, Maven multi-module |
| Database | **PostgreSQL** + Spring Data JPA + **Flyway** migrations |
| Messaging | **RabbitMQ** via Spring AMQP |
| API docs | **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui`) |
| Testing | JUnit 5, Mockito, **Testcontainers**, **REST Assured**, JaCoCo |
| Frontend | **Next.js** (App Router) + TypeScript + Tailwind |
| CI | GitHub Actions |

## Deployment targets (all free tiers, no credit card required)

- **Vercel** — Next.js dashboard only. Vercel has **no Java runtime**; never try to deploy Spring there.
- **Render** — two web services (Docker): `payment-service` and `ledger-worker`.
  ⚠️ Render's free tier does **not** offer Background Workers, so `ledger-worker` must be a *web
  service* exposing a trivial `GET /health`. Its real job is the `@RabbitListener`.
- **Neon** — PostgreSQL.
- **CloudAMQP** — RabbitMQ ("Little Lemur" free plan).

Render free instances sleep after ~15 min idle and take ~30–50 s to wake. Add a wake-up notice to
the dashboard UI and README so a slow first load doesn't read as broken.

**Optional upgrade — Google Cloud Run** (only if a card that works for GCP billing is available):
scale-to-zero like Render but with ~2–5 s cold starts instead of 30–50 s, Docker-native, and free at
portfolio traffic levels. It also creates genuine GCP experience, which is currently a gap. Use it
only as a swap for Render — everything else in this brief stays the same.

## Repo layout

```
payflow/
├── common/                 # shared DTOs, events, exceptions
├── payment-service/        # REST API, webhooks, publishes events
├── ledger-worker/          # consumes events, posts ledger entries, /health
├── web/                    # Next.js dashboard (deploys to Vercel)
├── docker-compose.yml      # local Postgres + RabbitMQ
└── README.md
```

## Data model

- `wallet` — id, owner, currency, `@Version` (optimistic locking)
- `payment` — id, wallet_id, amount, currency, status (`PENDING|COMPLETED|FAILED`), provider_ref,
  `card_ref_encrypted`, created_at
- `ledger_entry` — id, payment_id, wallet_id, direction (`DEBIT|CREDIT`), amount, created_at
- `idempotency_record` — key, request_hash, response_body, status_code, created_at

**Double-entry invariant:** for any payment, the sum of its ledger entries is exactly zero.
Enforce it in code and assert it in a test.

## API surface

```
POST /api/v1/payments              # requires Idempotency-Key header
GET  /api/v1/payments/{id}
GET  /api/v1/wallets/{id}
GET  /api/v1/wallets/{id}/transactions
POST /api/v1/webhooks/provider     # HMAC-SHA256 signed
POST /api/v1/demo/reset            # reseed demo data
```

---

## Build order

Each tier must be **deployed and working** before starting the next. Do not begin a tier until the
previous one is green in CI and live on Render/Vercel.

### Tier 1 — API + persistence
- Maven multi-module skeleton, `payment-service` runs locally via docker-compose
- Flyway migrations for all four tables
- Payment + wallet endpoints, Bean Validation on request bodies
- `@ControllerAdvice` returning Spring Boot 3 `ProblemDetail` (RFC 7807) — no stack traces to clients
- **Idempotency:** `Idempotency-Key` header. Same key + same request body → return the stored
  response, do not re-execute. Same key + *different* body → `409 Conflict`.
- Swagger UI reachable at `/swagger-ui.html`
- Dockerfile; deployed to Render against Neon

**Done when:** a recruiter can open the public Swagger UI and create a payment.

### Tier 2 — messaging + security
- Spring Security + JWT (access + refresh), `@PreAuthorize` on admin routes
- `payment-service` publishes `payment.completed` to a topic exchange
- `ledger-worker` consumes it and writes the balanced DEBIT/CREDIT pair
- **Dead-letter queue** + retry with exponential backoff; a poison message must land in the DLQ,
  not spin forever
- **Webhook HMAC-SHA256 verification** — compare with a **constant-time** comparison
  (`MessageDigest.isEqual`), reject on mismatch, reject stale timestamps
- **AES-GCM encryption at rest** for `card_ref_encrypted` via a JPA `AttributeConverter`.
  Key comes from an env var, never from source.

**Done when:** creating a payment produces ledger rows through the queue, and a tampered webhook
signature is rejected.

### Tier 3 — tests + dashboard
- Unit tests (JUnit 5 + Mockito) for the ledger and idempotency logic
- **Testcontainers** integration tests spinning up real Postgres + RabbitMQ
- **REST Assured** API tests covering the happy path, the idempotent replay, and the bad signature
- JaCoCo report; coverage badge in README
- GitHub Actions: build → test → coverage → build Docker image
- **Next.js dashboard on Vercel:** wallet balance, transaction table, "make a payment" form, and a
  live event log so the queue is visible. Configure CORS on the API for the Vercel origin.
- Seed demo data on boot + a working "Reset demo" button

**Done when:** the Vercel link shows a working payment flow end to end with no setup by the viewer.

### Tier 4 — optional (only for Oracle-focused applications)
Add an Oracle XE Spring profile and move ledger posting into a PL/SQL stored procedure.

---

## Constraints

- **No Kafka.** RabbitMQ only. If Kafka is ever added it must be real, not a rename.
- **No Kubernetes.** `docker-compose.yml` is for local development and is not orchestration.
- **No secrets in source.** DB URL, JWT secret, webhook secret and AES key are env vars.
  Commit a `.env.example` with placeholder values.
- **No real payment provider.** Simulate the provider; never handle real card numbers.
- Keep dependencies minimal — no framework that isn't in the table above.

## Environment variables

```
DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD
RABBITMQ_URL
JWT_SECRET, JWT_ACCESS_TTL, JWT_REFRESH_TTL
WEBHOOK_HMAC_SECRET
AES_ENCRYPTION_KEY          # base64, 256-bit
CORS_ALLOWED_ORIGINS
NEXT_PUBLIC_API_BASE_URL    # web/ only
```

## README requirements

Must include, in this order: one-line description · architecture diagram (Mermaid is fine) · live
demo link + Swagger link + the free-tier wake-up notice · local setup in under five commands ·
how idempotency, the double-entry invariant, HMAC verification and AES-GCM at rest each work ·
test + coverage instructions.
