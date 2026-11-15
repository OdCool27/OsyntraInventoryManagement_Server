# Orvexa B1 — Identity, memberships, tenancy and invitations

> Historical B1 snapshot: B2 has subsequently been implemented. See [ORVEXA_B2_IMPLEMENTATION.md](ORVEXA_B2_IMPLEMENTATION.md) for current settings, tax, onboarding and verification results. B3–B5 have subsequently completed the functional server MVP; see [the current guide](ORVEXA_MVP_B3_B5.md).

B1 extends the existing server in place. SQLite remains the default and the test database by user direction. The frontend is separate. B2–B9 are outside this change; the implementation stops at B1 for review.

## Implemented behavior

- One global email/password identity can join multiple businesses. Each membership has exactly one of OWNER, ADMINISTRATOR, MANAGER, CASHIER or INVENTORY_CLERK, with all-branch access or explicit grants. Owners always have all-branch access.
- Opaque Bearer sessions select a validated membership. User status, membership status, role, grants, business status and MFA requirements are rechecked from the database. Client headers/body cannot override this authority. Basic authentication is disabled.
- Owners may manage all tenant roles; Administrators may manage non-owners; Managers may manage Cashier/Inventory Clerk memberships only within their own grants. The last active owner cannot be demoted/deactivated. Legacy employee administration now changes the selected membership, not the global user's name/password/status.
- Verified users can create a business and initial branch. Becoming an Owner revokes sessions and requires MFA at next login. Branch creation/settings require Owner/Administrator authority and appropriate all-branch access. The final active branch cannot be disabled; pending orders/counts block disabling their branch.
- Invitation issue/list/revoke/accept is tenant scoped. Invitations expire after 48 hours, use hashed single-use tokens, and retain issuer/recipient audit. Acceptance revalidates the issuer's current role and grants. Privilege changes revoke pending invitations issued by the changed member. An existing global user must authenticate as the same recipient; acceptance never overwrites their password. New invitees prove email ownership through the invitation.
- Signup, verification and password reset use generic request responses. Verification links last one day; reset links 30 minutes. Resending invalidates older links. Passwords require 12–72 characters and at most 72 UTF-8 bytes.
- Mandatory TOTP MFA for Owners and active platform staff, optional MFA for other users. Enrollment/confirmation, replay prevention, encrypted seeds, ten single-use hashed recovery codes, password-confirmed recovery and optional disable are implemented. Required MFA cannot be disabled. Password reset retains MFA.
- FULL sessions have an eight-hour absolute and 30-minute idle limit; partial sessions last five minutes. Membership switching rotates the token without extending its deadline. Logout, session revoke, logout-all, password reset and privilege changes revoke access. Sessions/token lookup values are stored as hashes.
- Persistent authentication rate limits use HMAC buckets for email/IP scope, and do not trust forwarded IP headers. Limits survive failed authentication transactions. 429 returns RATE_LIMITED and Retry-After.
- Platform staff authority lives in a separate table, with SUPER_ADMIN/PROVISIONER/SUPPORT roles. Database constraints prohibit platform identities holding tenant memberships. Only provisioning-capable staff can use existing platform provisioning routes; no support impersonation access is introduced.
- Security audit records verification/reset, MFA and invitation/membership changes without secret material. Self events are user scoped; tenant-wide security audit requires an all-branch Owner/Administrator.

## Branch enforcement

Staff stock reads/writes, checkout/order history and actions, counts, opening imports, reports and location audit enforce the active membership's grants. A supplied inaccessible/cross-tenant resource returns 404. Catalog and verified fitments remain business-wide. Public storefront behavior is unchanged. All-branch grants cover future branches; explicit grants do not.

Disabling a branch preserves history. Existing historical data remains readable to permitted staff; new stock/count/import/checkout activity requires an active branch. Business-wide audit records without a branch are hidden from branch-restricted managers.

## API examples

Authenticate with POST /api/v1/auth/login:

```json
{"email":"automotive.manager@demo.osyntra.local","password":"YOUR_CONFIGURED_DEMO_PASSWORD"}
```

Use accessToken as Authorization: Bearer TOKEN. Check purpose before proceeding: FULL, VERIFY_EMAIL, MFA_ENROLL or MFA_CHALLENGE. The README describes verification and authenticator setup. /api/auth remains an alias for the identity endpoints; existing domain routes retain /api.

List GET /api/v1/memberships. Switch with POST /api/v1/auth/business:

```json
{"membershipId":123}
```

Replace the prior token with the new token. GET /api/v1/auth/me supplies current identity, membership and available businesses; the frontend should build its business selector from these server responses.

An Owner/Administrator (or appropriately scoped Manager) issues POST /api/v1/invitations:

```json
{"email":"new.staff@example.com","role":"INVENTORY_CLERK","allBranches":false,"branchIds":[1]}
```

The response contains safe metadata, not a token. Read delivery through the configured provider or development preview. Accept via POST /api/v1/invitations/accept:

```json
{"token":"TOKEN_FROM_DELIVERY","firstName":"New","lastName":"Staff","password":"NEW_STRONG_PASSWORD"}
```

For an existing recipient, authenticate first and submit only the token. Acceptance revokes sessions; log in again. Owner invitations require MFA before access.

Create POST /api/v1/businesses with name, locationName, currency and defaultThreshold. Manage GET/POST /api/v1/branches and PUT /api/v1/branches/{id} with name/active. Manage GET /api/v1/business/memberships and PUT /api/v1/business/memberships/{id}:

```json
{"role":"CASHIER","status":"ACTIVE","allBranches":false,"branchIds":[1],"jobTitle":"Cashier"}
```

Password reset: POST /api/v1/auth/password-reset with email; POST /api/v1/auth/password-reset/confirm with token/password. Both confirmation and verification require a fresh login afterward. Recovery after losing an authenticator requires the password plus an unused recovery code through /api/v1/auth/mfa/recover; enroll a replacement factor using the returned limited session. Store newly issued recovery codes securely.

Session management: GET /api/v1/auth/sessions; DELETE /api/v1/auth/sessions/{id}; POST /api/v1/auth/logout or /logout-all. Audit: GET /api/v1/auth/security-events and /api/v1/business/security-audit (limit/offset).

## Email and secret handling

The user approved local preview plus opt-in SMTP. Local sqlite,demo enables preview explicitly; GET /api/v1/auth/mail-preview requires a loopback request and authentication, scoped to recipient or issuer. The default application does not enable preview. No external provider credentials were invented or email sent.

Outbox enqueue shares the invitation/verification/reset transaction. Payloads are AES-256-GCM encrypted with record-specific associated data. A worker claims a two-minute lease in its own committed transaction, sends outside the business transaction, and records delivery/failure afterward. Retries wait one minute and stop after five attempts. Expired/consumed/revoked pending links are cancelled before delivery. A delivery interrupted after remote acceptance may be duplicated; this is at-least-once delivery, with single-use links and a stable Message-ID. Provider delivery remains a manual integration check.

ORVEXA_PUBLIC_BASE_URL is server configuration, never the request Host header. It requires HTTPS except loopback development HTTP. Links target the separately developed frontend routes /verify-email, /reset-password and /accept-invitation. Until that frontend exists, extract token from local preview and call the API directly.

The private local key is .local/orvexa-security.key (owner-only permissions), or ORVEXA_SECURITY_KEY supplies a Base64 32-byte key. A persisted fingerprint refuses a changed or missing key after initialization. Back up the key securely with the database; the SQLite backup utility does not copy it. Restore both the database and its original key. No token, password, MFA seed or email payload is logged.

Design references: [OWASP Forgot Password guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html) for generic, expiring single-use recovery; [OWASP session guidance](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html) for expiry/revocation; [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238) for TOTP and official test vectors.

## Migration and compatibility

Only new SQLite V7 is added; V1–V6 and historical PostgreSQL migrations are preserved. V7 backfills memberships using existing employee IDs, maps CLERK to INVENTORY_CLERK, retains all previous branch access, and separates platform staff. Old employees rows remain historical; runtime authority uses memberships/platform_staff. Existing user IDs, password hashes, catalog, stock, orders and history are preserved.

Legacy accounts must verify email, except explicitly seeded demo accounts. No existing user is silently enrolled into MFA. Owners/platform staff must enroll on first B1 login. The demo password stays in ignored local configuration and is never reset by seeding. The local .env and PostgreSQL settings remain unchanged. PostgreSQL execution stays guarded pending a separate port/test effort.

Compatibility changes for the separate frontend: replace HTTP Basic with Bearer login and purpose handling, replace the legacy CLERK role label with INVENTORY_CLERK, select memberships for multi-business users, and handle revocation after membership changes. Operational /api routes and public order capabilities remain available.

## Verification

The B1 suite covers real Bearer HTTP authorization, two-business switching, role/branch/tenant tampering, last-owner protection, invitation expiry/replay/revocation and concurrent acceptance, MFA enrollment/replay/recovery/optional disable, email/reset expiry, session ownership/logout/idle expiry, persistent rate limits, outbox commit/rollback/retry, preview scope, encryption-key binding and audit scope. RFC 6238 vectors verify TOTP independently. Populated V1 and V6 upgrades verify hashes, legacy-role mapping, verification requirements, stock/history and foreign keys. Existing inventory/catalog/commerce and concurrency regression tests remain included.

Actual results:

- `mvn -q test`: **70 tests, zero failures, errors or skips**.
- `mvn -q "-DskipTests" package`: successful executable JAR packaging after the passing test run.
- A consistent, integrity-checked pre-upgrade SQLite backup was created at `target/orvexa-b1-before-upgrade.db`. V7 then applied successfully to the existing local database.
- The packaged backend was restarted in `sqlite,demo` mode on port 8080. Live health/readiness were UP without component/details disclosure; OpenAPI exposes Bearer authentication.
- All eight existing demo passwords passed. Owners received MFA_ENROLL and were denied catalog access until enrollment; managers, clerks and cashiers received FULL access. Every verification session was logged out and rejected afterward. No real account MFA factor was enrolled automatically.
- Before/after comparisons confirmed unchanged global user IDs/emails/password hashes and inventory balances. Migrated membership counts matched the old tenant employees. SQLite integrity and foreign-key checks passed.
- The local `.env` SHA-256 matched its pre-B1 baseline. No PostgreSQL connection, schema migration or external SMTP delivery was performed.
- `git diff --check` passed.

Changed feature files are under `identity/`, with `MembershipController`, the revised `AuthController`, `SecurityConfig`, employee/tenant repositories/services and branch-aware inventory/commerce/report/audit services. New persistence is only `V7__identity_memberships_and_sessions.sql`. Focused evidence is in `IdentityB1Tests`, `TotpTests` and `MigrationUpgradeTests`; existing request/live/demo tests now use the applicable B1 contracts. README, DEMO, configuration examples and architecture/progress notes document the handoff.

## Review boundary and manual work

This is a local SQLite implementation, not a production readiness claim. SMTP provider delivery, deployment TLS/proxy configuration, distributed abuse controls, encryption-key custody/restore operations, monitoring and PostgreSQL parity require separate integration/operational review. Email preview is for local development only. Existing checkout payments remain simulated. B2 tax/onboarding work is pending review of B1.
