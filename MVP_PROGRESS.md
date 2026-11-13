# Osyntra Server MVP Progress

## Current status

Backend phases 1–5 of [the architecture plan](Osyntra_MVP_Architecture_and_Phases_v2.md) are implemented for the local SQLite demonstration. All frontend implementation belongs to the separate frontend project. Real deployment, PostgreSQL migration, offline sync, paid billing, AI and live payments remain deferred.

## Phase acceptance and evidence

| Phase | Implemented server behavior | Evidence |
| --- | --- | --- |
| 0 — assessment | Reusable JDBC/security baseline retained; obsolete single-business claims/scaffolding removed; endpoints and decisions documented | README, architecture decisions and original cleanup inventory |
| 1 — foundation/access | Businesses and locations, single-business employee membership, operator provisioning without tenant-data privileges, managed employees, deactivation, last-owner protection, two isolated opt-in demo tenants | API tenant/role/tampering tests, demo seed test, live bootstrap, fresh and populated schema migration tests |
| 2 — catalog/fitment | Generic product/category CRUD, product archive, per-tenant SKU/barcode lookup, unit/cost/threshold metadata, shared verified vehicle configurations with year ranges and optional engine/trim | Catalog, category, fitment, scanner-code and cross-tenant-link tests |
| 3 — inventory/counting | Per-location balances, conditional updates, reason-coded ledger, opening stock, CSV template/preview/atomic commit, resumable count sessions, submission/reopen/approval, drift protection, low-stock alert API | Ledger/bounds/location/import/count/reserved-stock tests |
| 4 — shared commerce | Non-reserving cart preview; POS/ONLINE reservations, five-minute expiry, simulated payment, cancellation, separate refunds, resellable returns, immutable line snapshots, order history and receipts, idempotency and private online capabilities | Commerce state/rollback/token/retry tests plus simultaneous cross-channel last-unit checkout and duplicate-payment test |
| 5 — reporting/demo/hardening | Stock/value reports, sales by date/channel/product, simulated gross/refund/net totals, audit and order events, paginated CSV export, repeatable automotive/grocery fixtures, demo walkthrough, local consistent backup/restore utility | Reporting/receipt/CORS tests, demo seed repeatability, backup/WAL/restore tests and walkthrough |
| 6 — post-MVP review | Review agenda and deployment gates documented | POST_MVP_REVIEW.md; formal review not yet conducted |
| 7 — future capabilities | Deferred by the approved plan | No real payments, AI, cloud hosting, subscriptions or offline synchronization added |

## Policies used for this implementation

The proposed MVP defaults were used while the implementation proceeded: platform operators provision metadata/accounts without routine tenant data access; five-minute checkout reservations; approval for all stock counts; stock stays sellable during counting; whole-unit quantities; one initial demo location per tenant. Multiple basic locations are supported.

Unfulfilled paid cancellations restore all stock and record a full simulated refund. Fulfilled orders need an explicit full-order return; only resellable returns restore stock. Refund-only commands leave inventory unchanged. These policies are recorded in ARCHITECTURE_DECISIONS.md and can be revised as explicit product changes.

## Database preservation

- The local .env and existing PostgreSQL credentials are preserved.
- SQLite V1 remains unchanged; V2–V6 incrementally introduce tenancy, catalog fitment, stock ledger/counts, simulated commerce and repeatable demo/scanner-code support.
- Populated V1 data migrates to a single “Existing business” and “Main” location. Accounts, hashes, quantities, reserved balances and audit records are retained.
- Historical PostgreSQL V5–V7 contents remain unchanged and V8 retains the prior cleanup's role mapping. The new SQLite schema has not been ported to PostgreSQL.
- PostgreSQL configuration and Compose volume remain available. A startup guard prevents running new MVP services against that incomplete database schema before the post-MVP migration review.

## Verification

The earlier inventory baseline had 20 passing tests. On 2026-11-13, `mvn -q clean package` passed **32 Java tests**, zero failures/errors/skips, and generated the executable JAR. Coverage now includes full tenant/catalog/count/commerce scenarios, populated database upgrades, demo fixtures and deployment-profile guarding.

Both Python backup tests pass: consistent WAL snapshots, restore, integrity checks and overwrite refusal. An additional seeded-database restore drill recovered two demo tenants, two products and 18 stock units, with selected account/history row counts matching the snapshot. Environment and historical PostgreSQL migration preservation checks pass, as does git diff --check.

Two issues found during verification were resolved: SQLite's populated-table foreign-key/default restriction required rebuilding/copying employees/audit, and request rollback tests needed actual request transactions instead of a surrounding test transaction.

## Handoff

- [README.md](README.md): startup, configuration, endpoint inventory, role policies and request examples.
- [DEMO.md](DEMO.md): local end-to-end demonstration.
- [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md): scope, business rules, data preservation and test evidence.
- [POST_MVP_REVIEW.md](POST_MVP_REVIEW.md): formal security/deployment/PostgreSQL review agenda.
- ProductService and ProductRepository: tenant-scoped catalog transactions.
- StockLedger: all stock/reservation mutations and quantitative audit.
- StockCountService / OpeningImportService: controlled onboarding and physical counts.
- CommerceService: shared channel lifecycle, retry safety and simulated financial state.
- scripts/sqlite_backup.py: optional local backup/restore tooling.

Next work outside this server MVP: build the separate frontend against these APIs, perform the formal post-MVP review, then authorize any real-business pilot separately.
