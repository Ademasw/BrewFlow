-- V3: kitchen prep status, audit trail, order corrections.

alter table orders
    add column prep_status  text    not null default 'NEW'
        check (prep_status in ('NEW', 'PREPARING', 'READY', 'CLEARED', 'CANCELLED')),
    add column prep_version integer not null default 0 check (prep_version >= 0);

create index orders_open_tickets_idx on orders (placed_at) where prep_status in ('NEW', 'PREPARING', 'READY');

create table audit_event (
    id          uuid primary key,
    actor_id    uuid references staff_user (id),
    event_type  text        not null,
    entity_type text,
    entity_id   uuid,
    request_id  text,
    -- redacted: never a PIN, password, cookie, CSRF token or external payment payload
    metadata    jsonb       not null default '{}'::jsonb,
    created_at  timestamptz not null
);
create index audit_event_created_idx on audit_event (created_at desc, id desc);
create trigger audit_event_append_only
    before update or delete on audit_event
    for each row execute function brewflow_forbid_mutation();

create table order_correction (
    id                     uuid primary key,
    order_id               uuid        not null references orders (id),
    -- pilot corrections are full-order VOID_NOTE only
    type                   text        not null check (type = 'VOID_NOTE'),
    external_action_state  text        not null check (external_action_state in ('PENDING', 'CONFIRMED', 'NOT_APPLICABLE')),
    confirms_correction_id uuid unique,
    reason                 text        not null check (length(trim(reason)) between 1 and 500),
    created_by             uuid        not null references staff_user (id),
    created_at             timestamptz not null,
    unique (id, order_id),
    -- a confirmation references a note of the same order
    foreign key (confirms_correction_id, order_id) references order_correction (id, order_id),
    check (confirms_correction_id is null or external_action_state = 'CONFIRMED')
);
-- one CONFIRMED void per order; one PENDING void per order (a pending note can only be resolved by confirmation)
create unique index order_correction_one_confirmed_idx on order_correction (order_id) where external_action_state = 'CONFIRMED';
create unique index order_correction_one_pending_idx on order_correction (order_id) where external_action_state = 'PENDING';
create trigger order_correction_append_only
    before update or delete on order_correction
    for each row execute function brewflow_forbid_mutation();

-- confirms_correction_id must name a PENDING note
create function brewflow_confirmation_targets_pending() returns trigger
    language plpgsql as
$$
begin
    if new.confirms_correction_id is not null and not exists (
        select 1 from order_correction c
        where c.id = new.confirms_correction_id
          and c.order_id = new.order_id
          and c.external_action_state = 'PENDING') then
        raise exception 'confirms_correction_id must reference a PENDING note of the same order'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger order_correction_confirmation_check
    before insert on order_correction
    for each row execute function brewflow_confirmation_targets_pending();
