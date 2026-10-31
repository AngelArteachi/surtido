CREATE TABLE products (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  sku                 text NOT NULL UNIQUE,
  name                text NOT NULL,
  category            text NOT NULL,
  -- What one unit of sale is: a box of 24, a bale of 6, a sack.
  pack                text NOT NULL,
  price_cents         bigint NOT NULL CHECK (price_cents > 0),
  -- The CHECK is the last line of defence; reservations never rely on it.
  stock               int NOT NULL CHECK (stock >= 0),
  low_stock_threshold int NOT NULL DEFAULT 20,
  active              boolean NOT NULL DEFAULT true,
  updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX products_category_idx ON products (category, name);

-- One reservation per order, so a redelivered OrderPlaced cannot reserve twice.
CREATE TABLE reservations (
  order_id   uuid PRIMARY KEY,
  status     text NOT NULL CHECK (status IN ('RESERVED', 'RELEASED', 'COMMITTED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reservation_lines (
  order_id         uuid NOT NULL REFERENCES reservations (order_id),
  product_id       uuid NOT NULL REFERENCES products (id),
  quantity         int NOT NULL CHECK (quantity > 0),
  unit_price_cents bigint NOT NULL,
  PRIMARY KEY (order_id, product_id)
);
