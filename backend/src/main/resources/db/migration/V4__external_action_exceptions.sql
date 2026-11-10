-- V4: orphan-receipt exceptions recorded by admin recovery when the attested amount differs (section 4.4).

create table external_action_exception (
    id                uuid primary key,
    quote_id          uuid           not null references order_quote (id),
    cashier_id        uuid           not null references staff_user (id),
    idempotency_key   uuid,
    attested_amount   numeric(12, 2) not null,
    snapshot_total    numeric(12, 2) not null,
    payment_method    text           not null check (payment_method in ('TRAINING', 'CASH', 'EXTERNAL_CARD', 'EXTERNAL_QR')),
    state             text           not null check (state in ('PENDING', 'RESOLVED_OUTSIDE')),
    resolved_order_id uuid references orders (id),
    reason            text           not null check (length(trim(reason)) between 1 and 500),
    created_by        uuid           not null references staff_user (id),
    created_at        timestamptz    not null,
    resolved_by       uuid references staff_user (id),
    resolved_at       timestamptz,
    check (attested_amount <> snapshot_total),
    check ((state = 'PENDING' and resolved_by is null) or (state = 'RESOLVED_OUTSIDE' and resolved_by is not null
        and resolved_at is not null and resolved_order_id is null))
);
create index external_action_exception_quote_idx on external_action_exception (quote_id);
