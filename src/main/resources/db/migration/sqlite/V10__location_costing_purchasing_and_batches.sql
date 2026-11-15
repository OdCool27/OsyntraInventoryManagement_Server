ALTER TABLE products ADD COLUMN batch_tracked INTEGER NOT NULL DEFAULT 0 CHECK(batch_tracked IN(0,1));
ALTER TABLE inventory ADD COLUMN stock_value TEXT NOT NULL DEFAULT '0';
UPDATE inventory SET stock_value=CAST(round(quantity_on_hand*(SELECT cost FROM products p WHERE p.id=inventory.product_id),6) AS TEXT);
ALTER TABLE stock_movements ADD COLUMN value_delta TEXT NOT NULL DEFAULT '0';
ALTER TABLE stock_movements ADD COLUMN value_after TEXT NOT NULL DEFAULT '0';
-- Quantities/history remain unchanged. Legacy valuation is explicitly estimated from current product cost.
CREATE TABLE valuation_adjustments (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,location_id INTEGER NOT NULL,product_id TEXT NOT NULL,
 quantity INTEGER NOT NULL,value_delta TEXT NOT NULL,value_after TEXT NOT NULL,source TEXT NOT NULL,reference_id TEXT,created_ms INTEGER NOT NULL,
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
INSERT INTO valuation_adjustments SELECT lower(hex(randomblob(16))),business_id,location_id,product_id,quantity_on_hand,stock_value,stock_value,'MIGRATED_ESTIMATE',NULL,0 FROM inventory;
CREATE TABLE sale_cost_snapshots (
 order_id TEXT NOT NULL,business_id INTEGER NOT NULL,product_id TEXT NOT NULL,quantity INTEGER NOT NULL,value TEXT NOT NULL,
 PRIMARY KEY(order_id,product_id),FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TABLE stock_batches (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,location_id INTEGER NOT NULL,product_id TEXT NOT NULL,
 code TEXT NOT NULL,expires_on TEXT,quantity_on_hand INTEGER NOT NULL DEFAULT 0 CHECK(quantity_on_hand>=0),
 quantity_reserved INTEGER NOT NULL DEFAULT 0 CHECK(quantity_reserved BETWEEN 0 AND quantity_on_hand),
 UNIQUE(business_id,location_id,product_id,code),UNIQUE(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE order_batch_allocations (
 order_id TEXT NOT NULL,business_id INTEGER NOT NULL,product_id TEXT NOT NULL,batch_id TEXT NOT NULL,
 quantity INTEGER NOT NULL,returned_quantity INTEGER NOT NULL DEFAULT 0,
 status TEXT NOT NULL CHECK(status IN('HELD','CONSUMED','RELEASED')),
 PRIMARY KEY(order_id,product_id,batch_id),FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id),
 FOREIGN KEY(batch_id,business_id) REFERENCES stock_batches(id,business_id)
);
CREATE TABLE batch_movements (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,batch_id TEXT NOT NULL,delta_on_hand INTEGER NOT NULL,delta_reserved INTEGER NOT NULL,
 reference_id TEXT,created_ms INTEGER NOT NULL,FOREIGN KEY(batch_id,business_id) REFERENCES stock_batches(id,business_id)
);
CREATE TABLE suppliers (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL REFERENCES businesses(id),name TEXT NOT NULL,email TEXT,active INTEGER NOT NULL DEFAULT 1,
 UNIQUE(id,business_id),UNIQUE(business_id,name)
);
CREATE TABLE purchase_orders (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,supplier_id TEXT NOT NULL,location_id INTEGER NOT NULL,
 currency TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN('OPEN','PARTIAL','RECEIVED','CANCELLED')),
 created_by INTEGER NOT NULL REFERENCES users(id),created_ms INTEGER NOT NULL,
 UNIQUE(id,business_id),FOREIGN KEY(supplier_id,business_id) REFERENCES suppliers(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE purchase_order_lines (
 purchase_order_id TEXT NOT NULL,business_id INTEGER NOT NULL,product_id TEXT NOT NULL,
 unit_code TEXT NOT NULL,unit_quantity TEXT NOT NULL,factor TEXT NOT NULL,unit_cost TEXT NOT NULL,
 ordered_quantity INTEGER NOT NULL CHECK(ordered_quantity>0),received_quantity INTEGER NOT NULL DEFAULT 0 CHECK(received_quantity BETWEEN 0 AND ordered_quantity),
 total_value TEXT NOT NULL,received_value TEXT NOT NULL DEFAULT '0',
 PRIMARY KEY(purchase_order_id,product_id),FOREIGN KEY(purchase_order_id,business_id) REFERENCES purchase_orders(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TABLE goods_receipts (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,purchase_order_id TEXT NOT NULL,created_by INTEGER NOT NULL REFERENCES users(id),created_ms INTEGER NOT NULL,
 UNIQUE(id,business_id),FOREIGN KEY(purchase_order_id,business_id) REFERENCES purchase_orders(id,business_id)
);
CREATE TABLE goods_receipt_lines (
 receipt_id TEXT NOT NULL,business_id INTEGER NOT NULL,product_id TEXT NOT NULL,quantity INTEGER NOT NULL,value TEXT NOT NULL,batch_code TEXT,expires_on TEXT,
 PRIMARY KEY(receipt_id,product_id),FOREIGN KEY(receipt_id,business_id) REFERENCES goods_receipts(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TABLE stock_transfers (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,source_id INTEGER NOT NULL,destination_id INTEGER NOT NULL,
 status TEXT NOT NULL CHECK(status IN('REQUESTED','DISPATCHED','PARTIAL','RECEIVED','CANCELLED')),
 created_by INTEGER NOT NULL REFERENCES users(id),created_ms INTEGER NOT NULL,
 UNIQUE(id,business_id),CHECK(source_id<>destination_id),
 FOREIGN KEY(source_id,business_id) REFERENCES locations(id,business_id),
 FOREIGN KEY(destination_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE stock_transfer_lines (
 transfer_id TEXT NOT NULL,business_id INTEGER NOT NULL,product_id TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),
 batch_code TEXT,expires_on TEXT,received_quantity INTEGER NOT NULL DEFAULT 0 CHECK(received_quantity BETWEEN 0 AND quantity),
 dispatched_value TEXT NOT NULL DEFAULT '0',received_value TEXT NOT NULL DEFAULT '0',
 PRIMARY KEY(transfer_id,product_id),FOREIGN KEY(transfer_id,business_id) REFERENCES stock_transfers(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TRIGGER stock_movement_immutable BEFORE UPDATE ON stock_movements BEGIN SELECT raise(ABORT,'Stock ledger is immutable'); END;
CREATE TRIGGER valuation_immutable BEFORE UPDATE ON valuation_adjustments BEGIN SELECT raise(ABORT,'Valuation ledger is immutable'); END;
CREATE TRIGGER sale_cost_immutable BEFORE UPDATE ON sale_cost_snapshots BEGIN SELECT raise(ABORT,'Sale costs are immutable'); END;
CREATE TRIGGER receipt_line_immutable BEFORE UPDATE ON goods_receipt_lines BEGIN SELECT raise(ABORT,'Receipt acquisition snapshots are immutable'); END;
