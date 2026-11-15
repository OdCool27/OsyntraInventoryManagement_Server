# Local server MVP demonstration

This walkthrough demonstrates server behavior through Swagger/API calls. The dashboard, POS screen and storefront UI belong to the separate frontend project.

## Preparation

1. Set a local OSYNTRA_DEMO_PASSWORD (12–72 characters, at most 72 UTF-8 bytes).
2. Run: `mvn spring-boot:run "-Dspring-boot.run.profiles=sqlite,demo"`.
3. Open Swagger at http://localhost:8080/swagger-ui/index.html.
4. Follow the README Bearer login flow; Swagger Authorize takes the returned accessToken, not a username/password. Owners and platform staff must complete MFA enrollment/challenge. Use the automotive and grocery accounts in README. Read each owner's GET /api/auth/me response to discover businessId; do not assume demo tenant IDs are 1 and 2. Migrated baseline data occupies business 1.

All amounts/payments/receipts in this demonstration are simulated. Automotive compatibility is curated demonstration data.

For B1's multi-business invitation, branch-grant, verification/reset and local email walkthrough, see [ORVEXA_B1_IMPLEMENTATION.md](ORVEXA_B1_IMPLEMENTATION.md). An issued email can be read locally through GET /api/v1/auth/mail-preview using the issuer/recipient Bearer token. No external email is sent by default.

For B2 settings/tax demonstration, use an MFA-authenticated Owner to inspect `/api/v1/tax/templates` and `/api/v1/onboarding`. Follow [B2 examples](ORVEXA_B2_IMPLEMENTATION.md) in a disposable test business: create effective seller/profile versions, assign product classifications and compare server cart/checkout net/tax/gross. Templates remain illustrative and must not be treated as real product eligibility. Existing demo businesses retain unverified nonregistered defaults and unchanged prices.

## Functional B3–B5 walkthrough

Follow [the functional MVP guide](ORVEXA_MVP_B3_B5.md) in a disposable business: create a variant, add purchase units, verify illustrative tax rules, enable batches before receiving, create a supplier/PO and receive partial stock, preview/checkout, record cash or reconcile a manual-terminal attempt, download the saved PDF/HTML receipt, then request/approve/complete a partial return and inspect reports. The same flow is automated through real Bearer/MFA authentication in FunctionalMvpTests. No external terminal charge or email is sent by that test.

The original walkthrough below still demonstrates the legacy demo simulator. New cash/manual-terminal sales use the approved B5 reversal routes.

## Fifteen-minute walkthrough

1. **Provision and isolation.** Authorize as the platform operator, list /api/platform/businesses and inspect business metadata. Operator access to /api/products returns 403. Authorize as automotive.owner@demo.osyntra.local, list its products, then authorize as grocery.owner@demo.osyntra.local and verify its separate grocery catalog. Attempt to GET an automotive product ID as the grocery owner: 404.

2. **Managed employee.** As the automotive manager, POST /api/employees with a new INVENTORY_CLERK account. Creating an OWNER instead returns 403. As the owner, PUT that new employee's ID with status DEACTIVATED and verify its existing Bearer session fails and its membership is no longer available. Preserve an active owner.

3. **Opening stock by CSV.** Create a new generic automotive product with a unique SKU, price/cost and optional barcode. Download /api/imports/opening/template. POST /api/imports/opening/preview with `{"csv":"sku,quantity,reorderLevel\nYOUR-SKU,5,3\n"}`. Inspect row validation; commit through /api/imports/opening/{id}/commit. Repeating commit does not duplicate stock.

4. **Resumable physical count.** As a clerk, POST /api/counts with {}. Save a line using its SKU/barcode and counted quantity 4. GET the session later to resume it, review variance and submit. Clerk approval returns 403; as manager, approve. If stock changes between entry and approval, a 409 requires reopen/recount/resubmit. Stock remains sellable while drafts are open.

5. **Verified vehicle search.** Without employee authentication, GET /api/storefront/{automotiveBusinessId}/products?make=Honda&model=Civic&year=2009. The structured air-filter fixture appears; an out-of-range year does not. There is no AI prediction.

6. **POS and online shared stock.** As automotive cashier, POST /api/orders/cart with one product/quantity: it does not reserve. POST /api/orders with the same lines plus a unique requestId: it reserves for five minutes. Pay through /api/orders/{id}/payment with simulated CASH/CARD and successful=true. The public storefront reflects the same remaining available stock.

7. **Online checkout and failure.** POST /api/storefront/{businessId}/orders for another unit. Save accessToken and pass it as X-Order-Token on subsequent public order requests. Missing/incorrect tokens return 404. A simulated unsuccessful payment releases stock. A repeated successful payment command does not deduct it twice.

8. **Last-unit concurrency.** The automated acceptance demonstration is InventoryConcurrencyTests: two simultaneous requests, one POS and one ONLINE, attempt the last unit. Exactly one creates a reservation; the other returns 409. Two concurrent submissions of the same payment then both return the existing result and produce one sale/payment. Run `mvn "-Dtest=InventoryConcurrencyTests" test` to repeat this server proof.

9. **Adjustments, alerts and reports.** As manager, record a DAMAGE movement with quantity 1, reason DAMAGE, notes and a unique requestId. Inspect /api/inventory/alerts, /api/inventory/movements and /api/audit. View stock summary, sales by UTC date/channel/product and /api/reports/stock.csv. Download /api/orders/{id}/receipt; it explicitly states simulated/no real funds.

10. **Cancellation, refunds and returns.** Cancel a reserved order to release it. Cancel a completed/unfulfilled order to restore stock and record a simulated refund. A fulfilled order instead requires an explicit manager return; resellable=false leaves stock unchanged. A refund-only operation also leaves stock unchanged.

## Backup and restore drill

Python 3 is optional maintenance tooling. The server itself needs only Java/Maven.

```powershell
python scripts/sqlite_backup.py backup osyntra-mvp.db demo-backup.db
python scripts/sqlite_backup.py restore demo-backup.db demo-restored.db
```

Back up `.local/orvexa-security.key` securely as well (or retain the original ORVEXA_SECURITY_KEY). B1 identity data cannot be restored without its original key. The database utility does not copy it.

Both destination files must be new. A consistent backup includes committed WAL data; the utility checks database integrity and foreign keys. Stop the server, set SQLITE_DB_PATH to demo-restored.db, and restart. Confirm tenant accounts/catalog/stock match the snapshot. No command overwrites the active source database.

Automated maintenance proof:

```powershell
python -m unittest discover -s scripts -p "test_*.py" -v
```

Tests use private temporary databases and exercise WAL activity, restore and overwrite rejection. Never use real payment credentials, card details or production tenant data for the local demo.
