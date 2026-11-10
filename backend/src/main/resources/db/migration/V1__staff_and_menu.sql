-- V1: staff accounts and menu catalog.
-- No default account is seeded. The first admin is created only by the bootstrap-admin command (section 2.1).

create table staff_user (
    id                     uuid primary key,
    login                  text        not null check (length(trim(login)) between 1 and 64),
    login_normalized       text        not null unique,
    display_name           text        not null check (length(trim(display_name)) between 1 and 120),
    role                   text        not null check (role in ('CASHIER', 'BARISTA', 'ADMIN')),
    credential_hash        text        not null,
    active                 boolean     not null default true,
    must_change_credential boolean     not null default true,
    failed_attempts        integer     not null default 0 check (failed_attempts >= 0),
    locked_until           timestamptz,
    -- incremented whenever existing sessions must die (deactivation, reset, credential change, role change)
    session_epoch          integer     not null default 0,
    revision               integer     not null default 0,
    created_at             timestamptz not null default now(),
    check (login_normalized = lower(trim(login)))
);

-- Single-row catalog revision returned by GET /menu.
create table menu_catalog (
    id       boolean primary key default true check (id),
    revision bigint  not null default 0
);
insert into menu_catalog (id, revision) values (true, 0);

create table category (
    id         uuid primary key,
    title      text    not null check (length(trim(title)) between 1 and 120),
    sort_order integer not null default 0,
    active     boolean not null default true,
    revision   integer not null default 0
);

create table menu_item (
    id             uuid primary key,
    category_id    uuid           not null references category (id),
    title          text           not null check (length(trim(title)) between 1 and 120),
    base_price     numeric(12, 2) not null check (base_price >= 0),
    available      boolean        not null default true,
    low_stock_flag boolean        not null default false,
    -- soft-disable only; menu rows are never deleted
    active         boolean        not null default true,
    revision       integer        not null default 0
);

create table modifier_group (
    id           uuid primary key,
    title        text    not null check (length(trim(title)) between 1 and 120),
    min_selected integer not null,
    max_selected integer not null,
    active       boolean not null default true,
    revision     integer not null default 0,
    check (0 <= min_selected and min_selected <= max_selected)
);

create table modifier_option (
    id          uuid primary key,
    group_id    uuid           not null references modifier_group (id),
    title       text           not null check (length(trim(title)) between 1 and 120),
    -- pilot rule: no negative modifier price
    price_delta numeric(12, 2) not null check (price_delta >= 0),
    active      boolean        not null default true,
    revision    integer        not null default 0
);

create table menu_item_modifier_group (
    item_id    uuid    not null references menu_item (id),
    group_id   uuid    not null references modifier_group (id),
    sort_order integer not null default 0,
    primary key (item_id, group_id)
);

create index menu_item_category_idx on menu_item (category_id);
create index modifier_option_group_idx on modifier_option (group_id);
