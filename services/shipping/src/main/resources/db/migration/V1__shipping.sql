CREATE TABLE shipments (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id     uuid NOT NULL UNIQUE,
  store_id     uuid NOT NULL,
  store_name   text NOT NULL,
  store_email  text NOT NULL,
  address      text NOT NULL,
  city         text NOT NULL,
  packs        int NOT NULL,
  total_cents  bigint NOT NULL,
  status       text NOT NULL CHECK (status IN ('PENDING', 'PREPARING', 'IN_TRANSIT', 'DELIVERED')),
  driver       text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  delivered_at timestamptz
);

CREATE INDEX shipments_status_idx ON shipments (status, created_at);
