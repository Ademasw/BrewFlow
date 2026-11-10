-- V2: quotes, shifts, orders, payments, idempotency.

-- Rows that must never change after insert raise on UPDATE and DELETE.
create function brewflow_forbid_mutation() returns trigger
    language plpgsql as
$$
begin
    raise exception '% is append-only (% rejected)', tg_table_name, tg_op
        using errcode = 'restrict_violation';
end;
$$;

create table shift (
    id            uuid primary key,
    business_date date        not null,
    status        text        not null check (status in ('OPEN', 'CLOSED')),
    opened_by     uuid        not null references staff_user (id),
    opened_at     timestamptz not null,
    closed_by     uuid references staff_user (id),
    closed_at     timestamptz,
    counted_cash  numeric(12, 2),
    check ((status = 'OPEN' and closed_at is null and closed_by is null and counted_cash is null)
        or (status = 'CLOSED' and closed_at is not null and closed_by is not null and counted_cash is not null))
);
-- one OPEN row at a time
create unique index shift_one_open_idx on shift ((true)) where status = 'OPEN';
create index shift_business_date_idx on shift (business_date);

create table order_quote (
    id            uuid primary key,
    cashier_id    uuid           not null references staff_user (id),
    quote_ref     text           not null check (quote_ref ~ '^[0-9A-F]{6}$'),
    snapshot      jsonb          not null,
    total         numeric(12, 2) not null check (total >= 0),
    business_date date           not null,
    created_at    timestamptz    not null,
    expires_at    timestamptz    not null check (expires_at > created_at)
);
create index order_quote_business_date_idx on order_quote (business_date, expires_at);
-- the snapshot is immutable; consumption is recorded by orders.quote_id
create trigger order_quote_immutable
    before update or delete on order_quote
    for each row execute function brewflow_forbid_mutation();

create table human_no_counter (
    business_date date primary key,
    last_no       integer not null check (last_no > 0)
);

create table orders (
    id            uuid primary key,
    -- unique: a quote is consumed by at most one order
    quote_id      uuid           not null unique references order_quote (id),
    cashier_id    uuid           not null references staff_user (id),
    created_by    uuid           not null references staff_user (id),
    shift_id      uuid           not null references shift (id),
    business_date date           not null,
    human_no      integer        not null check (human_no > 0),
    record_status text           not null check (record_status in ('TRAINING', 'RECORDED', 'EXCEPTION')),
    total         numeric(12, 2) not null check (total >= 0),
    placed_at     timestamptz    not null,
    unique (business_date, human_no)
);
create index orders_shift_idx on orders (shift_id);
create index orders_business_date_idx on orders (business_date);

create table order_line (
    id              uuid primary key,
    order_id        uuid           not null references orders (id),
    line_no         integer        not null check (line_no > 0),
    menu_item_id    uuid           not null references menu_item (id),
    item_title      text           not null,
    unit_base_price numeric(12, 2) not null check (unit_base_price >= 0),
    unit_price      numeric(12, 2) not null check (unit_price >= unit_base_price),
    quantity        integer        not null check (quantity > 0),
    line_total      numeric(12, 2) not null check (line_total >= 0),
    unique (order_id, line_no)
);
create trigger order_line_immutable
    before update or delete on order_line
    for each row execute function brewflow_forbid_mutation();

create table order_line_modifier (
    id                 uuid primary key,
    order_line_id      uuid           not null references order_line (id),
    modifier_option_id uuid           not null references modifier_option (id),
    group_title        text           not null,
    option_title       text           not null,
    price_delta        numeric(12, 2) not null check (price_delta >= 0)
);
create index order_line_modifier_line_idx on order_line_modifier (order_line_id);
create trigger order_line_modifier_immutable
    before update or delete on order_line_modifier
    for each row execute function brewflow_forbid_mutation();

create table payment_record (
    id                 uuid primary key,
    -- one row per order
    order_id           uuid           not null unique references orders (id),
    method             text           not null check (method in ('TRAINING', 'CASH', 'EXTERNAL_CARD', 'EXTERNAL_QR')),
    amount             numeric(12, 2) not null check (amount >= 0),
    -- staff-entered reference of the external action; never card data
    external_reference text check (external_reference is null or length(external_reference) <= 64),
    attested_by        uuid           not null references staff_user (id),
    created_at         timestamptz    not null
);
create trigger payment_record_immutable
    before update or delete on payment_record
    for each row execute function brewflow_forbid_mutation();

-- payment amount equals the quote total of its order
create function brewflow_payment_matches_quote() returns trigger
    language plpgsql as
$$
begin
    if not exists (select 1
                   from orders o
                            join order_quote q on q.id = o.quote_id
                   where o.id = new.order_id
                     and q.total = new.amount
                     and o.total = new.amount) then
        raise exception 'payment amount must equal the quote total'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger payment_record_amount_check
    before insert on payment_record
    for each row execute function brewflow_payment_matches_quote();

create table idempotency_record (
    id           uuid primary key,
    actor_id     uuid        not null references staff_user (id),
    key          uuid        not null,
    request_hash text        not null,
    -- SUBMIT: POST /orders; RECOVERY: stored on the original cashier by admin recovery (section 4.4)
    source       text        not null check (source in ('SUBMIT', 'RECOVERY')),
    order_id     uuid        not null references orders (id),
    created_at   timestamptz not null,
    unique (actor_id, key)
);
create index idempotency_record_key_idx on idempotency_record (key);
create trigger idempotency_record_immutable
    before update or delete on idempotency_record
    for each row execute function brewflow_forbid_mutation();
