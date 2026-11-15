ALTER TABLE products ADD COLUMN product_kind TEXT NOT NULL DEFAULT 'SIMPLE' CHECK(product_kind IN('SIMPLE','PARENT','VARIANT'));
ALTER TABLE products ADD COLUMN parent_id TEXT REFERENCES products(id);
ALTER TABLE products ADD COLUMN variant_key TEXT;
CREATE UNIQUE INDEX product_variant_key ON products(parent_id,variant_key) WHERE parent_id IS NOT NULL;
CREATE TRIGGER product_variant_insert BEFORE INSERT ON products WHEN
 (new.product_kind='VARIANT' AND (new.parent_id IS NULL OR new.variant_key IS NULL OR trim(new.variant_key)='' OR NOT EXISTS(SELECT 1 FROM products p WHERE p.id=new.parent_id AND p.business_id=new.business_id AND p.product_kind='PARENT' AND p.active=1)))
 OR (new.product_kind<>'VARIANT' AND (new.parent_id IS NOT NULL OR new.variant_key IS NOT NULL))
 BEGIN SELECT raise(ABORT,'A variant requires an active parent in its business and a unique variant key'); END;
CREATE TRIGGER product_variant_update BEFORE UPDATE OF product_kind,parent_id,variant_key,business_id ON products WHEN
 new.product_kind<>old.product_kind OR new.parent_id IS NOT old.parent_id OR new.variant_key IS NOT old.variant_key OR new.business_id<>old.business_id
 BEGIN SELECT raise(ABORT,'Product identity/variant relationships are immutable; archive and create a new product'); END;
CREATE TRIGGER parent_stock_guard BEFORE INSERT ON inventory WHEN EXISTS(SELECT 1 FROM products WHERE id=new.product_id AND product_kind='PARENT')
 BEGIN SELECT raise(ABORT,'Variant parents cannot hold stock'); END;
CREATE TRIGGER parent_order_guard BEFORE INSERT ON order_lines WHEN EXISTS(SELECT 1 FROM products WHERE id=new.product_id AND product_kind='PARENT')
 BEGIN SELECT raise(ABORT,'Variant parents cannot be sold'); END;
CREATE TABLE product_units (
 business_id INTEGER NOT NULL,product_id TEXT NOT NULL,code TEXT NOT NULL,
 factor TEXT NOT NULL,purchase_allowed INTEGER NOT NULL CHECK(purchase_allowed IN(0,1)),sale_allowed INTEGER NOT NULL CHECK(sale_allowed IN(0,1)),
 PRIMARY KEY(product_id,code),FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
INSERT INTO product_units SELECT business_id,id,unit_of_measure,'1',1,1 FROM products;
CREATE TRIGGER product_base_unit AFTER INSERT ON products BEGIN
 INSERT INTO product_units VALUES(new.business_id,new.id,new.unit_of_measure,'1',1,1);
END;
CREATE TABLE catalogue_imports (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL REFERENCES businesses(id),status TEXT NOT NULL CHECK(status IN('PREVIEW','COMMITTED')),
 duplicate_policy TEXT NOT NULL CHECK(duplicate_policy IN('REJECT','SKIP','UPDATE')),
 created_by INTEGER NOT NULL REFERENCES users(id),created_ms INTEGER NOT NULL,committed_ms INTEGER,
 UNIQUE(id,business_id)
);
CREATE TABLE catalogue_import_rows (
 import_id TEXT NOT NULL,business_id INTEGER NOT NULL,row_number INTEGER NOT NULL,
 payload TEXT NOT NULL,error TEXT,disposition TEXT NOT NULL,
 PRIMARY KEY(import_id,row_number),FOREIGN KEY(import_id,business_id) REFERENCES catalogue_imports(id,business_id)
);
CREATE TABLE legacy_reorder_levels (
 business_id INTEGER NOT NULL,location_id INTEGER NOT NULL,product_id TEXT NOT NULL,reorder_level INTEGER NOT NULL,
 PRIMARY KEY(location_id,product_id),FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id),
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id)
);
INSERT INTO legacy_reorder_levels SELECT business_id,location_id,product_id,reorder_level FROM inventory;
-- Preserve historical thresholds, then use the highest old branch threshold when no central product value exists.
UPDATE products SET min_stock_threshold=(SELECT max(reorder_level) FROM inventory WHERE product_id=products.id)
 WHERE min_stock_threshold IS NULL;
UPDATE inventory SET reorder_level=coalesce((SELECT min_stock_threshold FROM products WHERE id=inventory.product_id),(SELECT default_threshold FROM businesses WHERE id=inventory.business_id));
CREATE TRIGGER product_threshold_global AFTER UPDATE OF min_stock_threshold ON products BEGIN
 UPDATE inventory SET reorder_level=coalesce(new.min_stock_threshold,(SELECT default_threshold FROM businesses WHERE id=new.business_id)) WHERE product_id=new.id AND business_id=new.business_id;
END;
CREATE TRIGGER inventory_threshold_global AFTER INSERT ON inventory
 WHEN new.reorder_level<>coalesce((SELECT min_stock_threshold FROM products WHERE id=new.product_id),(SELECT default_threshold FROM businesses WHERE id=new.business_id))
 BEGIN
 UPDATE products SET min_stock_threshold=new.reorder_level WHERE id=new.product_id AND business_id=new.business_id;
END;
