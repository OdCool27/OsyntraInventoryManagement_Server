-- Preserve global identities, existing password hashes and every historical user reference.
ALTER TABLE users ADD COLUMN email_verified INTEGER NOT NULL DEFAULT 0 CHECK(email_verified IN(0,1));
ALTER TABLE users ADD COLUMN auth_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN mfa_secret TEXT;
ALTER TABLE users ADD COLUMN mfa_enabled INTEGER NOT NULL DEFAULT 0 CHECK(mfa_enabled IN(0,1));
ALTER TABLE users ADD COLUMN mfa_pending_secret TEXT;
ALTER TABLE users ADD COLUMN mfa_pending_expires_ms INTEGER;
ALTER TABLE users ADD COLUMN mfa_last_step INTEGER NOT NULL DEFAULT -1;
-- Preserve only explicitly seeded demo verification. Other legacy accounts verify on next sign-in.
UPDATE users SET email_verified=1 WHERE EXISTS(
 SELECT 1 FROM employees e JOIN demo_seeds d ON d.business_id=e.business_id
 WHERE e.user_id=users.id AND users.email=d.seed_key||'.'||lower(e.employee_role)||'@demo.osyntra.local'
) OR (email='operator@demo.osyntra.local' AND is_operator=1 AND (SELECT count(*) FROM demo_seeds)=2);

CREATE TABLE platform_staff (
 user_id INTEGER PRIMARY KEY REFERENCES users(id),
 role TEXT NOT NULL CHECK(role IN('SUPER_ADMIN','PROVISIONER','SUPPORT')),
 active INTEGER NOT NULL DEFAULT 1 CHECK(active IN(0,1))
);
INSERT INTO platform_staff(user_id,role) SELECT id,'SUPER_ADMIN' FROM users WHERE is_operator=1;

CREATE TABLE memberships (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id INTEGER NOT NULL REFERENCES users(id),
 business_id INTEGER NOT NULL REFERENCES businesses(id),
 role TEXT NOT NULL CHECK(role IN('OWNER','ADMINISTRATOR','MANAGER','CASHIER','INVENTORY_CLERK')),
 status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','DEACTIVATED')),
 job_title TEXT NOT NULL,
 all_branches INTEGER NOT NULL DEFAULT 0 CHECK(all_branches IN(0,1)),
 UNIQUE(user_id,business_id), UNIQUE(id,business_id),
 CHECK(role<>'OWNER' OR all_branches=1)
);
INSERT INTO memberships(id,user_id,business_id,role,status,job_title,all_branches)
 SELECT employee_id,user_id,business_id,
 CASE employee_role WHEN 'CLERK' THEN 'INVENTORY_CLERK' ELSE employee_role END,
 CASE WHEN u.status='ACTIVE' THEN 'ACTIVE' ELSE 'DEACTIVATED' END,job_title,1
 FROM employees e JOIN users u ON u.id=e.user_id WHERE e.business_id IS NOT NULL;
CREATE TABLE membership_branches (
 membership_id INTEGER NOT NULL,business_id INTEGER NOT NULL,location_id INTEGER NOT NULL,
 PRIMARY KEY(membership_id,location_id),
 FOREIGN KEY(membership_id,business_id) REFERENCES memberships(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TRIGGER tenant_identity_not_platform BEFORE INSERT ON memberships
 WHEN EXISTS(SELECT 1 FROM platform_staff WHERE user_id=new.user_id)
 BEGIN SELECT raise(ABORT,'Platform identities cannot hold tenant memberships'); END;
CREATE TRIGGER tenant_identity_update_not_platform BEFORE UPDATE OF user_id ON memberships
 WHEN EXISTS(SELECT 1 FROM platform_staff WHERE user_id=new.user_id)
 BEGIN SELECT raise(ABORT,'Platform identities cannot hold tenant memberships'); END;
CREATE TRIGGER platform_identity_not_tenant BEFORE INSERT ON platform_staff
 WHEN EXISTS(SELECT 1 FROM memberships WHERE user_id=new.user_id)
 BEGIN SELECT raise(ABORT,'Tenant identities cannot become platform staff'); END;

CREATE TABLE identity_sessions (
 id TEXT NOT NULL UNIQUE,token_hash TEXT PRIMARY KEY NOT NULL,
 user_id INTEGER NOT NULL REFERENCES users(id),membership_id INTEGER REFERENCES memberships(id),
 purpose TEXT NOT NULL CHECK(purpose IN('FULL','MFA_ENROLL','MFA_CHALLENGE','VERIFY_EMAIL')),
 mfa_verified INTEGER NOT NULL DEFAULT 0 CHECK(mfa_verified IN(0,1)),
 auth_version INTEGER NOT NULL,
 created_ms INTEGER NOT NULL,last_seen_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,revoked_ms INTEGER
);
CREATE INDEX identity_sessions_user_idx ON identity_sessions(user_id,revoked_ms);
CREATE TABLE identity_tokens (
 id TEXT NOT NULL UNIQUE,token_hash TEXT PRIMARY KEY NOT NULL,
 user_id INTEGER NOT NULL REFERENCES users(id),
 purpose TEXT NOT NULL CHECK(purpose IN('VERIFY_EMAIL','RESET_PASSWORD')),
 created_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,consumed_ms INTEGER
);
CREATE TABLE invitations (
 id TEXT PRIMARY KEY NOT NULL,business_id INTEGER NOT NULL REFERENCES businesses(id),
 email TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN('OWNER','ADMINISTRATOR','MANAGER','CASHIER','INVENTORY_CLERK')),
 all_branches INTEGER NOT NULL CHECK(all_branches IN(0,1)),
 token_hash TEXT NOT NULL UNIQUE,status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','ACCEPTED','REVOKED','EXPIRED')),
 created_by INTEGER NOT NULL REFERENCES users(id),accepted_by INTEGER REFERENCES users(id),
 created_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,
 UNIQUE(id,business_id),CHECK(role<>'OWNER' OR all_branches=1)
);
CREATE UNIQUE INDEX invitation_pending_email_unique ON invitations(business_id,email) WHERE status='PENDING';
CREATE TABLE invitation_branches (
 invitation_id TEXT NOT NULL,business_id INTEGER NOT NULL,location_id INTEGER NOT NULL,
 PRIMARY KEY(invitation_id,location_id),
 FOREIGN KEY(invitation_id,business_id) REFERENCES invitations(id,business_id),
 FOREIGN KEY(location_id,business_id) REFERENCES locations(id,business_id)
);
CREATE TABLE mfa_recovery_codes (
 user_id INTEGER NOT NULL REFERENCES users(id),code_hash TEXT NOT NULL,used_ms INTEGER,
 PRIMARY KEY(user_id,code_hash)
);
CREATE TABLE identity_audit (
 id TEXT PRIMARY KEY NOT NULL,actor_id INTEGER REFERENCES users(id),subject_id INTEGER REFERENCES users(id),
 business_id INTEGER REFERENCES businesses(id),action TEXT NOT NULL,details TEXT,
 created_ms INTEGER NOT NULL
);
CREATE INDEX identity_audit_scope_idx ON identity_audit(business_id,created_ms);
INSERT INTO identity_audit(id,subject_id,action,details,created_ms)
 SELECT lower(hex(randomblob(16))),id,'LEGACY_IDENTITY_MIGRATED',
 'Preserved password hash; only explicit demo fixtures are preverified',CAST(strftime('%s','now') AS INTEGER)*1000 FROM users;
CREATE TABLE identity_settings (id INTEGER PRIMARY KEY CHECK(id=1),key_fingerprint TEXT NOT NULL);
CREATE TABLE auth_rate_limits (bucket_hash TEXT PRIMARY KEY NOT NULL,window_ms INTEGER NOT NULL,hits INTEGER NOT NULL);
CREATE TABLE email_outbox (
 id TEXT PRIMARY KEY NOT NULL,user_id INTEGER REFERENCES users(id),actor_id INTEGER REFERENCES users(id),
 business_id INTEGER REFERENCES businesses(id),recipient TEXT NOT NULL,subject TEXT NOT NULL,body_encrypted TEXT NOT NULL,
 kind TEXT NOT NULL,reference_id TEXT NOT NULL,
 status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','SENDING','SENT','FAILED','CANCELLED')),
 attempts INTEGER NOT NULL DEFAULT 0,next_attempt_ms INTEGER NOT NULL,lease_until_ms INTEGER,last_error_code TEXT,
 created_ms INTEGER NOT NULL,sent_ms INTEGER
);
CREATE INDEX outbox_delivery_idx ON email_outbox(status,next_attempt_ms);
CREATE UNIQUE INDEX outbox_reference_unique ON email_outbox(kind,reference_id);
ALTER TABLE audit_logs ADD COLUMN location_id INTEGER REFERENCES locations(id);
