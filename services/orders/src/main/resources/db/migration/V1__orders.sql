-- Corner stores that buy from the distributor.
CREATE TABLE stores (
  id         uuid PRIMARY KEY,
  name       text NOT NULL,
  owner_name text NOT NULL,
  email      text NOT NULL,
  address    text NOT NULL,
  city       text NOT NULL
);

CREATE TABLE orders (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  store_id       uuid NOT NULL REFERENCES stores (id),
  status         text NOT NULL CHECK (status IN (
                   'PENDING_STOCK', 'AWAITING_PAYMENT', 'CONFIRMED', 'PREPARING', 'IN_TRANSIT', 'DELIVERED',
                   'REJECTED', 'EXPIRED', 'CANCELLED')),
  payment_method text NOT NULL CHECK (payment_method IN ('CARD', 'SPEI')),
  -- Known once inventory prices the order; the client never sends prices.
  total_cents    bigint,
  failure_reason text,
  expires_at     timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX orders_store_idx ON orders (store_id, created_at DESC);
CREATE INDEX orders_expiry_idx ON orders (expires_at) WHERE status = 'AWAITING_PAYMENT';

CREATE TABLE order_lines (
  order_id         uuid NOT NULL REFERENCES orders (id),
  product_id       uuid NOT NULL,
  quantity         int NOT NULL CHECK (quantity > 0),
  product_name     text,
  unit_price_cents bigint,
  PRIMARY KEY (order_id, product_id)
);

-- Every step the saga took for an order, for the store's live tracker and for support.
CREATE TABLE order_timeline (
  id          bigserial PRIMARY KEY,
  order_id    uuid NOT NULL REFERENCES orders (id),
  step        text NOT NULL,
  detail      text NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX order_timeline_order_idx ON order_timeline (order_id, id);
