# Orvexa Backend — Osyntra

Orvexa is Osyntra's Java/Spring Boot retail, inventory and POS backend. The frontend is developed separately; this repository supplies employee, inventory, POS, storefront and reporting APIs.

Backend implementation follows [the v2 architecture plan](Osyntra_MVP_Architecture_and_Phases_v2.md). Phases 1–5 are implemented for the local SQLite demonstration. [MVP_PROGRESS.md](MVP_PROGRESS.md) records phase evidence, [DEMO.md](DEMO.md) provides a walkthrough, and [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) records policies and migration decisions. This is a local SQLite MVP with cash and manually confirmed terminal recordkeeping, plus an explicitly enabled demo payment simulator. No bank API integration or production deployment is claimed. [POST_MVP_REVIEW.md](POST_MVP_REVIEW.md) defines the review before a real deployment.

## Run

The newer [Orvexa implementation plan](Orvexa_Backend_Implementation_Prompts.md) is being executed one phase at a time. [B0 assessment](ORVEXA_B0_ASSESSMENT.md) inventories the existing entities/APIs, documents the staged relationship and migration plan, and identifies the broader B1–B9 requirements. [B1 implementation](ORVEXA_B1_IMPLEMENTATION.md) adds memberships, branch grants, invitations, verification/reset, Bearer sessions and MFA. [B2 implementation](ORVEXA_B2_IMPLEMENTATION.md) adds settings/branch inheritance, resumable onboarding and versioned sales tax with immutable checkout snapshots. [B3–B5 implementation](ORVEXA_MVP_B3_B5.md) completes the requested functional server MVP: catalogue/units/imports, purchasing/location valuation/batches/transfers, and pricing/payment reconciliation/partial reversals/receipts. B6–B9 remain deferred. The older SQLite demo's phases 1–5 do not mean the broader Orvexa phases are complete. SQLite remains the default by explicit user direction; PostgreSQL is retained for readiness assessment.

Install JDK 24 and Maven 3.9+. Run commands from the repository root:

```powershell
mvn spring-boot:run
```

SQLite is the default profile and creates `osyntra-mvp.db` in the working directory. Neither Docker nor PostgreSQL is needed. Existing `.env` values and PostgreSQL credentials are preserved; do not overwrite an existing environment file.

For a new checkout only:

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

- Swagger: http://localhost:8080/swagger-ui/index.html
- OpenAPI: http://localhost:8080/v3/api-docs
- Login: `POST /api/v1/auth/login`; current identity: `GET /api/v1/auth/me`
- Public status: http://localhost:8080/actuator/health
- Liveness/readiness: `/actuator/health/liveness`, `/actuator/health/readiness` (readiness includes the database; no public database details)
- Default frontend origin allowed by CORS: `http://localhost:5173`

Authentication uses opaque Bearer sessions and BCrypt passwords. POST `/api/v1/auth/login` with `{"email":"…","password":"…"}`. Keep the returned `accessToken` and pass `Authorization: Bearer <accessToken>`. HTTP Basic is disabled. The `/api/auth` paths remain aliases for the new identity endpoints.

Inspect the returned `purpose` before opening a business:

- `FULL`: call `GET /api/v1/auth/me`. A single membership is selected automatically. For multiple businesses, GET `/api/v1/memberships` and POST `/api/v1/auth/business` with `{"membershipId":123}`; replace the old token with the returned token.
- `VERIFY_EMAIL`: request `POST /api/v1/auth/email-verification` with your email, confirm the delivered token through `POST /api/v1/auth/email-verification/confirm`, then log in again.
- `MFA_ENROLL`: POST `/api/v1/auth/mfa/setup` with `{}`, add the returned `otpauthUri` or secret to an authenticator, and POST `/api/v1/auth/mfa/confirm` with `{"code":"123456"}`. Use the new FULL token and save the ten recovery codes securely.
- `MFA_CHALLENGE`: POST `/api/v1/auth/mfa/verify` with the current authenticator code (or `{"recoveryCode":"…"}`), then use the new FULL token.

Owners and active platform staff must enroll MFA; other roles can opt in. A password reset does not bypass MFA. Sessions expire after eight hours or 30 idle minutes; verification/MFA sessions last five minutes. Logout, password reset and membership changes revoke sessions. [The B1 guide](ORVEXA_B1_IMPLEMENTATION.md) includes invitation, recovery and email flows for the separate frontend.

Configure `OSYNTRA_OPERATOR_EMAIL` / `OSYNTRA_OPERATOR_PASSWORD` for the first platform operator; `SENTRA_ADMIN_EMAIL` / `SENTRA_ADMIN_PASSWORD` remain fallback names. Bootstrap only creates a missing operator with an unused email; it never elevates an existing account or resets passwords. Operators can provision businesses but have no routine tenant-data access. Non-demo legacy accounts must verify their email before full access.

## Optional two-business demo

Existing local demo accounts keep their current passwords. Their configured password is the `OSYNTRA_DEMO_PASSWORD` value in your ignored `.env`; PostgreSQL and operator credentials remain separate. There is no built-in demo password in the application.

From the repository root, start the backend in demo mode:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=sqlite,demo"
```

Alternatively, after packaging with `mvn package`, start the executable JAR:

```powershell
java -jar target/OsyntraInventoryManagement-0.0.1-SNAPSHOT.jar --spring.profiles.active=sqlite,demo
```

Keep the terminal running. Open Swagger and call `POST /api/v1/auth/login` with a demo email and your configured password. For a quick stock/POS demonstration, use a manager, clerk or cashier. Copy the returned `accessToken` into **Authorize** (token only). Seeded demo accounts sign in directly with email/password: no MFA enrollment, authenticator code, recovery code or email verification step is required, even if a demo account was previously enrolled. This exemption requires the opt-in demo profile and explicit seeded fixture identity; normal accounts retain their MFA policy. Call `GET /api/v1/auth/me` to confirm the selected membership. Stop the server with **Ctrl+C** in its terminal.

For a fresh checkout, set `OSYNTRA_DEMO_PASSWORD` in your ignored `.env`, or prompt for it in the current PowerShell session before starting:

```powershell
$demoPassword = Read-Host 'Choose a local demo password' -AsSecureString
$env:OSYNTRA_DEMO_PASSWORD = [System.Net.NetworkCredential]::new('', $demoPassword).Password
mvn spring-boot:run "-Dspring-boot.run.profiles=sqlite,demo"
```

You may choose a different local password of 12–72 characters, at most 72 UTF-8 bytes, before seeding a new database. Changing the variable later does **not** reset passwords for already-created accounts.

The opt-in demo profile seeds an automotive tenant and a grocery tenant with their own location, owner, manager, clerk, cashier, product and stock. It is repeatable and does not reseed stock or reset passwords on subsequent starts.

Tenant accounts use the chosen demo password:

| Tenant | Account emails |
| --- | --- |
| Automotive | automotive.owner@demo.osyntra.local, automotive.manager@demo.osyntra.local, automotive.clerk@demo.osyntra.local, automotive.cashier@demo.osyntra.local |
| Grocery | grocery.owner@demo.osyntra.local, grocery.manager@demo.osyntra.local, grocery.clerk@demo.osyntra.local, grocery.cashier@demo.osyntra.local |

If there is no configured/existing platform operator, this opt-in profile also creates `operator@demo.osyntra.local` with the chosen demo password. Otherwise the existing operator keeps its own credentials.

Demo vehicle compatibility is a clearly labeled, structured fixture for Honda Civic 2006–2011. It is not a manufacturer guarantee for an actual retail part.

## Configuration and data

| Variable | Purpose |
| --- | --- |
| SQLITE_DB_PATH | Local SQLite file; default `osyntra-mvp.db`; parent directory must exist |
| SERVER_PORT | HTTP port; default 8080 |
| OSYNTRA_OPERATOR_EMAIL / OSYNTRA_OPERATOR_PASSWORD | First platform operator |
| SENTRA_ADMIN_EMAIL / SENTRA_ADMIN_PASSWORD | Preserved bootstrap fallback names |
| OSYNTRA_DEMO_PASSWORD | Required only for the opt-in demo fixtures |
| OSYNTRA_ALLOWED_ORIGINS | Comma-separated exact frontend origins; default `http://localhost:5173` |
| POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD / DATABASE_URL | Preserved PostgreSQL configuration for later database migration |

The SQLite, test and PostgreSQL connection settings have separate profiles/migration locations. New SQLite migrations preserve baseline data under a single explicit business and location; no existing data is split across tenants or silently discarded. SKU uniqueness is now per business. Barcode lookup is exact after trimming, unique per business when supplied, and cannot collide with another product's SKU.

The current phases use SQLite. PostgreSQL connection settings, original migration contents and Compose volume remain intact. A startup guard rejects the PostgreSQL profile before database initialization: the new tenant/commerce schema and behavior must be migrated and verified separately during the post-MVP review. See [README.Docker.md](README.Docker.md). No data is automatically transferred between databases.

## Roles and tenant boundaries

A global user can have memberships in multiple businesses. Each membership has one fixed role and either all-branch access or explicit branch grants. Business context comes from the session's validated active membership, never an arbitrary request header or body field. Repositories filter by membership and composite foreign keys reject cross-business relationships. Cross-tenant resource IDs return 404; unauthorized actions return 403.

| Role | Current responsibilities |
| --- | --- |
| Platform operator | List business metadata; provision a business, location and first owner |
| OWNER | Business settings, staff, catalog, stock, counts, checkout, fulfillment, refunds/returns and reports |
| ADMINISTRATOR | Business settings, branches, non-owner membership administration and all manager operations |
| MANAGER | Operational staff creation/editing, catalog, stock, count approval, checkout, fulfillment, refunds/returns and reports |
| INVENTORY_CLERK | Catalog/stock reads, draft count entry/submission, cart preview, order reads and fulfillment |
| CASHIER | Catalog/stock reads, cart preview, POS checkout, simulated payment and eligible cancellation |

Only owners can grant/edit OWNER memberships. Administrators cannot edit owners; managers can manage Cashier and Inventory Clerk memberships within their own branch grants. A business must retain an active owner. Membership deactivation revokes that user's sessions and pending invitations they issued, while leaving their global identity and other memberships intact. Platform operators have no routine tenant-data access.

## Endpoint inventory

Lists use `limit` (default 50, max 200) and `offset` where exposed. UUIDs identify products, fitments, count/import sessions and orders; business/location/employee IDs are numeric.

| Area | Endpoints |
| --- | --- |
| Identity | POST /api/v1/auth/register, /login, /email-verification, /email-verification/confirm, /password-reset, /password-reset/confirm, /business, /logout, /logout-all; GET /me, /sessions, /security-events; DELETE /sessions/{id} (all under /api/v1/auth) |
| MFA | POST /api/v1/auth/mfa/setup, /confirm, /verify, /recover, /disable |
| Memberships | GET /api/v1/memberships; GET /api/v1/business/memberships; PUT /api/v1/business/memberships/{id}; GET /api/v1/business/security-audit |
| Onboarding/branches | POST /api/v1/businesses; GET/POST /api/v1/branches; PUT /api/v1/branches/{id} |
| Invitations | GET/POST /api/v1/invitations; DELETE /api/v1/invitations/{id}; POST /api/v1/invitations/accept |
| Provisioning | GET/POST /api/platform/businesses |
| Business | GET/PUT /api/business; GET/POST /api/locations |
| Preferences | GET/PUT /api/v1/settings; GET/PUT /api/v1/branches/{id}/settings |
| Tax | GET /api/v1/tax/templates; GET/POST /api/v1/tax/policies; GET /api/v1/tax/profiles; POST /api/v1/tax/profiles/{code}/versions; GET/POST /api/v1/products/{id}/tax |
| Onboarding state | GET /api/v1/onboarding; PUT /api/v1/onboarding/{step} |
| Employees | GET/POST /api/employees; PUT /api/employees/{id} |
| Categories | GET/POST /api/categories; GET/PUT/DELETE /api/categories/{id} |
| Products | GET/POST /api/products; GET/PUT/DELETE /api/products/{id} |
| Fitment | POST /api/fitments; GET /api/products/{id}/fitments; POST/DELETE /api/products/{id}/fitments/{fitmentId} |
| Stock | GET /api/inventory; GET/POST /api/inventory/{productId}; POST /api/inventory/{productId}/movements; PUT /api/inventory/{productId}/reorder-level |
| Movement/alerts | GET /api/inventory/movements; GET /api/inventory/alerts; GET/POST /api/inventory/reasons |
| Counting | GET/POST /api/counts; GET /api/counts/{id}; PUT /api/counts/{id}/lines; POST /api/counts/{id}/submit, /reopen, /approve |
| Opening CSV | GET /api/imports/opening/template; POST /api/imports/opening/preview; GET /api/imports/opening/{id}; POST /api/imports/opening/{id}/commit |
| Staff commerce | POST /api/orders/cart; GET/POST /api/orders; GET /api/orders/{id}; POST /api/orders/{id}/payment, /cancel, /fulfill, /return, /refund; GET /api/orders/{id}/receipt |
| Public commerce | GET /api/storefront/{businessId}/products; POST /api/storefront/{businessId}/cart, /orders; GET /api/storefront/{businessId}/orders/{id}; POST .../payment, .../cancel; GET .../receipt |
| Reporting | GET /api/reports/summary, /low-stock, /out-of-stock, /sales, /stock.csv |
| Employee audit | GET /api/audit?entityType=...&entityId=... |

The category DELETE API rejects categories still referenced by products. Product DELETE archives the product and preserves sale/movement history. Product prices/costs in order lines are immutable snapshots.

Product queries accept `search`, `category`, `barcode`, `make`, `model`, `year`, `engine`, `trim` and staff-only `includeInactive`. Fitment is relational and requires a verification source/actor; one configuration can link to many products and a product can link to many configurations. Without an engine/trim query, configurations limited to a specific engine/trim are excluded.

Stock is per location and product. Supply `locationId` to select a location; omission selects the first active location permitted by the selected membership. Orders, counts, imports, stock, reports and location audit enforce branch grants. Catalog/fitment remains shared within the selected business. Unknown or cross-tenant locations return 404. Available quantity is on-hand minus reserved.

Unknown JSON fields fail validation. Ordinary validation errors return 400, unauthenticated requests 401, forbidden actions 403, missing/cross-tenant resources 404, and duplicate/conflicting state 409. Creation usually returns 201. A duplicate idempotent command returns its existing state without applying stock changes again.

B0 standardizes failures as `application/problem+json` with stable `code` and `requestId` fields, including security-filter 401/403 responses. Database availability failures return 503 with `Retry-After: 1`; internal/SQL exception diagnostics are not returned to clients. Every HTTP response has `X-Request-ID`. Clients may supply a canonical UUID; invalid values are replaced. CORS permits/exposes that header for configured frontend origins. It traces an HTTP call and does not replace a command's idempotency key. See [the B0 error example and codes](ORVEXA_B0_ASSESSMENT.md#api-contract-additions).

## Functional MVP: B3–B5

[The B3–B5 guide](ORVEXA_MVP_B3_B5.md) documents the complete new contracts and operator flows. Existing identity/MFA and B2 tax setup remain required.

- Catalogue: SIMPLE/PARENT/VARIANT products through existing product routes; units and exact base conversions; internal ORV- barcodes and SVG/PNG/PDF labels; bounded CSV/XLSX templates, mapping, preview/error/status and atomic commit.
- Inventory: per-location acquisition value and weighted average, suppliers/POs/partial receiving, immutable receipt costs, optional batches/expiry and approved batch counts, and requested/dispatched/partially received transfers retaining in-transit value.
- Checkout: exact SKU/barcode lookup with effective branch prices, controlled nonstacking discounts/promotions, immutable tax/unit/price snapshots, cash/manual-terminal attempts and privileged reconciliation, approved partial returns/refunds, and saved text/HTML/PDF/email receipt payloads.

Base stock remains integral; decimal unit factors resolve exactly to a chosen small base unit (for example GRAM or EACH). Product thresholds are global and apply separately to each branch; old per-branch thresholds are archived during V9. Legacy values are initialized from catalogue cost and labeled estimates. Actual new acquisition costs should use purchase orders or valued adjustments.

The frontend can follow: login/MFA → business/branch/tax setup → create/import catalogue → PO receipt → checkout reservation → payment confirmation/reconciliation → immutable receipt → reports. `FunctionalMvpTests` proves that flow through a real Bearer session, including a partial return.

| Area | New routes |
| --- | --- |
| Units/labels | /api/v1/catalogue/products/{id}/units, /convert, /barcode, /label |
| Catalogue import | /api/v1/catalogue/imports/template, /csv, /xlsx, /{id}, /{id}/commit |
| Purchasing | /api/v1/purchasing/suppliers, /orders, /orders/{id}/receive, /orders/{id}/cancel |
| Valuation/batches | /api/v1/inventory/adjustments, /products/{id}/tracking, /batches, /batches/{id}/count |
| Transfers | /api/v1/inventory/transfers, /{id}/dispatch, /{id}/receive, /{id}/cancel |
| POS lookup/pricing | /api/v1/checkout/catalogue, /prices, /promotions, /promotions/{id}/revoke |
| Payment attempts | /api/v1/checkout/orders/{id}/payments, /payments/{id}/reconcile |
| Approved reversals | /api/v1/checkout/orders/{id}/reversals, /reversals/{id}/review, /reversals/{id}/complete |
| Receipts | /api/v1/checkout/orders/{id}/receipt?format=text\|html\|pdf, /orders/{id}/receipt-email |

## Stock and count requests

Manual opening stock:

```json
{"quantityOnHand":10,"reorderLevel":3,"reason":"OPENING_COUNT","requestId":"opening-unique-id"}
```

A movement has type `RECEIPT`, `DAMAGE` or `CORRECTION`, a configured reason, optional notes and a unique request ID:

```json
{"type":"DAMAGE","quantity":1,"reason":"DAMAGE","notes":"Broken in handling","requestId":"damage-unique-id"}
```

Receipts/damage use positive quantities. Corrections use a signed nonzero delta. Raw absolute quantity endpoints and the old unreasoned add/remove commands have been replaced by movements and approved counts. Every change, including reservations/releases, has a ledger entry with location, deltas, resulting balance and actor/context.

Create a count with `{}` or `{"locationId":1}`. Save a line with `{"code":"SKU-OR-BARCODE","quantity":9}`. Drafts are resumable. Submission freezes entry until a manager reopens it. An owner/manager approves all submitted counts, including zero-variance counts. Stock remains sellable while a count is open. If on-hand changed after a line was recorded, approval returns 409; reopen/recount/resubmit. Approval cannot reduce on-hand below reserved stock and repeated approval does not apply it twice.

CSV imports accept at most 1000 rows under the exact header `sku,quantity,reorderLevel`. Preview reports row errors without changing stock; commit revalidates and applies all lines in one transaction. Imports initialize previously unstocked SKUs; existing stock requires a movement or count session.

## Settings, tax and onboarding

B2 settings and onboarding require an all-branch Owner/Administrator; branch preferences require a permitted branch. Preferences use revisions to reject stale edits. Low-stock notification settings are stored preferences; notification delivery remains future work. The separate frontend can resume checklist steps through `/api/v1/onboarding`; the checklist does not block catalog/dashboard access.

Tax templates are **illustrative and require review**. No product is automatically assumed taxable at 15%. Existing/new businesses use an explicitly unverified nonregistered baseline to preserve prices. Owners must confirm their actual registration status. A registered policy requires Owner verification and reviewed effective product classifications before checkout can proceed. Zero-rated and exempt are distinct classifications. Inclusive and exclusive pricing are supported; branch price mode requires explicit business permission. Seller registration stays business-wide.

Tax policies, profile rates and product assignments use immutable effective-dated versions. Version POSTs use a unique `requestId`; repeating the same command returns its existing version. Tax rounds HALF_UP per extended line, and all amounts come from the server. New orders snapshot tax rules, net/tax/total, seller review state and receipt branding at reservation creation. Later changes do not reprice orders, payments, retries or refunds. Old orders are marked LEGACY_UNSPECIFIED with snapshot version 0; their historical tax is not inferred.

See [the B2 guide](ORVEXA_B2_IMPLEMENTATION.md) for API examples, permissions, revision handling, onboarding checks and TAJ/accountant validation points. Customs/import charges and landed costs are separate from sales tax and are not added by checkout.

## Checkout, payment confirmation and retries

Cart preview accepts `{"lines":[{"productId":"PRODUCT_UUID","quantity":1}]}` and never reserves stock. Start checkout by POSTing to staff `/api/orders` or public `/api/storefront/{businessId}/orders`:

```json
{"lines":[{"productId":"PRODUCT_UUID","quantity":1}],"requestId":"checkout-unique-id"}
```

Checkout reserves for five minutes. In normal SQLite mode, use POST `/api/v1/checkout/orders/{id}/payments` with `{"provider":"CASH","requestId":"unique"}` only after receiving cash, or provider=MANUAL_TERMINAL to begin an uncertain terminal attempt. MANUAL_TERMINAL holds stock until an Owner/Administrator/Manager records the verified outcome/reference through `/api/v1/checkout/payments/{id}/reconcile`. UNKNOWN/PENDING attempts survive expiry and cannot be cancelled/retried blindly.

In the explicitly enabled demo/test simulator only, the older payment route uses `{"method":"CASH","successful":true,"requestId":"payment-unique-id"}` (or simulated CARD/false). No card/account details are accepted. Success commits stock; failure releases it. Unstarted expired reservations are released by a 30-second sweep and checked at checkout/payment. Uncertain payment holds require verified reconciliation.

Use the same request ID with the same command to retry. Reusing it for another command returns 409. POS and ONLINE share the same conditional balance updates and ledger, so concurrent checkout cannot allocate the same final unit twice.

An online checkout returns a random `accessToken` once. Keep it in the customer context and pass it in `X-Order-Token` for order reads, payment, cancellation, receipts and checkout retries. Only its hash is stored. A request ID is not authorization; a replay without the correct token returns 404 and never reveals an existing order. A client that loses the initial token cannot recover that order publicly; its reservation expires, or staff can cancel it.

Cancellation of a reserved order releases stock. A completed, unfulfilled order is fully restocked on cancellation and receives a simulated refund. Fulfilled orders require an explicit full-order return: `{"resellable":true,"reason":"Unopened","requestId":"return-unique-id"}`. Only resellable returns restore stock. A separate refund records simulated funds without changing inventory. B5 adds approved partial/full RETURN, REFUND and unfulfilled CANCEL workflows through `/api/v1/checkout/orders/{id}/reversals`. Cashiers may request; managers review and complete with verified refund method/reference. Use these for new cash/manual-terminal sales. Historical sale prices/tax/cost determine amounts, and only resellable physical returns or unfulfilled cancellation restore stock.

## Reports, audit and receipts

Reports are tenant scoped. Sales accept inclusive UTC `from=YYYY-MM-DD`, `to=YYYY-MM-DD` and `channel=POS|ONLINE`. They show gross collected revenue, completed refunds and net revenue plus daily/channel/product summaries. Product cost/profit figures use immutable pre-tax sale net and cost snapshots, before refund accounting. Sales include `sales_net_before_tax_cents` and `sales_tax_cents`; tax figures are gross sale-cohort amounts before refund reconciliation and do not constitute a tax return. Refund totals follow the original sale cohort; this is an operational demo, not a general accounting ledger. Business currency cannot change after stock, purchasing or sales history exists.

Employee administration appears in `/api/audit`; quantitative events appear in `/api/inventory/movements`. Order details include lifecycle events and employee, online-customer or system-expiry attribution. Public catalog/order responses omit costs and staff event details. New confirmed sales retain immutable receipts; simulator receipts explicitly say simulated/no real funds, while cash/manual-terminal receipts identify staff confirmation. Text/HTML/PDF downloads and opt-in queued email use the saved payload. Stock CSV export reads every page and escapes spreadsheet-sensitive text.

## Tests and local backup

```powershell
mvn package
python -m unittest discover -s scripts -p "test_*.py" -v
```

Java tests use isolated, randomly named SQLite files under the OS temporary directory, never the local MVP or PostgreSQL database. They exercise actual request transactions, tenant tampering, role/deactivation checks, verified fitment, stock bounds/ledger, CSV preview/commit, count approval/drift, failed/expired payments, cancellation/refunds/returns, immutable prices, online order tokens, simultaneous POS/online checkout, duplicate payment, populated baseline upgrades, demo seeding, CORS and live HTTP/Swagger startup.

Functional MVP verification: **122 Java tests passed**, zero failures/errors/skips. Packaging, V9–V11 live migration and all eight demo login/new-endpoint checks passed. The backend runs in sqlite,demo mode on port 8080; identities, stock and historical order/payment/tax values were preserved. See [B3–B5 verification and limitations](ORVEXA_MVP_B3_B5.md#verification-and-review-boundary) for final results. Stop the running JAR before repackaging on Windows.

Optional Python 3 tooling performs a consistent online SQLite backup, including committed WAL data. It refuses to overwrite an existing destination, validates integrity/foreign keys, and restores to a separate file:

```powershell
python scripts/sqlite_backup.py backup osyntra-mvp.db osyntra-backup.db
python scripts/sqlite_backup.py restore osyntra-backup.db osyntra-restored.db
```

Stop the server before choosing a restored file as its new `SQLITE_DB_PATH`. The scripts never replace an in-use database. Python is needed only for maintenance tooling, not for the Java server/tests. PostgreSQL backups and production disaster recovery remain post-MVP work.

## Local email preview and opt-in SMTP

SMTP is disabled by default. The `sqlite,demo` profile explicitly enables local email preview. After password login, call `GET /api/v1/auth/mail-preview` with your Bearer token from a loopback client. It returns only messages addressed to you or issued by you (including invitations), with their link text. A VERIFY_EMAIL token may use this endpoint. Confirmation links target the separate frontend; until that UI exists, copy the link's `token` value into the corresponding API confirmation body. Local preview is a development capability and must stay disabled for a shared deployment.

To enable SMTP deliberately, set `ORVEXA_SMTP_ENABLED=true`, `ORVEXA_MAIL_FROM`, and the standard Spring mail process environment variables `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, and `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true`. Alternatively put standard lowercase `spring.mail.*` properties in the ignored `.env`. STARTTLS is required, peer identity is checked and network timeouts are bounded. Set `ORVEXA_PUBLIC_BASE_URL` to the trusted frontend HTTPS origin. SMTP delivery is opt-in and has not been tested against an external provider.

Identity links are queued transactionally and delivered after commit, with leases, retries and a five-attempt limit. Delivery is at least once; a transport failure after remote acceptance can produce a duplicate message. Links remain single-use. No external email is sent in the default demo.

B1 creates a private `.local/orvexa-security.key` to encrypt MFA seeds and deliverable email tokens, or uses `ORVEXA_SECURITY_KEY` (Base64 encoding of exactly 32 random bytes). **Back up the original key securely alongside the database backup.** It is required to restore encrypted identity data; startup refuses a missing/mismatched key for an initialized database. Do not regenerate it to fix startup. Never commit the key or real credentials.
