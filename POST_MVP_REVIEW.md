# Post-MVP architecture review

This is the Phase 6 review agenda. The review has not been conducted and no real-business pilot, production deployment or cloud purchase is authorized by completing the local demo.

## Evidence to take into review

- SQLite test results for tenant ID tampering, employee roles/deactivation and last-owner protection.
- Shared POS/ONLINE reservation concurrency and duplicate-payment tests.
- Ledger reconciliation, count drift/approval, immutable order snapshot, refund/return and capability-token tests.
- Populated baseline migration and repeatable two-tenant demo tests.
- Local backup/WAL/restore drill.
- Current API contract, demo policies and preserved PostgreSQL settings.

## Security review

Assess the threat model for operators, employees and public customers; owner onboarding/recovery; password policy/rate limiting; HTTP Basic versus session/token auth; HTTPS; CSRF and CORS assumptions; request size limits; public checkout abuse/rate limits; capability lifecycle and loss/recovery; tenant boundaries including support access; database/backup file permissions; secret storage; dependency/vulnerability management; audit integrity and retention; input validation; privacy; incident response.

Baseline authorization is implemented now. Production abuse protection, credential recovery, formal penetration testing and a hardened deployment boundary are not established by the local test suite.

## PostgreSQL and data migration gate

1. Choose and document PostgreSQL schema/migration strategy for businesses, memberships, fitment, per-location stock, counts, commands and commerce.
2. Preserve existing production credentials/data; evaluate real schema/history before baselining or importing anything.
3. Map UUIDs, timestamps, numeric money, indexes, constraints, lookup policy and SQLite migration differences explicitly.
4. Import a copied dataset, reconcile all stock/reservations/ledger entries and immutable money snapshots.
5. Rerun cross-tenant, last-unit concurrency, idempotency, cancellation, expiry and restore tests against PostgreSQL. SQLite writer serialization is not PostgreSQL evidence.
6. Design an operator recovery process and production migrations/rollback/backup procedure.
7. Remove the PostgreSQL startup guard only after these checks pass.

## Merchant deployment topology

Compare hosted SaaS, merchant-owned local server, merchant-owned cloud and future hybrid/offline arrangements. Review power/network failure, scanners/tablets, updates, monitoring, restore objectives, reliability, support cost, geographic/privacy constraints and payment connectivity. No offline synchronization is promised by the MVP.

## Interface review

Review the separate frontend's responsive React/PWA, desktop or hybrid POS approach against scanner input, tablets, accessibility, installation, printing, maintenance and device limits. The current server supports CORS for explicitly configured origins and does not embed a frontend.

## Product-policy review

Confirm five-minute reservations; expiry sweep latency; whole-unit stock; one membership per employee; owner/manager permissions and approval separation; sales during physical counting; full-order cancellation/returns/refunds; unambiguous barcode policy; fitment verification/provenance; single-currency rules; report refund/date/cost conventions; capability token retry/recovery; public storefront visibility.

## Go/no-go

Record reviewer names, date, findings, resolved/high-priority risks, migration/concurrency/restore evidence, deployment architecture/costs and the exact pilot authorization. Until that review is approved, use the server as a local demonstration.

Phase 7 capabilities—real payments, paid subscriptions, durable offline sync, advanced multi-location allocation and optional validated-data AI—remain separate future work.
