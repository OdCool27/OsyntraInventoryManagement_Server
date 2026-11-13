# Osyntra Inventory Management Server

Java/Spring Boot REST server for a configurable, multi-business inventory MVP. The frontend is developed separately; this repository supplies employee, inventory, POS, storefront and reporting APIs.

Backend implementation follows [the v2 architecture plan](Osyntra_MVP_Architecture_and_Phases_v2.md). Phases 1–5 are implemented for the local SQLite demonstration. [MVP_PROGRESS.md](MVP_PROGRESS.md) records phase evidence, [DEMO.md](DEMO.md) provides a walkthrough, and [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) records policies and migration decisions. This remains a local demonstration with simulated payments. [POST_MVP_REVIEW.md](POST_MVP_REVIEW.md) defines the review before a real deployment.

## Run

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
- Basic login check: `GET /api/auth/me`
- Default frontend origin allowed by CORS: `http://localhost:5173`

Employee authentication uses HTTP Basic and BCrypt. Configure `OSYNTRA_OPERATOR_EMAIL` / `OSYNTRA_OPERATOR_PASSWORD` for the first platform operator; the existing `SENTRA_ADMIN_EMAIL` / `SENTRA_ADMIN_PASSWORD` variables remain fallback names. An operator provisions tenants and their first owners; it cannot access tenant catalog, inventory, sales or employee records. There are no default login passwords, public employee signup or email verification.

Bootstrap creates an operator only if none exists and the configured email is unused. It never converts an existing tenant owner to an operator or resets a password. Existing baseline employees remain owners/staff of the migrated “Existing business.” If its old bootstrap email is already in use, use a distinct `OSYNTRA_OPERATOR_EMAIL` for operator provisioning.

## Optional two-business demo

Choose a local demo password of 12–72 characters, at most 72 UTF-8 bytes. Set `OSYNTRA_DEMO_PASSWORD` in your local environment, then:

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=sqlite,demo"
```

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

Each employee has one business membership for this MVP. Business context comes from the authenticated employee, never an arbitrary request header or body field. Repositories filter by membership and composite foreign keys reject cross-business relationships. Cross-tenant resource IDs return 404; unauthorized actions return 403.

| Role | Current responsibilities |
| --- | --- |
| Platform operator | List business metadata; provision a business, location and first owner |
| OWNER | Business settings, staff, catalog, stock, counts, checkout, fulfillment, refunds/returns and reports |
| MANAGER | Operational staff creation/editing, catalog, stock, count approval, checkout, fulfillment, refunds/returns and reports |
| CLERK | Catalog/stock reads, draft count entry/submission, cart preview, order reads and fulfillment |
| CASHIER | Catalog/stock reads, cart preview, POS checkout, simulated payment and eligible cancellation |

Managers cannot create or edit owners/managers. A business must retain an active owner. Employee deactivation takes effect on subsequent authenticated requests. Platform operators have no routine tenant-data access.

## Endpoint inventory

Lists use `limit` (default 50, max 200) and `offset` where exposed. UUIDs identify products, fitments, count/import sessions and orders; business/location/employee IDs are numeric.

| Area | Endpoints |
| --- | --- |
| Identity | GET /api/auth/me |
| Provisioning | GET/POST /api/platform/businesses |
| Business | GET/PUT /api/business; GET/POST /api/locations |
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

Stock is per location and product. Supply `locationId` to select a location; omission selects the business's first active location. Unknown or cross-tenant locations return 404. Available quantity is on-hand minus reserved.

Unknown JSON fields fail validation. Ordinary validation errors return 400, unauthenticated requests 401, forbidden actions 403, missing/cross-tenant resources 404, and duplicate/conflicting state 409. Creation usually returns 201. A duplicate idempotent command returns its existing state without applying stock changes again.

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

## Simulated checkout and retries

Cart preview accepts `{"lines":[{"productId":"PRODUCT_UUID","quantity":1}]}` and never reserves stock. Start checkout by POSTing to staff `/api/orders` or public `/api/storefront/{businessId}/orders`:

```json
{"lines":[{"productId":"PRODUCT_UUID","quantity":1}],"requestId":"checkout-unique-id"}
```

Checkout reserves for five minutes. Payment uses only `{"method":"CASH","successful":true,"requestId":"payment-unique-id"}` (or simulated CARD/false). No card/account details are accepted. Success commits stock; failure releases it. Expired reservations are released by a 30-second sweep and also checked at checkout/payment.

Use the same request ID with the same command to retry. Reusing it for another command returns 409. POS and ONLINE share the same conditional balance updates and ledger, so concurrent checkout cannot allocate the same final unit twice.

An online checkout returns a random `accessToken` once. Keep it in the customer context and pass it in `X-Order-Token` for order reads, payment, cancellation, receipts and checkout retries. Only its hash is stored. A request ID is not authorization; a replay without the correct token returns 404 and never reveals an existing order. A client that loses the initial token cannot recover that order publicly; its reservation expires, or staff can cancel it.

Cancellation of a reserved order releases stock. A completed, unfulfilled order is fully restocked on cancellation and receives a simulated refund. Fulfilled orders require an explicit full-order return: `{"resellable":true,"reason":"Unopened","requestId":"return-unique-id"}`. Only resellable returns restore stock. A separate refund records simulated funds without changing inventory. Partial returns/refunds are outside this MVP.

## Reports, audit and receipts

Reports are tenant scoped. Sales accept inclusive UTC `from=YYYY-MM-DD`, `to=YYYY-MM-DD` and `channel=POS|ONLINE`. They show gross simulated revenue, full refunds and net revenue plus daily/channel/product summaries. Product cost/profit figures are gross estimates from immutable snapshots, before refund accounting. Refund totals follow the original sale cohort; this is an operational demo, not a general accounting ledger. Business currency cannot change after its first order.

Employee administration appears in `/api/audit`; quantitative events appear in `/api/inventory/movements`. Order details include lifecycle events and employee, online-customer or system-expiry attribution. Public catalog/order responses omit costs and staff event details. Text receipts explicitly say simulated/no real funds and are downloadable/printable. Stock CSV export reads every page and escapes spreadsheet-sensitive text.

## Tests and local backup

```powershell
mvn clean package
python -m unittest discover -s scripts -p "test_*.py" -v
```

Java tests use isolated, randomly named SQLite files under the OS temporary directory, never the local MVP or PostgreSQL database. They exercise actual request transactions, tenant tampering, role/deactivation checks, verified fitment, stock bounds/ledger, CSV preview/commit, count approval/drift, failed/expired payments, cancellation/refunds/returns, immutable prices, online order tokens, simultaneous POS/online checkout, duplicate payment, populated baseline upgrades, demo seeding, CORS and live HTTP/Swagger startup.

Verified on 2026-11-13: **32 Java tests and two Python backup tests passed**. The clean package build produced the executable JAR. A separate seeded-database backup/restore drill also passed.

Optional Python 3 tooling performs a consistent online SQLite backup, including committed WAL data. It refuses to overwrite an existing destination, validates integrity/foreign keys, and restores to a separate file:

```powershell
python scripts/sqlite_backup.py backup osyntra-mvp.db osyntra-backup.db
python scripts/sqlite_backup.py restore osyntra-backup.db osyntra-restored.db
```

Stop the server before choosing a restored file as its new `SQLITE_DB_PATH`. The scripts never replace an in-use database. Python is needed only for maintenance tooling, not for the Java server/tests. PostgreSQL backups and production disaster recovery remain post-MVP work.
