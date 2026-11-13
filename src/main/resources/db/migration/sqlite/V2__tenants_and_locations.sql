
CREATE TABLE businesses (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 name TEXT NOT NULL CHECK(trim(name) <> ''),
 currency TEXT NOT NULL DEFAULT 'JMD' CHECK(length(currency)=3),
 default_threshold INTEGER NOT NULL DEFAULT 3 CHECK(default_threshold>=0),
 active INTEGER NOT NULL DEFAULT 1 CHECK(active IN(0,1))
);
-- Preserve all baseline data under one explicit business, never distribute it across tenants.
INSERT INTO businesses(id,name) VALUES(1,'Existing business');
CREATE TABLE locations (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 business_id INTEGER NOT NULL REFERENCES businesses(id),
 name TEXT NOT NULL CHECK(trim(name) <> ''),
 active INTEGER NOT NULL DEFAULT 1 CHECK(active IN(0,1)),
 UNIQUE(id,business_id), UNIQUE(business_id,name)
);
INSERT INTO locations(id,business_id,name) VALUES(1,1,'Main');
ALTER TABLE users ADD COLUMN is_operator INTEGER NOT NULL DEFAULT 0 CHECK(is_operator IN(0,1));
-- SQLite cannot add a REFERENCES column with a non-null default to populated tables.
CREATE TABLE employees_new (
 employee_id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL UNIQUE REFERENCES users(id),
 job_title TEXT NOT NULL,
 employee_role TEXT NOT NULL CHECK(employee_role IN('OWNER','MANAGER','CLERK','CASHIER')),
 business_id INTEGER REFERENCES businesses(id)
);
INSERT INTO employees_new SELECT employee_id,user_id,job_title,employee_role,1 FROM employees;
DROP TABLE employees;
ALTER TABLE employees_new RENAME TO employees;
CREATE TRIGGER employee_membership_insert BEFORE INSERT ON employees
 WHEN (new.business_id IS NULL) <> (SELECT is_operator FROM users WHERE id=new.user_id)
 BEGIN SELECT raise(ABORT,'Employee membership must match operator status'); END;
CREATE TRIGGER employee_membership_update BEFORE UPDATE OF business_id,user_id ON employees
 WHEN (new.business_id IS NULL) <> (SELECT is_operator FROM users WHERE id=new.user_id)
 BEGIN SELECT raise(ABORT,'Employee membership must match operator status'); END;

CREATE TABLE categories_new (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 business_id INTEGER NOT NULL REFERENCES businesses(id),
 name TEXT NOT NULL CHECK(trim(name)<>''),
 UNIQUE(id,business_id), UNIQUE(business_id,name)
);
INSERT INTO categories_new(id,business_id,name) SELECT id,1,name FROM categories;
CREATE TABLE products_new (
 id TEXT PRIMARY KEY NOT NULL,
 business_id INTEGER NOT NULL REFERENCES businesses(id),
 sku TEXT NOT NULL CHECK(trim(sku)<>''),
 barcode TEXT,
 item_name TEXT NOT NULL,
 item_description TEXT NOT NULL,
 unit_price NUMERIC NOT NULL CHECK(unit_price>=0),
 category_id INTEGER,
 active INTEGER NOT NULL DEFAULT 1 CHECK(active IN(0,1)),
 added_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 modified_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 UNIQUE(id,business_id), UNIQUE(business_id,sku),
 FOREIGN KEY(category_id,business_id) REFERENCES categories_new(id,business_id)
);
INSERT INTO products_new SELECT id,1,sku,barcode,item_name,item_description,unit_price,category_id,active,added_at,modified_at FROM products;
-- Barcode lookup is exact, trimmed and unique per tenant when present; packaging needs its own SKU.
CREATE UNIQUE INDEX products_barcode_tenant_unique ON products_new(business_id,barcode) WHERE barcode IS NOT NULL;
CREATE TABLE inventory_new (
 product_id TEXT NOT NULL,
 business_id INTEGER NOT NULL,
 location_id INTEGER NOT NULL,
 quantity_on_hand INTEGER NOT NULL DEFAULT 0 CHECK(quantity_on_hand BETWEEN 0 AND 2147483647),
 quantity_reserved INTEGER NOT NULL DEFAULT 0 CHECK(quantity_reserved BETWEEN 0 AND quantity_on_hand),
 reorder_level INTEGER NOT NULL DEFAULT 0 CHECK(reorder_level BETWEEN 0 AND 2147483647),
 updated_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 PRIMARY KEY(product_id,location_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products_new(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
INSERT INTO inventory_new SELECT product_id,1,1,quantity_on_hand,quantity_reserved,reorder_level,updated_at FROM inventory;
DROP TABLE inventory;
DROP TABLE products;
DROP TABLE categories;
ALTER TABLE categories_new RENAME TO categories;
ALTER TABLE products_new RENAME TO products;
ALTER TABLE inventory_new RENAME TO inventory;
CREATE INDEX products_category_idx ON products(category_id);
CREATE INDEX inventory_business_location_idx ON inventory(business_id,location_id);
CREATE TABLE audit_logs_new (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id),
 action TEXT NOT NULL,entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,
 old_value TEXT,new_value TEXT,
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 business_id INTEGER NOT NULL REFERENCES businesses(id)
);
INSERT INTO audit_logs_new SELECT id,user_id,action,entity_type,entity_id,old_value,new_value,created_at,1 FROM audit_logs;
DROP TABLE audit_logs;
ALTER TABLE audit_logs_new RENAME TO audit_logs;
CREATE INDEX audit_logs_entity_idx ON audit_logs(entity_type,entity_id,created_at);
CREATE INDEX audit_business_idx ON audit_logs(business_id,id);

CREATE TABLE platform_audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 actor_id INTEGER NOT NULL REFERENCES users(id),
 business_id INTEGER REFERENCES businesses(id),
 action TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now'))
);
