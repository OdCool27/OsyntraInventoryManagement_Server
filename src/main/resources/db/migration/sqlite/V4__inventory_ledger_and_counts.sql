
CREATE TABLE movement_reasons (
 business_id INTEGER NOT NULL REFERENCES businesses(id), code TEXT NOT NULL,
 label TEXT NOT NULL, PRIMARY KEY(business_id,code)
);
INSERT INTO movement_reasons
 SELECT id,'OPENING_COUNT','Opening count' FROM businesses UNION ALL
 SELECT id,'RECEIPT','Stock receipt' FROM businesses UNION ALL
 SELECT id,'DAMAGE','Damaged stock' FROM businesses UNION ALL
 SELECT id,'CORRECTION','Stock correction' FROM businesses UNION ALL
 SELECT id,'COUNT_APPROVAL','Approved physical count' FROM businesses;
CREATE TABLE command_receipts (
 business_id INTEGER NOT NULL REFERENCES businesses(id),
 request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, entity_id TEXT NOT NULL,
 PRIMARY KEY(business_id,request_id)
);
CREATE TABLE stock_movements (
 id TEXT PRIMARY KEY NOT NULL,
 business_id INTEGER NOT NULL, location_id INTEGER NOT NULL, product_id TEXT NOT NULL,
 movement_type TEXT NOT NULL, reason TEXT NOT NULL, notes TEXT,
 delta_on_hand INTEGER NOT NULL, delta_reserved INTEGER NOT NULL,
 on_hand_after INTEGER NOT NULL CHECK(on_hand_after BETWEEN 0 AND 2147483647),
 reserved_after INTEGER NOT NULL CHECK(reserved_after BETWEEN 0 AND on_hand_after),
 actor_id INTEGER REFERENCES users(id), actor_kind TEXT NOT NULL,
 reference_id TEXT,
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE INDEX movement_history_idx ON stock_movements(business_id,location_id,product_id,created_at);
INSERT INTO stock_movements(id,business_id,location_id,product_id,movement_type,reason,delta_on_hand,delta_reserved,on_hand_after,reserved_after,actor_kind)
 SELECT lower(hex(randomblob(16))),business_id,location_id,product_id,'OPENING','MIGRATED_BASELINE',quantity_on_hand,quantity_reserved,quantity_on_hand,quantity_reserved,'MIGRATION' FROM inventory;
CREATE TABLE stock_counts (
 id TEXT PRIMARY KEY NOT NULL, business_id INTEGER NOT NULL, location_id INTEGER NOT NULL,
 status TEXT NOT NULL CHECK(status IN('DRAFT','SUBMITTED','APPROVED')),
 created_by INTEGER NOT NULL REFERENCES users(id), approved_by INTEGER REFERENCES users(id),
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 UNIQUE(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE stock_count_lines (
 count_id TEXT NOT NULL, business_id INTEGER NOT NULL, product_id TEXT NOT NULL,
 expected_quantity INTEGER NOT NULL, counted_quantity INTEGER NOT NULL CHECK(counted_quantity BETWEEN 0 AND 2147483647),
 PRIMARY KEY(count_id,product_id),
 FOREIGN KEY(count_id,business_id) REFERENCES stock_counts(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TABLE opening_imports (
 id TEXT PRIMARY KEY NOT NULL, business_id INTEGER NOT NULL, location_id INTEGER NOT NULL,
 created_by INTEGER NOT NULL REFERENCES users(id), status TEXT NOT NULL CHECK(status IN('PREVIEW','COMMITTED')),
 UNIQUE(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE opening_import_lines (
 import_id TEXT NOT NULL, business_id INTEGER NOT NULL, row_number INTEGER NOT NULL,
 sku TEXT NOT NULL, quantity INTEGER, reorder_level INTEGER, error TEXT,
 PRIMARY KEY(import_id,row_number),
 FOREIGN KEY(import_id,business_id) REFERENCES opening_imports(id,business_id)
);
