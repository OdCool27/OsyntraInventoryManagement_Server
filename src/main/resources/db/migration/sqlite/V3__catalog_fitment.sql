
ALTER TABLE products ADD COLUMN cost NUMERIC NOT NULL DEFAULT 0 CHECK(cost>=0);
ALTER TABLE products ADD COLUMN unit_of_measure TEXT NOT NULL DEFAULT 'EACH' CHECK(trim(unit_of_measure)<>'');
ALTER TABLE products ADD COLUMN min_stock_threshold INTEGER CHECK(min_stock_threshold>=0);
CREATE TABLE vehicle_fitments (
 id TEXT PRIMARY KEY NOT NULL,
 business_id INTEGER NOT NULL REFERENCES businesses(id),
 make TEXT NOT NULL CHECK(trim(make)<>''),
 model TEXT NOT NULL CHECK(trim(model)<>''),
 year_from INTEGER NOT NULL CHECK(year_from BETWEEN 1886 AND 2200),
 year_to INTEGER NOT NULL CHECK(year_to BETWEEN year_from AND 2200),
 engine TEXT, trim TEXT,
 source TEXT NOT NULL CHECK(trim(source)<>''),
 verified_by INTEGER NOT NULL REFERENCES users(id),
 verified_at TEXT NOT NULL DEFAULT(strftime('%Y-%m-%d %H:%M:%f','now')),
 UNIQUE(id,business_id)
);
CREATE TABLE product_fitments (
 product_id TEXT NOT NULL, fitment_id TEXT NOT NULL, business_id INTEGER NOT NULL,
 PRIMARY KEY(product_id,fitment_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id),
 FOREIGN KEY(fitment_id,business_id) REFERENCES vehicle_fitments(id,business_id)
);
CREATE INDEX vehicle_search_idx ON vehicle_fitments(business_id,make,model,year_from,year_to);
