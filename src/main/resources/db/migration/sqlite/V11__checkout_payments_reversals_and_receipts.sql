ALTER TABLE orders ADD COLUMN payment_state TEXT NOT NULL DEFAULT 'NOT_STARTED' CHECK(payment_state IN('NOT_STARTED','PENDING','UNKNOWN','CONFIRMED','DECLINED'));
UPDATE orders SET payment_state=CASE WHEN EXISTS(SELECT 1 FROM simulated_payments p WHERE p.order_id=orders.id AND p.successful=1) THEN 'CONFIRMED' WHEN status='PAYMENT_FAILED' THEN 'DECLINED' ELSE 'NOT_STARTED' END;
ALTER TABLE orders ADD COLUMN discount_cents INTEGER NOT NULL DEFAULT 0 CHECK(discount_cents>=0);
ALTER TABLE order_lines ADD COLUMN list_price_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN discount_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN discount_percent TEXT NOT NULL DEFAULT '0';
ALTER TABLE order_lines ADD COLUMN discount_reason TEXT NOT NULL DEFAULT '';
ALTER TABLE order_lines ADD COLUMN price_version_id TEXT;
ALTER TABLE order_lines ADD COLUMN promotion_id TEXT;
ALTER TABLE order_lines ADD COLUMN unit_code TEXT NOT NULL DEFAULT 'BASE';
ALTER TABLE order_lines ADD COLUMN unit_quantity TEXT NOT NULL DEFAULT '';
ALTER TABLE order_lines ADD COLUMN unit_factor TEXT NOT NULL DEFAULT '1';
-- V8 protects all line updates; only this migration backfills new descriptive fields.
DROP TRIGGER order_line_snapshot_immutable;
UPDATE order_lines SET list_price_cents=price_cents,unit_quantity=CAST(quantity AS TEXT);
CREATE TRIGGER order_line_snapshot_immutable BEFORE UPDATE ON order_lines BEGIN SELECT raise(ABORT,'Order line snapshots are immutable'); END;
CREATE TRIGGER order_discount_immutable BEFORE UPDATE OF discount_cents ON orders BEGIN SELECT raise(ABORT,'Order discounts are immutable'); END;
CREATE TABLE branch_price_versions (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,location_id INTEGER NOT NULL,product_id TEXT NOT NULL,
 price_cents INTEGER NOT NULL CHECK(price_cents>=0),effective_from_ms INTEGER NOT NULL,created_by INTEGER NOT NULL REFERENCES users(id),
 UNIQUE(business_id,location_id,product_id,effective_from_ms),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TABLE checkout_promotions (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,location_id INTEGER,product_id TEXT,
 name TEXT NOT NULL,percent TEXT NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,
 created_by INTEGER NOT NULL REFERENCES users(id),CHECK(end_ms>start_ms),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TABLE payment_attempts (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,order_id TEXT NOT NULL,provider TEXT NOT NULL CHECK(provider IN('CASH','MANUAL_TERMINAL','SIMULATOR')),
 status TEXT NOT NULL CHECK(status IN('PENDING','UNKNOWN','CONFIRMED','DECLINED')),
 amount_cents INTEGER NOT NULL,currency TEXT NOT NULL,external_reference TEXT,
 created_by INTEGER NOT NULL REFERENCES users(id),created_ms INTEGER NOT NULL,resolved_by INTEGER REFERENCES users(id),resolved_ms INTEGER,
 UNIQUE(id,business_id),UNIQUE(business_id,provider,external_reference),
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id)
);
CREATE UNIQUE INDEX order_one_unresolved_payment ON payment_attempts(order_id) WHERE status IN('PENDING','UNKNOWN');
CREATE TABLE payment_events (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,attempt_id TEXT NOT NULL,event_key TEXT NOT NULL,payload_hash TEXT NOT NULL,status TEXT NOT NULL,created_ms INTEGER NOT NULL,
 UNIQUE(business_id,event_key),FOREIGN KEY(attempt_id,business_id) REFERENCES payment_attempts(id,business_id)
);
CREATE TABLE payment_records (
 order_id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,provider TEXT NOT NULL,
 method TEXT NOT NULL,successful INTEGER NOT NULL CHECK(successful IN(0,1)),amount_cents INTEGER NOT NULL,
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id)
);
INSERT INTO payment_records SELECT order_id,business_id,'SIMULATOR',method,successful,amount_cents,created_at FROM simulated_payments;
CREATE TABLE sale_reversals (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,order_id TEXT NOT NULL,type TEXT NOT NULL CHECK(type IN('RETURN','REFUND','CANCEL')),
 status TEXT NOT NULL CHECK(status IN('REQUESTED','APPROVED','COMPLETED','REJECTED')),
 reason TEXT NOT NULL,resellable INTEGER NOT NULL CHECK(resellable IN(0,1)),amount_cents INTEGER NOT NULL,net_cents INTEGER NOT NULL,tax_cents INTEGER NOT NULL,
 created_by INTEGER NOT NULL REFERENCES users(id),approved_by INTEGER REFERENCES users(id),created_ms INTEGER NOT NULL,completed_ms INTEGER,
 refund_method TEXT,external_reference TEXT,UNIQUE(id,business_id),UNIQUE(business_id,external_reference),
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id)
);
CREATE TABLE sale_reversal_lines (
 reversal_id TEXT NOT NULL,business_id INTEGER NOT NULL,product_id TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),
 net_cents INTEGER NOT NULL,tax_cents INTEGER NOT NULL,total_cents INTEGER NOT NULL,cost_value TEXT NOT NULL,
 PRIMARY KEY(reversal_id,product_id),FOREIGN KEY(reversal_id,business_id) REFERENCES sale_reversals(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE VIEW refund_totals AS
 SELECT order_id,business_id,sum(amount_cents) AS amount_cents FROM(
 SELECT order_id,business_id,amount_cents FROM simulated_refunds
 UNION ALL SELECT order_id,business_id,amount_cents FROM sale_reversals WHERE status='COMPLETED'
 ) GROUP BY order_id,business_id;
CREATE TABLE sale_receipts (
 order_id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,body TEXT NOT NULL,sha256 TEXT NOT NULL,created_ms INTEGER NOT NULL,
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id)
);
CREATE TRIGGER receipt_immutable BEFORE UPDATE ON sale_receipts BEGIN SELECT raise(ABORT,'Sale receipts are immutable'); END;
CREATE TRIGGER branch_price_immutable BEFORE UPDATE ON branch_price_versions BEGIN SELECT raise(ABORT,'Branch price versions are immutable'); END;
CREATE TRIGGER promotion_immutable BEFORE UPDATE ON checkout_promotions BEGIN SELECT raise(ABORT,'Promotions are immutable'); END;
CREATE TRIGGER payment_record_immutable BEFORE UPDATE ON payment_records BEGIN SELECT raise(ABORT,'Payment records are immutable'); END;
CREATE TABLE promotion_revocations (
 promotion_id TEXT PRIMARY KEY REFERENCES checkout_promotions(id),business_id INTEGER NOT NULL REFERENCES businesses(id),
 actor_id INTEGER NOT NULL REFERENCES users(id),reason TEXT NOT NULL,created_ms INTEGER NOT NULL
);
