CREATE TABLE payments (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- One payment per order: a redelivered PaymentRequested cannot open a second one.
  order_id       uuid NOT NULL UNIQUE,
  store_id       uuid NOT NULL,
  amount_cents   bigint NOT NULL CHECK (amount_cents > 0),
  method         text NOT NULL CHECK (method IN ('CARD', 'SPEI')),
  status         text NOT NULL CHECK (status IN ('REQUIRES_PAYMENT', 'SUCCEEDED', 'CANCELLED', 'REFUNDED')),
  -- SPEI: the CLABE and reference the store transfers to.
  clabe          text,
  reference      text UNIQUE,
  card_brand     text,
  card_last4     text,
  failed_attempts int NOT NULL DEFAULT 0,
  expires_at     timestamptz NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX payments_pending_spei_idx ON payments (created_at) WHERE method = 'SPEI' AND status = 'REQUIRES_PAYMENT';
