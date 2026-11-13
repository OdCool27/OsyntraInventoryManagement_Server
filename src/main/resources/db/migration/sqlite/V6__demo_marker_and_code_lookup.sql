
CREATE TABLE demo_seeds (
 seed_key TEXT PRIMARY KEY NOT NULL,
 business_id INTEGER NOT NULL REFERENCES businesses(id)
);
CREATE TRIGGER product_codes_insert BEFORE INSERT ON products
 WHEN EXISTS(SELECT 1 FROM products p WHERE p.business_id=new.business_id
  AND (p.sku=new.barcode OR p.barcode=new.sku))
 BEGIN SELECT raise(ABORT,'SKU and barcode must identify an unambiguous product'); END;
CREATE TRIGGER product_codes_update BEFORE UPDATE OF sku,barcode,business_id ON products
 WHEN EXISTS(SELECT 1 FROM products p WHERE p.business_id=new.business_id AND p.id<>new.id
  AND (p.sku=new.barcode OR p.barcode=new.sku))
 BEGIN SELECT raise(ABORT,'SKU and barcode must identify an unambiguous product'); END;
