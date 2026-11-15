CREATE TABLE business_preferences (
 business_id INTEGER PRIMARY KEY REFERENCES businesses(id), revision INTEGER NOT NULL DEFAULT 0,
 receipt_name TEXT NOT NULL DEFAULT '',receipt_footer TEXT NOT NULL DEFAULT '',
 low_stock_notifications INTEGER NOT NULL DEFAULT 1 CHECK(low_stock_notifications IN(0,1)),
 allow_branch_price_mode INTEGER NOT NULL DEFAULT 0 CHECK(allow_branch_price_mode IN(0,1))
);
INSERT INTO business_preferences(business_id) SELECT id FROM businesses;
CREATE TRIGGER business_preferences_defaults AFTER INSERT ON businesses BEGIN
 INSERT INTO business_preferences(business_id) VALUES(new.id);
END;
CREATE TABLE branch_preferences (
 location_id INTEGER PRIMARY KEY,business_id INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,
 receipt_name TEXT,receipt_footer TEXT,low_stock_notifications INTEGER CHECK(low_stock_notifications IN(0,1)),
 price_mode TEXT CHECK(price_mode IN('INCLUSIVE','EXCLUSIVE')),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE tax_templates (
 code TEXT PRIMARY KEY,label TEXT NOT NULL,classification TEXT NOT NULL,rate_percent TEXT NOT NULL,
 illustrative INTEGER NOT NULL DEFAULT 1 CHECK(illustrative=1),requires_verification INTEGER NOT NULL DEFAULT 1 CHECK(requires_verification=1)
);
INSERT INTO tax_templates VALUES
 ('JM_STANDARD_EXAMPLE','Illustrative Jamaica standard GCT — verify eligibility','TAXABLE','15',1,1),
 ('JM_ZERO_EXAMPLE','Illustrative zero-rated category — verify eligibility','ZERO_RATED','0',1,1),
 ('JM_EXEMPT_EXAMPLE','Illustrative exempt category — verify eligibility','EXEMPT','0',1,1);

CREATE TABLE tax_policy_versions (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL REFERENCES businesses(id),
 effective_from_ms INTEGER NOT NULL,
 registration_status TEXT NOT NULL CHECK(registration_status IN('NOT_REGISTERED','REGISTERED')),
 registration_number TEXT NOT NULL DEFAULT '',price_mode TEXT NOT NULL CHECK(price_mode IN('INCLUSIVE','EXCLUSIVE')),
 verified INTEGER NOT NULL CHECK(verified IN(0,1)),verification_note TEXT NOT NULL,
 created_by INTEGER REFERENCES users(id),created_ms INTEGER NOT NULL,
 UNIQUE(business_id,effective_from_ms),UNIQUE(id,business_id)
);
INSERT INTO tax_policy_versions SELECT 'baseline-'||id,id,0,'NOT_REGISTERED','','EXCLUSIVE',0,'Unverified legacy/default nonregistered setting',NULL,0 FROM businesses;
CREATE TRIGGER business_tax_defaults AFTER INSERT ON businesses BEGIN
 INSERT INTO tax_policy_versions VALUES('baseline-'||new.id,new.id,0,'NOT_REGISTERED','','EXCLUSIVE',0,'Unverified default nonregistered setting',NULL,0);
END;
CREATE TABLE tax_profiles (
 business_id INTEGER NOT NULL REFERENCES businesses(id),code TEXT NOT NULL,
 PRIMARY KEY(business_id,code)
);
CREATE TABLE tax_rate_versions (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,code TEXT NOT NULL,
 label TEXT NOT NULL,classification TEXT NOT NULL CHECK(classification IN('TAXABLE','ZERO_RATED','EXEMPT')),
 rate_percent TEXT NOT NULL CHECK(CAST(rate_percent AS NUMERIC)>=0 AND CAST(rate_percent AS NUMERIC)<=100),effective_from_ms INTEGER NOT NULL,
 illustrative INTEGER NOT NULL CHECK(illustrative IN(0,1)),
 verified INTEGER NOT NULL CHECK(verified IN(0,1)),verification_note TEXT NOT NULL,
 created_by INTEGER NOT NULL REFERENCES users(id),created_ms INTEGER NOT NULL,
 CHECK(classification='TAXABLE' OR CAST(rate_percent AS NUMERIC)=0),
 FOREIGN KEY(business_id,code) REFERENCES tax_profiles(business_id,code),
 UNIQUE(business_id,code,effective_from_ms),UNIQUE(id,business_id)
);
CREATE TABLE product_tax_versions (
 id TEXT PRIMARY KEY,business_id INTEGER NOT NULL,product_id TEXT NOT NULL,code TEXT NOT NULL,
 effective_from_ms INTEGER NOT NULL,created_by INTEGER NOT NULL REFERENCES users(id),created_ms INTEGER NOT NULL,
 FOREIGN KEY(product_id,business_id) REFERENCES products(id,business_id),
 FOREIGN KEY(business_id,code) REFERENCES tax_profiles(business_id,code),
 UNIQUE(product_id,effective_from_ms),UNIQUE(id,business_id)
);
CREATE TABLE onboarding_steps (
 business_id INTEGER NOT NULL REFERENCES businesses(id),step TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN('NOT_STARTED','IN_PROGRESS','COMPLETE','SKIPPED')),
 notes TEXT NOT NULL,revision INTEGER NOT NULL,updated_by INTEGER NOT NULL REFERENCES users(id),updated_ms INTEGER NOT NULL,
 PRIMARY KEY(business_id,step)
);
ALTER TABLE orders ADD COLUMN net_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN tax_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN tax_snapshot_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN tax_policy_id TEXT;
ALTER TABLE orders ADD COLUMN seller_registration_status TEXT NOT NULL DEFAULT 'LEGACY_UNSPECIFIED';
ALTER TABLE orders ADD COLUMN seller_registration_number TEXT NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN seller_tax_verified INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN price_mode TEXT NOT NULL DEFAULT 'EXCLUSIVE';
ALTER TABLE orders ADD COLUMN tax_calculated_at TEXT;
ALTER TABLE orders ADD COLUMN receipt_name TEXT NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN receipt_footer TEXT NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN preferences_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN branch_preferences_revision INTEGER NOT NULL DEFAULT 0;
UPDATE orders SET net_cents=total_cents;
ALTER TABLE order_lines ADD COLUMN net_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN tax_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN total_cents INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN tax_classification TEXT NOT NULL DEFAULT 'LEGACY_UNSPECIFIED';
ALTER TABLE order_lines ADD COLUMN tax_rate_percent TEXT NOT NULL DEFAULT '0';
ALTER TABLE order_lines ADD COLUMN tax_profile_code TEXT;
ALTER TABLE order_lines ADD COLUMN tax_rate_version_id TEXT;
ALTER TABLE order_lines ADD COLUMN product_tax_version_id TEXT;
ALTER TABLE order_lines ADD COLUMN tax_rule_label TEXT NOT NULL DEFAULT 'Legacy order; tax not reconstructed';
ALTER TABLE order_lines ADD COLUMN tax_verified INTEGER NOT NULL DEFAULT 0;
ALTER TABLE order_lines ADD COLUMN tax_illustrative INTEGER NOT NULL DEFAULT 0;
UPDATE order_lines SET net_cents=price_cents*quantity,total_cents=price_cents*quantity;

-- Amounts and tax policy/classification snapshots cannot change during payment or reversal.
CREATE TRIGGER order_tax_snapshot_immutable BEFORE UPDATE OF total_cents,net_cents,tax_cents,currency,tax_snapshot_version,tax_policy_id,seller_registration_status,seller_registration_number,seller_tax_verified,price_mode,tax_calculated_at,receipt_name,receipt_footer,preferences_revision,branch_preferences_revision ON orders
 BEGIN SELECT raise(ABORT,'Order monetary snapshots are immutable'); END;
CREATE TRIGGER order_line_snapshot_immutable BEFORE UPDATE ON order_lines
 BEGIN SELECT raise(ABORT,'Order line snapshots are immutable'); END;
CREATE TRIGGER tax_policy_immutable BEFORE UPDATE ON tax_policy_versions
 BEGIN SELECT raise(ABORT,'Create a new effective-dated tax policy version'); END;
CREATE TRIGGER tax_rate_immutable BEFORE UPDATE ON tax_rate_versions
 BEGIN SELECT raise(ABORT,'Create a new effective-dated tax rate version'); END;
CREATE TRIGGER product_tax_immutable BEFORE UPDATE ON product_tax_versions
 BEGIN SELECT raise(ABORT,'Create a new effective-dated classification version'); END;
CREATE TRIGGER order_amounts_valid BEFORE INSERT ON orders
 WHEN new.net_cents<0 OR new.tax_cents<0 OR new.total_cents-new.net_cents<>new.tax_cents
 BEGIN SELECT raise(ABORT,'Order net plus tax must equal total'); END;
CREATE TRIGGER order_line_amounts_valid BEFORE INSERT ON order_lines
 WHEN new.net_cents<0 OR new.tax_cents<0 OR new.total_cents-new.net_cents<>new.tax_cents
 BEGIN SELECT raise(ABORT,'Order line net plus tax must equal total'); END;
