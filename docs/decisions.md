# Design decisions

What shapes Surtido, what was considered instead, and what each choice costs.

## 1. An orchestrated saga, not a choreography

The orders service drives every order: it places it, waits for inventory,
asks payments for a payment, waits for the money, and follows the delivery.
Other services only answer.

*Considered:* choreography, where each service reacts to the previous one's
event, as the earlier version of this project did (orders published, products
listened, payments published, orders listened). It has fewer moving parts per
service but no single place that knows where an order is, which is exactly
what the store's live tracker and support need. With orchestration the whole
flow lives in one class (`OrderSaga`) and its legal moves in one enum
(`OrderStatus`).

*Cost:* orders knows about every step, so a new step means changing it.

## 2. A status machine guards every transition

`OrderStatus.canMoveTo` lists the legal moves. Every handler locks the order
row (`SELECT … FOR UPDATE`), checks the move, and only then writes. Kafka keeps
the order of messages per key, but replies from different services can still
race, and redeliveries happen: an out-of-order "delivered" before payment, or
a second "paid", simply does nothing. There are tests for both.

## 3. Stock is reserved all or nothing with conditional updates

```sql
UPDATE products SET stock = stock - :quantity
WHERE id = :id AND active AND stock >= :quantity
RETURNING name, price_cents
```

Each line of an order is one of these, inside one transaction. If any line
matches no row, the whole transaction rolls back and the order is rejected
with the product and the quantity left. Lines are applied in product-id order,
so two orders that share products lock them in the same order and cannot
deadlock. Prices are read in the same statement: the client never sends them.

The integration test sends 200 concurrent orders, each for 1 scarce unit (50
in stock) plus 2 plentiful ones, and checks that exactly 50 succeed and that
the 150 rejected orders gave back the plentiful units they had already taken.

*Earlier version:* stock was decremented after the order was saved, by an
event, without a check, so it could go negative; editing an order sent the
decrement again.

## 4. Transactional outbox and idempotent consumers

A service never publishes to Kafka inside a request. It writes the event to an
`outbox` table in the same transaction as the change, and a relay sends it
afterwards (`FOR UPDATE SKIP LOCKED`, so replicas can share the work). A crash
can delay an event but never lose it or send one for a change that rolled back.

Delivery is at least once, so every consumer records the event id in
`processed_messages` inside its own transaction. A redelivered event is a
no-op. Both tables come from the shared `messaging` library and its Flyway
migration, which each service runs alongside its own.

## 5. The payment window closes by itself

Once stock is reserved the store has `PAYMENT_WINDOW` (15 minutes) to pay. A
scheduled job expires overdue orders with `SKIP LOCKED`, which leaves alone an
order that a payment is confirming at that moment. Expiry publishes
`OrderExpired`: inventory puts the units back and payments stops accepting
cards for the order.

A SPEI transfer can still arrive after that, because banks do not ask. The
deposit is recorded, the saga sees an expired order and publishes
`RefundRequested`, and payments marks the money as refunded. That path has an
integration test too.

## 6. A declined card does not fail the order

Card declines are normal. The payment stays open, the saga writes the decline
to the order's timeline, and the store can try another card until the window
closes. Only expiry or cancellation ends an unpaid order.

## 7. Two kinds of retries

*Saga handlers* (inventory, orders, payments, shipping) use a blocking retry
with exponential backoff and then a dead-letter topic. Their messages must be
handled in order per order, so a failing one holds its partition briefly.

*Emails* use Spring Kafka retry topics: a failed send moves to `-retry` topics
and comes back after 2, 4 and 8 seconds without blocking newer emails, then
lands in `-dlt` and is recorded as failed for the warehouse to see. This is
the framework's version of the earlier project's hand-written retry tables and
schedulers. Set `MAIL_FAILURE_RATE=0.5` to watch it work.

## 8. Spring Cloud Gateway and Eureka stay

Docker Compose already gives every container a DNS name, so a registry is not
strictly needed here. They stay because they are what this stack looks like in
the companies that run Spring at scale, and because the gateway resolves
`lb://orders` through the registry, so adding a second orders container
needs no configuration change.

## 9. One Postgres server, one database per service

Each service owns its database and migrates it with Flyway; nothing reads
another service's tables. The earlier version mixed MongoDB and Postgres.
Everything moved to Postgres because the reservation and the outbox row must
commit together, which a single-node MongoDB cannot do without a replica set.

## 10. What is out of scope

Authentication is not implemented: the store picks who it is from a list, and
the warehouse panel is open. The companion project
[Aforo](https://github.com/AngelArteachi/aforo) covers sign-in, signed tokens
and per-user rate limits; here the focus is the order saga.
