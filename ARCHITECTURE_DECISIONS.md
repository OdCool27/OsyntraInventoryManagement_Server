# Osyntra Server Architecture Decisions

## Orvexa B3–B5 — functional MVP completion

The user authorized B3–B5 together. Existing catalogue/ledger/commerce services are extended rather than replaced. Variant parents cannot be stocked/sold; variants have immutable tenant-local identity. Decimal conversion units resolve exactly to integral base granularity, preserving existing balances. Product thresholds are global; prior location levels are archived. Catalogue imports are bounded atomic preview/commit jobs with safe XLSX parsing and rollback/retry.

Location acquisition value uses six decimal currency places, weighted average for outgoing stock, historical sale-cost snapshots and original-value returns. Legacy valuation/opening costs remain labeled estimates. Optional batches are configured before inventory history, allocated by expiry, rechecked before sale and returned into original batches. Transfers retain dispatched acquisition value in transit and reconcile partial receiving.

Cash and externally confirmed terminal bookkeeping use durable payment attempts/records. UNKNOWN/PENDING stock holds do not expire or release on cancellation; verified reconciliation is required. There is no bank SDK/webhook integration. Simulator routes require explicit development/demo/test enablement. Approved partial reversals derive quantity/amount/tax/cost budgets from historical sales, with no double restock/refund. Confirmed receipts are saved once and reused for download/email; delivery remains the B1 opt-in outbox.

SQLite V9–V11 preserve historical identities/quantities/financial snapshots and old checkout retry fingerprints. [ORVEXA_MVP_B3_B5.md](ORVEXA_MVP_B3_B5.md) contains contracts, arithmetic, limits, verification and manual integration prerequisites. This completes the authorized functional server MVP; frontend, production readiness and B6–B9 remain separate.

## Orvexa B2 — preferences, tax and progressive onboarding

Business/branch preferences use revision checks, with null branch values inheriting centrally. Only explicitly enabled price-mode overrides apply; registration remains business-wide. Tax policies, profile rates and per-product assignments are effective-dated immutable histories, selected by server time. Registered collection requires owner-reviewed configuration. Nonregistered compatibility defaults are explicitly unverified and do not determine legal eligibility.

Tax uses decimal arithmetic and HALF_UP rounding per extended line. Checkout snapshots net/tax/gross, configuration IDs/verification state and receipt branding once; payments, retries and full refunds retain those amounts. Pre-B2 orders keep their totals and receive LEGACY_UNSPECIFIED/snapshot version 0, without invented historical tax. Product profit estimates exclude collected sales tax. Import/landed costs are not sales-tax inputs.

Onboarding stores independently resumable checklist steps/revisions/notes, with current server readiness and stale-completion signals. It does not block the dashboard. SQLite V8 is incremental; existing environment, key, identity and stock/history remain preserved. [ORVEXA_B2_IMPLEMENTATION.md](ORVEXA_B2_IMPLEMENTATION.md) supplies contracts, verification evidence, rounding/compatibility policies and professional validation points. B3–B5 were subsequently implemented as described above.

## Orvexa B1 — identity and branch authority

B1 supersedes the earlier single-business/Basic model. Global users have multiple memberships, one of five fixed tenant roles, and explicit or all-branch grants. Active business comes from a validated opaque Bearer session. Branch access is enforced across existing stock/count/import/order/report services. Platform staff are separate from tenant memberships; there is no tenant support bypass.

Email verification/reset and invitations use expiring hashed single-use tokens. Their encrypted delivery payloads are enqueued transactionally and delivered outside business transactions, with durable leases and bounded retries. The user chose loopback development preview with opt-in SMTP; no external email was sent. MFA is mandatory for Owners/platform staff and optional for others. Seeds and email bodies use a private retained encryption key; restore requires both original key and database. Password resets retain MFA, and privilege changes revoke sessions.

SQLite V7 backfills memberships/platform staff without changing account IDs/password hashes or old stock/history. Legacy emails require verification except explicit demo fixtures. Old employee records remain historical; authority comes from the new tables. Existing .env/PostgreSQL settings remain intact. [ORVEXA_B1_IMPLEMENTATION.md](ORVEXA_B1_IMPLEMENTATION.md) describes API compatibility changes, migration, evidence and manual integration limits. B2 was subsequently authorized and implemented as described above.

The sections below record the earlier B0/MVP decisions historically; B1 takes precedence where they describe Basic login, one membership or deferred identity features.

## Orvexa B0 — audit and foundation

The new Orvexa implementation prompts establish Orvexa as the product and Osyntra as the company, with one phase per review cycle. The user explicitly reaffirmed SQLite for the MVP/tests; PostgreSQL remains an assessed local Docker setup, not a switch in this phase. Docker's Linux engine was unavailable during inspection and no PostgreSQL connection or migration was attempted. Its existing guard/configuration/history remain intact.

B0 preserves existing service boundaries, API paths, Java package/artifact identity, database records and credentials. Product display metadata, logging identity and receipt heading now say Orvexa by Osyntra. The repository's broader gaps, current entity relationship map, staged B1–B9 extension plan and manual integration prerequisites are in ORVEXA_B0_ASSESSMENT.md. Earlier demo completion does not establish compliance with those broader requirements.

Essential foundation changes: GET-only public health/liveness/readiness with hidden details; database-backed readiness; validated UUID request correlation before security; MDC restoration and CORS access to X-Request-ID; shared application/problem+json codes/requestId for filter/controller failures; safe SQL/internal diagnostics; and explicit UTC calendar reads for typed JDBC timestamps. Existing raw map timestamp strings remain UTC by convention pending a versioned response-contract migration. No data migration was added or modified.

The README now directs developers to their ignored local demo-password configuration or a secure prompt instead of publishing a literal credential. Existing demo/operator/PostgreSQL credentials and passwords remain unchanged.

Verification: **41 Java tests passed**, including eight focused foundation tests and a UTC regression test. Packaging initially hit Windows' lock on the running JAR; only the confirmed project listener was restarted, then packaging succeeded. Live health/probes, correlation, OpenAPI identity, 401 errors and all eight existing demo logins passed. The backend runs in sqlite,demo mode on port 8080. B0 stops for review; B1 membership/branch/identity work remains next.

## Scope and chronology

The [v2 architecture plan](Osyntra_MVP_Architecture_and_Phases_v2.md) defines the target. Repository-specific user instructions take precedence: this project contains the server only; the frontend is separate; SQLite is for the local MVP and testing; environment variables and PostgreSQL credentials/configuration must be preserved. No working SMTP configuration was found in the earlier audit. B1 now supplies opt-in SMTP and local preview, with external provider validation still manual.

On 2026-11-12, Phase 0 cleanup removed unused Product/CarPart inheritance, customer/cart objects, empty user DTOs, invalid ignored migration drafts, duplicate configuration and the untested Java 27 Docker template. Applied PostgreSQL V5–V7 contents were preserved under their own migration location, and the old narrower “MVP complete” claim was removed. The old single-vehicle fields were removed before their replacement.

On 2026-11-12/13, the user authorized proceeding through the MVP phases. Server phases 1–5 now implement tenancy, verified relational fitment, location inventory/counts, shared simulated commerce and reporting/demo hardening. Frontend tasks in the source plan apply to the separate frontend project. Phase 6 remains a formal post-MVP review; Phase 7 capabilities remain deferred.

## Foundation and database preservation

Java 24, Spring Boot 4.1.1, Spring MVC, Spring Security, JDBC/JdbcClient and Flyway remain the foundation. Controllers validate HTTP input, services enforce business rules/transactions, and repositories bind SQL parameters.

SQLite uses WAL, foreign-key enforcement, a 10-second busy timeout and IMMEDIATE transactions. Stock/reservation mutations use conditional updates and a quantitative ledger in the same transaction. A code-19 JDBC exception translator maps SQLite constraint failures to HTTP 409. References: [Flyway SQLite support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sqlite), [Xerial connection configuration](https://github.com/xerial/sqlite-jdbc/blob/master/src/main/java/org/sqlite/SQLiteConfig.java).

SQLite V1 remains the baseline. V2–V6 incrementally introduce ownership/location constraints, catalog/fitment, stock ledger/count/import sessions, simulated commerce and demo/code-lookup support. Populated V1 data migrates to one explicit “Existing business” and “Main” location; it is not spread across demo tenants. Employees, hashes, stock/reserved balances and audit rows retain their identifiers/values. Existing reserved balances are preserved; legacy reservations without matching order records need review before any real-data migration.

The upgrade test caught SQLite's restriction on adding a foreign-key column with a non-null default to populated tables. V2 now rebuilds/copies employees and audit tables within the migration instead. Fresh-schema and populated-schema upgrades, including a repeat migration, are tested.

The local .env and PostgreSQL connection bindings/Compose volume remain intact. Historical PostgreSQL V5–V7 contents are unchanged; V8 retains the cleanup's role mapping. The new tenant/commerce schema currently targets SQLite. A guard rejects the PostgreSQL profile before database initialization until that schema is ported and the post-MVP database review/tests pass. SQLite test results are not PostgreSQL evidence.

## Tenancy and employee access

Each employee has one business membership in the MVP. Request business IDs/headers never select an employee's business context. Tenant queries scope reads/writes and composite foreign keys reject cross-tenant category, location, product and fitment links. Missing and cross-tenant resource IDs both fail with 404.

Platform operators can list business metadata and provision a tenant/location/first owner. They cannot routinely access catalog, inventory, staff, reports or sales data. No support impersonation bypass was added. Tenant roles are OWNER, MANAGER, CLERK and CASHIER; platform operator authority is separate from those roles.

Owners/managers manage operational staff; managers cannot create/edit owners or managers. The last active owner cannot be deactivated/demoted. Subsequent requests reject deactivated/suspended employees. HTTP Basic with BCrypt is retained for the local demo, with CORS limited to configured exact frontend origins. Broader authentication/abuse/recovery hardening belongs to the formal review.

Preferred OSYNTRA_OPERATOR_EMAIL/PASSWORD bootstrap a platform operator, with existing SENTRA_ADMIN variables retained as fallbacks. Bootstrap never resets/elevates an existing account. Earlier tenant-owner accounts remain in the migrated tenant; a distinct operator email can provision a separate operator.

## Catalog and verified compatibility

Products are generic; automotive compatibility uses vehicle_fitments and product_fitments. A configuration has a make/model/year range and optional engine/trim, a verification source, verifying employee and timestamp. Creating a configuration requires verified=true and a source. Shared configurations and multiple links per product provide the required many-to-many model.

Search uses recorded structured data. Engine/trim-specific configurations are excluded when the query omits that detail. A verified broad configuration with no engine/trim restriction may match a specific query. No AI claims or guessed compatibility are generated.

SKUs are exact, trimmed and unique per business. Optional barcodes are exact, trimmed and unique per business. Triggers prevent another product's SKU from colliding with a barcode, making scan/type lookup unambiguous. Packaging variants need distinct SKUs. Products archive rather than delete; referenced categories cannot be deleted. Cost, sale price, unit and threshold metadata support inventory/reporting.

## Stock, counts and onboarding

Balances are keyed by location and product, with trustworthy business associations. All on-hand/reserved changes—including openings, corrections, damage, receipts, count approvals, checkout, expiry and reversals—pass through StockLedger. Conditional predicates protect zero/reserved/integer bounds. Entries record both deltas, resulting balances, reason, notes, reference and employee/online/system context.

Quantities are whole units. Each demo tenant starts with one location; basic additional locations are supported. A product-specific threshold can override the business default when initializing stock.

Manual opening and CSV commit initialize previously unstocked balances. CSV preview records row errors without quantity changes; commit revalidates and applies every line atomically. The small CSV reader handles quoted fields, escaped quotes and CRLF. Imports support at most 1000 rows.

Dedicated count sessions are saved/resumed as drafts, then submitted and approved by owner/manager. All counts require approval; no threshold bypass is implemented. Stock remains sellable. Approval rejects changed on-hand snapshots or counts below reservations; staff reopen/recount/resubmit. Approval is idempotent. The previous unreasoned add/remove/raw absolute-count APIs were replaced by reason-coded movements and approved sessions.

## Shared commerce and simulated financial state

Cart preview does not allocate inventory. Checkout atomically reserves stock for five minutes. POS and ONLINE use the same order service and stock ledger. Expiry is swept every 30 seconds and checked at checkout/payment. Successful simulated payment converts reserved stock into a sale; unsuccessful payment releases it. Order lines capture immutable SKU/name/quantity/price/cost snapshots in integer minor units.

Request IDs are unique within a business and stored with a command fingerprint. Exact retries return the existing entity state; reusing an ID for another command fails 409. State transitions and receipts share the mutation transaction.

Anonymous online orders use a random 256-bit capability returned on creation, with only its hash stored. Subsequent public reads/actions/checkout retries require X-Order-Token. A request ID is never authorization. A lost initial capability cannot be recovered through the public API; the reservation expires or staff can cancel it. Public responses omit costs and staff event details.

Unfulfilled cancellation releases reserved stock or fully restores completed stock and records a simulated refund. Fulfilled orders require explicit full-order returns; only resellable returns restock. Refund-only commands record simulated financial reversal without changing inventory. Partial returns/refunds are deferred. No bank/card details or real funds are handled.

These defaults were proposed to the user and used while implementation proceeded; they remain documented product policies that can be revised explicitly.

## Reporting, demo and maintenance

Operational reports include stock/value estimates, low/out-of-stock, sales by UTC date/channel/product, simulated gross revenue, refunds and net revenue. Product cost/profit figures are gross estimates from sale snapshots; refunds are associated with the original sales cohort. Business currency is fixed after its first order; this is a single-currency MVP, not a tax/accounting engine.

Employee administration is in tenant audit history; quantities are in stock movements; order details carry lifecycle actor/context events. Downloadable text receipts explicitly identify simulated payments. Stock CSV export pages all rows and escapes formula-sensitive text.

The opt-in demo profile requires an explicit local password and seeds two isolated tenants, four employee roles each, separate catalog/stock and a structured Honda Civic fixture. A marker prevents duplicate seeding and stock/password resets. Fixture compatibility is not a real manufacturer warranty.

Optional Python 3 tooling uses SQLite's backup API to include committed WAL data and restore to a new destination; it refuses overwrite and checks integrity/foreign keys. Python is not required by the Java server/test suite.

## Verification — 2026-11-13

- `mvn -q clean package`: **32 Java tests passed**, zero failures/errors/skips; executable JAR generated.
- Catalog, category, fitment, tenant tampering, staff lifecycle, last-owner, CORS, ledger bounds/reconciliation, location isolation, CSV, count drift/approval and reserved-stock tests pass.
- Commerce tests cover cart preview, atomic rollback, snapshots, idempotency, payment failure/expiry, cancellation, refund-only, returns, customer capabilities, reports and receipts.
- Simultaneous POS/ONLINE last-unit checkout produces one reservation; concurrent duplicate payment produces one payment/sale.
- Fresh migrations, populated V1 upgrade, repeat migration, PostgreSQL guard, repeatable two-tenant seeds and real HTTP bootstrap/Swagger startup pass.
- Two Python backup tests pass, including WAL activity, restore and overwrite refusal.
- An additional backup/restore drill against the seeded test database restored two tenants, two products and 18 units, with selected account/catalog/history row counts matching the snapshot and integrity/FK checks passing.
- Local .env hash and historical PostgreSQL V5–V7 contents are unchanged.
- `git diff --check` passes.

The request tests use actual request transactions. A previous outer test transaction made rolled-back request changes remain visible inside the test; removing that wrapper and resetting only the dedicated generated test database fixed the test model and verifies real commit/rollback boundaries.

The old 20-test inventory baseline result is historical. No PostgreSQL concurrency or production-security readiness is inferred from the new SQLite suite. [POST_MVP_REVIEW.md](POST_MVP_REVIEW.md) remains the deployment gate.
