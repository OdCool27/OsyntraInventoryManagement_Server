
CREATE TABLE orders (
 id TEXT PRIMARY KEY NOT NULL, business_id INTEGER NOT NULL, location_id INTEGER NOT NULL,
 channel TEXT NOT NULL CHECK(channel IN('POS','ONLINE')),
 status TEXT NOT NULL CHECK(status IN('RESERVED','COMPLETED','PAYMENT_FAILED','CANCELLED','EXPIRED','FULFILLED','RETURNED')),
 created_by INTEGER REFERENCES users(id), access_hash TEXT,
 expires_at_ms INTEGER NOT NULL, total_cents INTEGER NOT NULL CHECK(total_cents>=0),
 currency TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 UNIQUE(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE order_lines (
 order_id TEXT NOT NULL, business_id INTEGER NOT NULL, product_id TEXT NOT NULL,
 sku TEXT NOT NULL,item_name TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),
 price_cents INTEGER NOT NULL CHECK(price_cents>=0),cost_cents INTEGER NOT NULL CHECK(cost_cents>=0),
 PRIMARY KEY(order_id,product_id),
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
CREATE TABLE simulated_payments (
 order_id TEXT PRIMARY KEY NOT NULL,business_id INTEGER NOT NULL,
 method TEXT NOT NULL CHECK(method IN('CASH','CARD')), successful INTEGER NOT NULL CHECK(successful IN(0,1)),
 amount_cents INTEGER NOT NULL CHECK(amount_cents>=0),
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id)
);
CREATE TABLE simulated_refunds (
 order_id TEXT PRIMARY KEY NOT NULL,business_id INTEGER NOT NULL,
 amount_cents INTEGER NOT NULL CHECK(amount_cents>=0),reason TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id)
);
CREATE TABLE order_events (
 id TEXT PRIMARY KEY NOT NULL,business_id INTEGER NOT NULL,order_id TEXT NOT NULL,
 action TEXT NOT NULL,actor_id INTEGER REFERENCES users(id),actor_kind TEXT NOT NULL,reason TEXT,
 created_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 FOREIGN KEY(order_id,business_id) REFERENCES orders(id,business_id)
);
CREATE INDEX orders_history_idx ON orders(business_id,created_at);
CREATE INDEX orders_expiry_idx ON orders(status,expires_at_ms);
