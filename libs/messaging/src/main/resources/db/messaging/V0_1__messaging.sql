-- Shared by every service that publishes or consumes events.

-- Transactional outbox: written in the same transaction as the state change,
-- relayed to Kafka afterwards.
CREATE TABLE outbox (
  id           uuid PRIMARY KEY,
  topic        text NOT NULL,
  message_key  text NOT NULL,
  event_type   text NOT NULL,
  payload      text NOT NULL,
  -- W3C trace context of the request that caused the event, so the trace
  -- continues through Kafka even though a background job sends it.
  traceparent  text,
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_at timestamptz
);
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;

-- Ids of messages already handled. Kafka delivers at least once; inserting
-- here in the handler's transaction turns a redelivery into a no-op.
CREATE TABLE processed_messages (
  message_id   uuid PRIMARY KEY,
  processed_at timestamptz NOT NULL DEFAULT now()
);
