-- One row per event that should reach a store's inbox. The unique event id
-- keeps a redelivered event from sending the same email twice.
CREATE TABLE notifications (
  id         bigserial PRIMARY KEY,
  event_id   uuid NOT NULL UNIQUE,
  order_id   uuid NOT NULL,
  recipient  text NOT NULL,
  subject    text NOT NULL,
  status     text NOT NULL CHECK (status IN ('SENT', 'FAILED')),
  attempts   int NOT NULL DEFAULT 1,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notifications_recent_idx ON notifications (created_at DESC);
