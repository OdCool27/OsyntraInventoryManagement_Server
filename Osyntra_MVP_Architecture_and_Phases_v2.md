# Osyntra — MVP Architecture and Phased Implementation Plan

**Status:** Working design draft — five product decisions confirmed on 2026-11-12; remaining decisions are listed in Section 8.  
**Product:** Configurable, multi-business inventory management SaaS, initially demonstrated using an automotive parts store.  
**Delivery philosophy:** Reuse and evaluate the existing Spring Boot application first; implement business-relevant functionality incrementally; maintain readable code comments, README, and a change/decision log.

## 1. Product objective and MVP definition

Osyntra will demonstrate how a retailer can register and organize products, enter an opening stock count, receive and adjust inventory, search compatible parts, record in-store sales, and accept simulated online orders while sharing a single authoritative inventory balance. An owner or manager should be able to see meaningful audit trails and basic reports.

The first deployment is a **local demonstration** that requires no paid infrastructure. It is **not** yet production-ready SaaS, payment processing, offline resilience, or a deployed public e-commerce site.

### MVP demonstration scenario

1. A platform operator creates an automotive business tenant and its first location.
2. An authorized employee logs in and adds products (or imports a CSV), including SKU, barcode (where available), vehicle fitment, cost and sale price.
3. Opening inventory is counted and entered; discrepancies and subsequent adjustments are reason-coded and attributed to users.
4. A customer searches for an air filter compatible with a 2009 Honda Civic. Results use **verified structured fitment data**, not an AI guess.
5. A cashier adds an item to a POS-like checkout and completes a **simulated** payment.
6. An online customer attempts to order the last available item at the same time; transactional inventory allocation prevents two successful commitments of the last unit.
7. Managers view sales, low-stock flags, inventory adjustments, and an audit trail.
8. Order cancellations return eligible stock to availability; refunds are simulated and recorded independently of inventory state.

## 2. Decisions made or provisionally recommended

| Topic | MVP decision | Notes |
|---|---|---|
| Backend | Java + Spring Boot modular monolith | Single application, modular internal domain boundaries |
| Database | SQLite for local MVP | Access via Spring JDBC; do not assume PostgreSQL-specific SQL or row locks are available |
| Later database | PostgreSQL | Migrate with Flyway; test database-specific behavior; do not advertise SQLite concurrency tests as proof of PostgreSQL behavior |
| Frontend | Minimal React + TypeScript demo | Include staff dashboard, basic POS simulation, simple storefront (Option B) |
| Authentication | Real employee login and owner/manager-managed accounts | Secure password hashing, server-side authorization and tenant isolation; no SMTP verification |
| SaaS model | Two demonstrable, isolated business tenants | Initially platform-managed tenant creation, not public signup |
| Catalog | Generic products with relational vehicle compatibility records | One product may fit multiple make/model/year/engine configurations; no unverified AI claims |
| Sales | In-store and online simulated payment paths | No bank/card details stored or transmitted |
| Notifications | In-app low-stock alerts only | NO SMTP, email delivery, fake mail server, or simulated outbound emails |
| AI | Not required for MVP | Start with deterministic compatibility search; future AI assistant can query validated catalog data |
| Offline sync | Deferred | Design event IDs and clear inventory transactions for future extension |
| Cloud deployment and subscriptions | Deferred | No hosting, real billing, subscription payments, or domain purchase |
| Reports | Basic operational and revenue reports | Demo revenue from completed simulated sales, distinguish from real funds |
| Code quality | Readable, appropriately commented methods; integration tests | Document nontrivial business logic and tradeoffs |

## 3. Domain boundaries

- **Platform/tenancy:** Businesses, locations, tenant membership, provisioning.
- **Identity/access:** Employee account, role, permissions, business context.
- **Catalog:** Products, categories, SKUs, optional barcode identifiers, product images/URLs (optional), category-specific attributes, vehicle fitment.
- **Inventory:** Stock balance per location and SKU, movement ledger, inventory count/adjustment, minimum thresholds, reservation and release.
- **Commerce:** Orders, order lines, channel (`POS`, `ONLINE`), statuses, fulfillment, cancellation, simulated payment records.
- **Reporting:** Sales by date/channel/product, stock levels, low-stock list, adjustments, inventory value/cost estimate.
- **Audit:** Actor, action, business, entity, timestamp, before/after or quantitative delta, reason and notes.

### Data principles

- All tenant-owned tables must have a trustworthy tenant association; use authorization checks in services/repositories and test cross-tenant ID tampering.
- Assign stable internal IDs and unique per-tenant SKUs; barcodes may be missing, shared across packaging variants, or require normalization and explicit uniqueness policy.
- Stock is kept per **location and SKU/variant**, not globally on the product master record.
- `available = on_hand - reserved` (subject to any future explicit allocation model); maintain a movement ledger and transactionally update balances.
- Price and cost at time of sale should be captured as immutable order-line snapshots.
- Opening counts, receipts, returns, damage and corrections have distinct movement types and reasons.
- Employee and customer contexts must not bypass server-side permissions.
- Avoid direct frontend database connections; React calls Spring Boot endpoints only.

### Reservation and concurrent transaction rules

- Do not permanently reserve stock merely by adding an item to a cart.
- Define a short reservation lifecycle for checkout; release on cancellation/failure/expiry.
- Prevent negative available inventory under concurrent requests via atomic transactions and conditional updates.
- **SQLite caveat:** SQLite serializes writers and does not support PostgreSQL `SELECT ... FOR UPDATE`. Use SQLite-compatible conditional stock updates, short transactions, WAL where useful, and database-specific concurrency tests. Later rerun transaction tests against PostgreSQL.
- Payment and inventory are separate state transitions; no real card transactions in MVP.
- Use unique request IDs to make sale completion, cancellation and other retried commands idempotent.
- Keep a basic audit event or outbox-ready event structure; do not build a distributed event bus for the MVP.

## 4. MVP scope (must deliver)

### Operator/business setup
- Create at least two demo business tenants (for example, automotive and grocery), with isolated products, staff and inventory; primarily showcase the automotive tenant.
- Add employees using owner/manager-facing screens and assign fixed roles (`OWNER`, `MANAGER`, `CLERK`, `CASHIER`); each action enforces permission checks.
- Business-specific configuration: currency (default JMD), default stock threshold, categories, movement reasons.

### Products and inventory
- Product CRUD/archive, category, SKU, barcode lookup, unit of measure, cost, selling price, min-stock threshold.
- Automotive: relational vehicle compatibility records supporting make, model, year range and optionally engine/trim **only if recorded and verified**, including a many-to-many product/vehicle-fitment link; avoid false compatibility promises.
- Opening stock import: downloadable CSV template, validated import preview/errors, controlled commit; manual entry supported.
- Dedicated stock-count sessions: start a session, scan/type SKU or barcode, record counted quantities, save and resume draft sessions, review differences, and submit for an authorized approval/commit. Scanner can operate as keyboard input; no special hardware SDK required.
- Record stock receipts, damage, adjustments, cycle counts with actor/reason/notes.
- Stock levels: on-hand, reserved, available; prevent unauthorized manual edits to raw balances.
- Low-stock indicators and in-app alerts.

### Commerce simulation
- POS-style cart with barcode/SKU lookup; record sale with **simulated cash/card payment outcome**.
- Simple public storefront read/search and checkout with simulated payment.
- Atomic reservation/commit of stock across channels; cancellations/releases and simulated refunds.
- Order history and receipt-like downloadable/printable view (no real fiscal receipt promises).

### Reporting and auditing
- Stock and low-stock reports; sales by date/channel/product, gross revenue, basic cost/profit estimate where costs exist.
- Adjustment and stock movement history; filtered audit activity; export CSV if straightforward.
- Demonstration tests for insufficient stock, simultaneous purchase, unauthorized edits, cross-tenant isolation, duplicate requests.

## 5. Explicitly deferred

- Real bank terminals, payment processors, live credit-card details, PCI-scoped integrations.
- Any SMTP integration or simulation, outbound email infrastructure, deliverability optimization.
- AI/LLM chat agent, embeddings/vector DB, speculative compatibility recommendations.
- Local offline database nodes, two-way synchronization, conflict reconciliation.
- Paid SaaS subscriptions/billing, open self-service sign-up, customer-facing domain/hosting.
- Advanced forecasting, automated supplier purchase orders, multi-currency tax engine, complex accounting integration.
- Barcode label printer integrations (scanners using keyboard input can be demonstrated).
- High-availability/failover architecture and production disaster recovery; do include a simple local SQLite backup/restore demonstration if time permits.

## 6. Implementation phases and acceptance criteria

### Phase 0 — Existing codebase assessment and specification freeze
**Implement:** Inventory the existing Spring Boot modules, database/migrations, endpoints and tests; identify reusable code; map current functionality to target domain; note incompatible assumptions such as automotive-only product models or single-business access. Record architecture decisions and outstanding questions.

**Deliverables:** `README.md` current-state section, architecture decisions, endpoint inventory, baseline test results, prioritized change list.

**Exit criteria:** We can explain what already works and what will be refactored, without rewriting unrelated features.

### Phase 1 — Project foundation, SQLite, tenancy, and employee access
**Implement:** Spring Boot configuration for a local SQLite database, migrations appropriate to SQLite, module boundaries, business/location ownership, password-based employee authentication, owner/manager-managed employee accounts, tenant-scoped authorization, role checks and error responses. Minimal React login/navigation. Seed two isolated demo tenants.

**Exit criteria:** Users can access only their tenant and permitted APIs; migrations reproducibly initialize an empty SQLite database; cross-tenant access tests fail closed.

### Phase 2 — Generic catalog and automotive fitment
**Implement:** Product/category CRUD, SKU and barcode lookup, sell price/cost and metadata, relational many-to-many vehicle compatibility records, searchable/filterable catalog, a simple catalog UI and demo fixtures.

**Exit criteria:** A user can find a product using SKU/barcode or verified vehicle criteria and can safely archive it without destroying sales history.

### Phase 3 — Inventory ledger, onboarding counts, and stock alerts
**Implement:** Stock per location, movement ledger, receipts, reason-coded adjustments and damages, controlled opening stock, CSV import with validation, resumable barcode-assisted counting sessions, review and approval of count variances, threshold rules, in-app alerts, inventory views.

**Exit criteria:** Every quantity change has a reason and an actor or attributable system process; balances reconcile with movements; negative stock cannot be created through normal operations.

### Phase 4 — Shared POS and online order engine
**Implement:** Cart/checkout APIs, order lifecycle, reservations, simulated payment outcomes, cancellation, restock on eligible reversal, POS scan interface and simple storefront. Deliver Option B: a unified commerce demonstration, with intentionally minimal UI polish. Both channels use the same Spring Boot order/stock services.

**Exit criteria:** Simultaneous purchases cannot both claim the last unit; duplicate submission cannot double-deduct stock; simulated unsuccessful payments do not complete a sale; store and online views converge to the same stock state.

### Phase 5 — Reporting, auditing, demonstration and hardening
**Implement:** Owner-facing dashboard, product and channel sales summaries, revenue/cost estimates, movement and action audit, demo datasets and script, input validation, failure testing, basic SQLite backup/restore workflow. Update documentation.

**Exit criteria:** Demo clearly shows end-to-end inventory setup, sale from each channel, low stock, cancellation, adjustments and reports. Tests and a restore drill pass.

### Phase 6 — After MVP: security, deployment and GUI architecture review
**Implement planning/review, not new MVP features:** Conduct the formal security, deployment-topology and GUI/PWA-versus-desktop assessment defined in Section 8. Validate threat model, tenant isolation, migration to PostgreSQL, backup/restoration, operational costs and merchant constraints. Document deployment criteria before authorizing a real-business pilot.

### Phase 7 — After review and pilot: resilient SaaS and optional AI
**Later:** Real bank terminal adapters, multi-location features, local store nodes, stock allocation, durable sync event ingestion and conflict resolution, authenticated integrations, paid plans, AI-assisted product discovery with verified fitment validation.

## 7. Demo and measurement plan

Demo the following in under 15 minutes:
1. Log in as platform operator; show separate automotive and grocery tenants and demonstrate that their data is isolated.
2. Log in as manager; create an employee, import sample SKUs, demonstrate a saved/resumed stock-count session and approved variance, including Honda Civic fitment and a low-stock threshold.
3. Search for the compatible Honda Civic air filter on the storefront.
4. Complete a simulated in-store sale, then show stock reflected on the storefront.
5. Attempt simultaneous checkout of the final item from POS and web; only one commits.
6. Record damage adjustment with a reason and employee attribution.
7. Show low-stock alert, movement history, order history and sales report.
8. Cancel an eligible order and show stock release where applicable.

Document what is **simulated**, what is **functional backend logic**, and what remains future production work.

## 8. Confirmed decisions and remaining questions

### Confirmed on 2026-11-12

1. **Multi-tenant MVP:** Demonstrate at least two independently isolated business tenants (automotive parts and grocery are suitable), with automotive as the primary business scenario.
2. **Employee management:** Implement real login plus owner/manager-created employee accounts, roles, deactivation and server-side permissions; do not build email verification or SMTP.
3. **Automotive compatibility:** Implement a proper relational compatibility model (make/model/year and optional engine/trim), allowing one product to match multiple vehicle configurations. Compatibility is data-based, not AI-generated.
4. **Inventory setup service:** Include dedicated, resumable stock-count sessions with barcode/SKU entry, reviewable variances and authorized approval/commit, alongside manual entry and CSV import.
5. **Demo experience:** Choose Option B — an inventory dashboard plus lightweight POS and e-commerce interfaces proving shared stock consistency and simulated checkout.

### Still to resolve before implementation prompts

1. **Repository baseline:** Confirm the existing Spring Boot application's exact code, build system, Java/Spring versions and migrations before making changes.
2. **Business/operator roles:** Decide whether the platform operator can view tenant business data routinely or only with auditable support authorization.
3. **Sales reservation policy:** Choose checkout reservation duration, timeout behavior and what constitutes successful simulated payment.
4. **Refunds/returns:** Decide the exact MVP rules for cancelled orders, paid-but-unfulfilled orders and returned physical stock.
5. **Stock count controls:** Decide whether a count requires manager approval every time or only for variance thresholds; determine whether the original quantity remains sellable while the count is open.
6. **Units/locations:** Confirm fractional quantities in the MVP and how many active locations each demo tenant should show.
7. **Frontend structure:** Choose one React app with role-specific routes (recommended for MVP) versus separate React applications.
8. **Demo access:** Confirm whether the MVP runs entirely locally or needs a public demonstration URL.
9. **Naming:** Confirm whether Osyntra is both the company brand and product name, or the product will have a distinct name.

### Formal post-MVP architecture review (required before real-business deployment)

After the functional MVP is complete, schedule a focused design review to select and document:

- **Security principles and implementation:** threat modeling; authentication/session security; authorization and tenant isolation; secure configuration and secret management; input validation; audit integrity; dependency management; application and API security tests; backup security; vulnerability assessment; privacy and data retention. Baseline security controls must still be implemented during the MVP — this review deepens and verifies them, rather than postponing all security.
- **Deployment patterns by customer infrastructure:** (a) no local server / hosted SaaS; (b) business with a local computer/server and future cloud sync; (c) business with its own cloud or managed server; (d) hybrid/offline-capable setup. Compare operational cost, reliability, updates, backup, recovery, payment connectivity and support effort. No offline synchronization is promised as part of the initial MVP.
- **Interface strategy:** evaluate browser-based responsive React UI/PWA versus desktop GUI or hybrid POS interface, considering usability with scanners and tablets, accessibility, aesthetics, installation, offline capability, maintenance and business-device constraints. A minimal functional React UI is still required for the MVP.

This review is a **go/no-go gate for any real deployment**. No local-only MVP should be described as production-secure merely because it has login and tests.

## 9. Prompt-generation rules for later

Once the open decisions are answered, generate one implementation prompt per phase or meaningful subphase. Each prompt must: (a) inspect existing code first, (b) implement incrementally without unrelated rewrites, (c) specify API behavior, data constraints, transactions and roles, (d) add unit/integration tests of meaningful business logic, (e) keep comments plain-language and useful for nontrivial logic, (f) update README and an architecture/change log with previous behavior, new behavior, design rationale, problems and resolutions, and (g) provide a demonstration checklist. Avoid paid services and features explicitly deferred from the MVP.
