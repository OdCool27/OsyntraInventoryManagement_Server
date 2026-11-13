-- Local MVP baseline. Legacy PostgreSQL migration history lives in its own location.
-- Tenant/location ownership and the commerce engine are subsequent phases.
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
    modified_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE UNIQUE INDEX users_email_case_unique ON users(lower(email));
CREATE TABLE employees (
    employee_id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL UNIQUE REFERENCES users(id),
    job_title TEXT NOT NULL,
    employee_role TEXT NOT NULL CHECK (employee_role IN ('OWNER', 'MANAGER', 'CLERK', 'CASHIER'))
);
CREATE TABLE categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE CHECK (trim(name) <> '')
);
CREATE TABLE products (
    id TEXT PRIMARY KEY NOT NULL,
    sku TEXT NOT NULL UNIQUE,
    barcode TEXT UNIQUE,
    item_name TEXT NOT NULL,
    item_description TEXT NOT NULL,
    unit_price NUMERIC NOT NULL CHECK (unit_price >= 0),
    category_id INTEGER REFERENCES categories(id),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    added_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
    modified_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX products_category_idx ON products(category_id);
CREATE TABLE inventory (
    product_id TEXT PRIMARY KEY NOT NULL REFERENCES products(id),
    quantity_on_hand INTEGER NOT NULL DEFAULT 0 CHECK (quantity_on_hand BETWEEN 0 AND 2147483647),
    quantity_reserved INTEGER NOT NULL DEFAULT 0 CHECK (quantity_reserved BETWEEN 0 AND 2147483647),
    reorder_level INTEGER NOT NULL DEFAULT 0 CHECK (reorder_level BETWEEN 0 AND 2147483647),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now')),
    CHECK (quantity_reserved <= quantity_on_hand)
);
CREATE TABLE audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    action TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f', 'now'))
);
CREATE INDEX audit_logs_entity_idx ON audit_logs(entity_type, entity_id, created_at);
