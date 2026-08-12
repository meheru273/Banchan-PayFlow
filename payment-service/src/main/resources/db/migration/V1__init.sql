-- PayFlow schema, Tier 1.
-- Written to run on both PostgreSQL (prod) and H2 in PostgreSQL mode (dev),
-- so: no gen_random_uuid() defaults (UUIDs are app-generated), no TEXT/CHAR.

CREATE TABLE wallet (
    id          UUID PRIMARY KEY,
    owner       VARCHAR(120)   NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    balance     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE payment (
    id                  UUID PRIMARY KEY,
    wallet_id           UUID           NOT NULL REFERENCES wallet (id),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    provider_ref        VARCHAR(80),
    card_ref_encrypted  VARCHAR(512),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payment_wallet ON payment (wallet_id);

CREATE TABLE ledger_entry (
    id          UUID PRIMARY KEY,
    payment_id  UUID           NOT NULL REFERENCES payment (id),
    wallet_id   UUID           NOT NULL REFERENCES wallet (id),
    direction   VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_payment ON ledger_entry (payment_id);
CREATE INDEX idx_ledger_wallet ON ledger_entry (wallet_id);

CREATE TABLE idempotency_record (
    idem_key      VARCHAR(100) PRIMARY KEY,
    request_hash  VARCHAR(64)  NOT NULL,
    response_body VARCHAR(4000),
    status_code   INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
