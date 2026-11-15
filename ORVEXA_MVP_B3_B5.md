# Orvexa functional server MVP — B3 through B5

The user authorized B3–B5 together. These phases extend B0–B2 in the existing Spring Boot server; the frontend remains separate and SQLite remains the chosen local database. B6–B9 are not implemented by this work.

## B3 — Catalogue, variants, units, labels and import

Products now have immutable SIMPLE/PARENT/VARIANT identity. A VARIANT references an active PARENT in the same business and has a unique variantKey within that parent. SKU/barcode uniqueness remains tenant-wide, including cross-code collision guards. Each sellable variant has its own price, stock, classification and units. Parents cannot hold stock or be sold; active variants must be archived before their parent.

Create through the existing POST /api/products:

```json
{"sku":"SHIRT-BLUE-S","itemName":"Blue shirt small","itemDescription":"Example variant","unitPrice":100,"cost":0,"unitOfMeasure":"EACH","productKind":"VARIANT","parentId":"PARENT_UUID","variantKey":"BLUE-S","minStockThreshold":3}
```

Omit variant fields for a simple product. Use productKind=PARENT without parentId/variantKey to create a grouping parent. Existing category, fitment, tax assignment and product archive routes are retained.

Product thresholds are central and applied independently at each location. Changing a product threshold updates each location's threshold, never its quantities. V9 archives old per-location thresholds in legacy_reorder_levels; where no central threshold existed it selects the highest old threshold. The old reorder-level route now sets this global product policy.

Units have immutable positive decimal factors, up to six decimal places, relative to an immutable base unit. Purchase/sale permissions are explicit. The ledger retains whole base units; choose an appropriately small base such as EACH, GRAM or MILLILITRE. Conversions must resolve exactly to a positive base integer, with overflow rejected. Fractional base stock is not silently rounded. Existing stock is not rescaled.

POST /api/v1/catalogue/products/{id}/units:

```json
{"code":"BOX","factor":12,"purchaseAllowed":true,"saleAllowed":true,"requestId":"unit-command-unique"}
```

POST .../{id}/convert with unit=BOX, quantity=0.5 and purpose=SALE returns baseQuantity=6. Checkout accepts either the original base quantity or unitCode/unitQuantity, and retains the supplied unit/factor snapshot:

```json
{"lines":[{"productId":"PRODUCT_UUID","unitCode":"BOX","unitQuantity":0.5}],"requestId":"checkout-unique"}
```

Existing UPC/EAN/GTIN strings remain unchanged, including leading zeros. POST .../{id}/barcode generates an ORV- internal code only when no barcode exists and otherwise returns the existing code. It is not a GS1-issued GTIN. GET .../{id}/label?format=svg|png|pdf&widthMm=100&heightMm=30 produces bounded CODE128 labels. Dimensions are limited to 50–150 mm by 20–80 mm, with a minimum module width and quiet zones. Long/nonprintable codes are rejected for labels while remaining usable as stored lookup strings. PNG labels use 300-DPI pixel geometry; print at actual size without stretching.

Bounded catalogue import supports CSV and XLSX. GET /api/v1/catalogue/imports/template?format=csv|xlsx downloads headers. POST /imports/csv accepts:

```json
{"csv":"Code,Name,Description,Price\nNEW-SKU,Example,Description,15.50\n","options":{"mapping":{"sku":"Code","itemName":"Name","itemDescription":"Description","unitPrice":"Price"},"duplicatePolicy":"REJECT","requestId":"preview-unique"}}
```

XLSX uses POST /imports/xlsx multipart parts file and options (the latter application/json with the same options structure). Identity mappings can use mapping={}. Preview saves row dispositions/errors and changes no products. GET /imports/{id} resumes status; POST /imports/{id}/commit with requestId commits the entire bounded job atomically. Invalid rows prevent commit. Fix errors in a new preview rather than mutating a saved preview.

Duplicate policies: REJECT records an error; SKIP preserves existing rows; UPDATE updates an existing active SKU only if its complete saved product snapshot still matches preview. Concurrent edits or commit-time collisions roll back all product/audit/unit writes. Repeating a preview/commit command returns its original result. In-file duplicate or ambiguous codes are checked; referenced categories/parents must already exist in the same business. Imports do not receive stock or assign tax automatically.

Limits: 1000 data rows, 20 columns, 1,000,000 CSV characters, 1 MB XLSX upload, 8 MB expanded workbook and 200 ZIP entries. XLSX requires one sheet, bounded cells, no formulas, macros or external links. Values are never evaluated as code. Exported spreadsheet-sensitive text is escaped, including leading whitespace/control characters. Use text cells for exact barcode identifiers. Large background jobs/chunked imports are future work; this MVP uses bounded atomic transactions.

## B4 — Acquisition value, purchasing, batches and transfers

All quantity, reservation, batch and value changes share StockLedger's transaction. Negative stock and consumption of reserved stock are prohibited. Acquisition value is retained as decimal currency with six places per location/product. Weighted average = remaining location value / on-hand quantity. Receipts add actual entered acquisition value; outgoing adjustments/transfers remove the current proportional value. A final depletion removes the full remaining value to avoid rounding residue.

Confirmed sales save actual consumed value once in sale_cost_snapshots at stock commitment. The original checkout cost remains a quoted estimate; reports use the actual sale-cost snapshot where present. Returns/cancellations restore the historical sale value for the units physically restocked, rather than current catalogue cost. Non-resellable returns and refund-only operations do not add stock/value.

V10 initializes existing balances from current catalogue cost, explicitly labeled MIGRATED_ESTIMATE. Legacy opening counts/imports and legacy receipt APIs use catalogue cost where no actual acquisition value was supplied; these are estimates, not reconstructed purchase history. Positive count/correction variance uses the current location average (catalogue fallback at zero stock). New valued receipts/purchase orders should be used for actual acquisition cost.

Purchasing routes:

| Method | Route |
| --- | --- |
| GET/POST | /api/v1/purchasing/suppliers |
| GET/POST | /api/v1/purchasing/orders |
| GET | /api/v1/purchasing/orders/{id} |
| POST | .../{id}/receive, .../{id}/cancel |
| POST | /api/v1/inventory/adjustments |
| POST | /api/v1/inventory/products/{id}/tracking |
| GET | /api/v1/inventory/batches?locationId=...&expiring=true&days=30 |
| POST | /api/v1/inventory/batches/{id}/count |
| GET/POST | /api/v1/inventory/transfers |
| GET | /api/v1/inventory/transfers/{id} |
| POST | .../{id}/dispatch, .../{id}/receive, .../{id}/cancel |

Create a supplier with name, email and requestId. Create a purchase order:

```json
{"supplierId":"SUPPLIER_UUID","locationId":1,"lines":[{"productId":"PRODUCT_UUID","unit":"BOX","quantity":2,"unitCost":120}],"requestId":"po-unique"}
```

Currency comes from the business and is locked once stock, purchasing or sales history exists. Default threshold changes propagate to products that inherit them, while explicit product thresholds stay unchanged. Unit factor, purchase quantity, unit cost and total acquisition value are snapshotted on the PO. Receive with:

```json
{"lines":[{"productId":"PRODUCT_UUID","quantity":0.5,"batchCode":"LOT-001","expiresOn":"2099-12-31"}],"requestId":"receipt-unique"}
```

Receiving quantities use the PO's purchase unit/factor; omit batch metadata for untracked products. Partial receipts preserve remaining quantities and acquisition value. Final receiving consumes the value remainder exactly. Over-receiving, duplicate lines and concurrent duplicate commands cannot add stock twice. Receipt lines retain acquisition value/batch/expiry. Only completely unreceived OPEN POs can be cancelled; received history is retained.

For valued direct receipts/adjustments:

```json
{"locationId":1,"productId":"PRODUCT_UUID","unit":"EACH","quantity":5,"unitCost":10,"type":"RECEIPT","reason":"Supplier delivery","requestId":"adjustment-unique"}
```

Types are RECEIPT, DAMAGE, CORRECTION_IN and CORRECTION_OUT. Incoming values use quantity × unitCost; outgoing values use the current location average. Reason and actor are audited. No client-provided total is accepted.

Enable batch tracking before receiving stock with enabled=true and requestId through the tracking endpoint. This requires all-branch management access and zero balances/reservations; tracking mode is locked once stock/sale history exists. Use a distinct SKU for a different tracking policy. Each location/batch tracks on-hand and reserved units. A batch code's expiry cannot be changed by receiving it again.

Tracked receipts require a batch code; expiry is optional. Reservation uses eligible batches in expiry order. An expiry date is valid through that UTC day; older batches are excluded from saleable availability and cannot be sold. Payment rechecks reserved batch expiry. Physical returns restore original allocated batches, so expired returned stock stays unsellable. Expiry alerts include past-due and soon-due stock. Generic aggregate counts remain available for untracked stock; tracked discrepancies use the approved batch-count endpoint with expectedQuantity, countedQuantity, reason and requestId. A stale count or a count below reserved quantity conflicts.

Transfer example:

```json
{"sourceId":1,"destinationId":2,"lines":[{"productId":"PRODUCT_UUID","quantity":4,"batchCode":"LOT-001"}],"requestId":"transfer-unique"}
```

Both branches must be permitted. Tracked transfers require one explicit source batch per product; use separate transfers to move multiple batches of the same product. REQUESTED does not move stock. Dispatch removes source quantity/value, retained in transit. Receive with quantities={"PRODUCT_UUID":2} and requestId; partial receiving adds only those units and their saved acquisition value to the destination. The last receipt consumes the remaining transit value exactly. GET shows in_transit_quantity/value. Dispatched transfers cannot be silently cancelled; receive/reconcile remaining stock. Loss/write-off workflows for in-transit discrepancies are not automated in this MVP.

Pending purchases/transfers block disabling their branches. Existing physical counts, reason-coded adjustments and quantitative audit remain in use.

## B5 — Pricing, payment reconciliation, reversals and receipts

POST /api/orders and /api/orders/cart remain the authoritative checkout/preview routes. Public storefront pricing uses the same branch/promotion prices. GET /api/v1/checkout/catalogue?locationId=1&code=EXACT-SKU-OR-BARCODE supports exact scanning; search, limit and offset support POS browsing. It returns effective price_cents and saleable available quantity. Cart/checkout adds tax from B2 and validates all stock. Preview never reserves.

Branch prices are append-only effective-dated versions. POST /api/v1/checkout/prices with locationId, productId, price, effectiveFrom and requestId; GET lists permitted history. POST /promotions with optional locationId/productId, name, percent, startsAt, endsAt and requestId creates a bounded automatic discount; POST /promotions/{id}/revoke records an audited stop. Business-wide promotions require all-branch management access.

A staff checkout may include discountPercent and discountReason. Manual discounts require Owner/Administrator/Manager authority and a reason; public requests cannot grant them. The greatest applicable automatic/manual percentage is used once, without stacking. Discounted unit prices round to currency cents before line extension; B2 tax then rounds per extended line. Original list price, discount, rule/price version and tax metadata are retained on the order. Coupon/loyalty/usage-cap promotion engines remain B6.

Payment routes:

| Method | Route |
| --- | --- |
| GET/POST | /api/v1/checkout/orders/{id}/payments |
| POST | /api/v1/checkout/payments/{id}/reconcile |
| GET/POST | /api/v1/checkout/orders/{id}/reversals |
| GET | /api/v1/checkout/reversals/{id} |
| POST | .../{id}/review, .../{id}/complete |
| GET | /api/v1/checkout/orders/{id}/receipt?format=text|html|pdf |
| POST | /api/v1/checkout/orders/{id}/receipt-email |

Start payment with provider=CASH|MANUAL_TERMINAL|SIMULATOR and requestId. CASH records the cashier's confirmation of received cash and commits sale stock/COGS once. MANUAL_TERMINAL creates an UNKNOWN attempt and retains stock; it does not call a bank or charge a card. Amount/currency come from the immutable order. Only one unresolved attempt is allowed per order.

An authorized Owner/Administrator/Manager reconciles a terminal result:

```json
{"outcome":"CONFIRMED","externalReference":"terminal-receipt-reference","eventId":"unique-confirmation-event","requestId":"reconcile-unique"}
```

CONFIRMED commits stock/COGS once. DECLINED means a verified unsuccessful payment and releases the reservation. VOIDED means staff verified an external void/refund before releasing an uncertain hold. Event IDs, command IDs and external references prevent duplicate/conflicting reconciliation. A different confirmed outcome cannot overwrite a completed attempt.

UNKNOWN/PENDING holds survive normal reservation expiry. Cancellation, another payment and legacy simulator settlement cannot bypass them. If a batch expires during the hold, confirmed sale completion is blocked; verify the terminal result and perform an external void/refund when necessary, then record VOIDED. Never release a hold merely because the terminal timed out. There is no unauthenticated provider webhook: no bank SDK/signature configuration exists. Reconciliation event replay is tested through the privileged API.

PaymentProvider supplies a provider-independent seam. Current providers perform local preparation only. A future approved network adapter must persist/commit its attempt/outbox before external side effects and validate provider callbacks. No NCB/Scotiabank or other terminal API is claimed.

SIMULATOR is enabled explicitly in demo/test and requires a demo/test/dev profile. It accepts simulatorOutcome=CONFIRMED|DECLINED|UNKNOWN. Outside those profiles it is unavailable. The legacy successful=true/false /api/orders/{id}/payment and public equivalent remain demonstration-only simulator routes. Default SQLite mode uses explicit cash/manual-terminal workflows. No raw card fields are accepted or stored.

### Partial returns and refunds

Cashiers may request a reversal; managers approve/reject and complete it. Amounts, tax and cost come from original sale-line snapshots, never the request. Requests reserve the remaining refundable quantity, including other pending/approved requests; rejected requests release that budget. Rounding allocates the remaining historical amount, with the last portion taking the exact remainder. Zero-value units still have a physical return budget.

```json
{"type":"RETURN","resellable":true,"reason":"Unopened item","lines":[{"productId":"PRODUCT_UUID","quantity":1}],"requestId":"return-unique"}
```

RETURN requires fulfilled goods. REFUND records financial reversal without restocking and must use resellable=false. CANCEL applies to all remaining unfulfilled paid units and restores them. Duplicate/over-limit requests conflict. Legacy full refund/return routes cannot bypass a new approved reversal.

Review with approve=true/false and requestId. Complete an approved reversal with confirmed=true, method=CASH|EXTERNAL_MANUAL|SIMULATED, externalReference when required, and requestId. The refund method must match the original provider. External terminal refunds must already have been performed/verified by staff; the server records their reference and never pretends to send a refund through a bank API.

Restock/value changes and completed refund records commit together and only once. Only resellable physical returns or unfulfilled cancellations restore stock at historical cost. Partial returns leave the order fulfilled until all relevant quantities are reversed. Reports include actual payment records and completed refund totals; gross pre-tax margin/COGS estimates remain before return-cost reconciliation. A full accounting/tax-return report is B7.

### Immutable receipt delivery

Confirmed payment captures a text receipt and SHA-256 in the same transaction. Later product, price, branding or fulfillment changes do not rewrite it. Text, escaped browser HTML, PDF and queued email use that saved payload. Original sale receipts remain unchanged after refunds; reversal records hold refund/credit details. Historical pre-B5 receipts are reconstructed from saved order fields and are not represented as originally captured documents.

The PDF uses a portable standard font; unsupported glyphs render as '?' while UTF-8 text/HTML retain the full content. Customer emails are queued transactionally through the existing encrypted outbox, with per-actor rate limits and idempotent delivery requests. SMTP is still opt-in and no external email was sent during implementation. Example email body: email=customer@example.com and requestId. The receipt request does not accept prices, tax totals or card details.

## Migrations, preservation and files

- SQLite V9: catalogue identity, units, bounded import jobs/rows and archived/global threshold policy.
- SQLite V10: per-location value, valuation ledger, actual sale costs, batches/allocations, suppliers, purchasing/receipts and transfers.
- SQLite V11: price/promotion versions, payment attempts/events/records, approved partial reversals, immutable receipts and promotion revocations.

V1–V8 are preserved. Existing IDs, account hashes, balances, orders, tax snapshots, original simulated payment/refund rows and history remain. New payment_records backfills legacy simulated records as SIMULATOR. The existing order-state column is retained; payment_state distinguishes unresolved payment holds without relabeling them as failed orders. Old checkout idempotency fingerprints are accepted for matching original base-unit commands.

New feature packages: catalogue, inventory and payments, with CatalogueController, PurchasingController and CheckoutController. Existing ProductService/Repository, StockLedger, InventoryService, CommerceService, ReportService, TenancyService, onboarding, security and mail outbox are extended. Dependencies are Apache POI for bounded XLSX, ZXing for barcode generation/decoding tests, and PDFBox for labels/receipts; no custom file execution or invented bank adapter was added. Implementation references: [POI security guidance](https://poi.apache.org/security.html), [ZXing CODE128 API](https://zxing.github.io/zxing/apidocs/com/google/zxing/oned/Code128Writer.html), and [PDFBox 3 documentation](https://pdfbox.apache.org/3.0/getting-started.html).

Actual final verification:

- `mvn -q test`: **122 Java tests passed**, zero failures/errors/skips. This includes the existing 92 B0–B2 tests, eight B3 catalogue cases, seven B4 acquisition/receiving/transfer/batch cases, thirteen B5 checkout/reconciliation/reversal cases, one complete real Bearer/MFA MVP flow, and an additional populated V8 upgrade regression.
- `mvn -q "-DskipTests" package`: successful executable JAR packaging after the passing suite.
- Verified consistent pre-upgrade backup: `target/orvexa-mvp-before-b3-b5.db`. V9–V11 applied successfully to the existing local SQLite database. Fresh and populated V1/V6/V7/V8 upgrade/repeat-migration checks pass in isolated databases.
- Final packaged server runs on port 8080 in `sqlite,demo` mode. Live health/readiness were UP; OpenAPI/Bearer authentication, catalogue XLSX template download, exact branch-price lookup, purchasing/transfer lists and tax-aware nonreserving cart preview passed.
- All eight existing demo passwords passed with their expected MFA/full-session gates and logout revocation. No account was automatically enrolled into MFA and no password was reset.
- Before/after digests verified original account/user/employee/business/location/catalogue/inventory/order/line/payment/refund/movement/audit columns, excluding only the explicitly consolidated product/location threshold fields. Quantities, reserved balances, identity hashes and historical monetary/tax fields matched. Prior thresholds are retained in legacy_reorder_levels.
- SQLite integrity/foreign-key checks, environment SHA-256 preservation and `git diff --check` passed. PostgreSQL settings/history were preserved; PostgreSQL was not connected or migrated.
- No external terminal charge/refund or SMTP email was initiated during implementation/smoke checks. Test purchase/sale/return mutations used isolated temporary SQLite databases.

## Verification and review boundary

Acceptance covers variants and tenant links, exact decimal conversions, all label formats with PNG decoding, valid/malformed/formula XLSX, mapped CSV and duplicate/rollback/retry behavior; per-location acquisition arithmetic, partial and concurrent receipts, value-preserving partial transfers, expired-batch denial and approved counts; branch pricing, manual discount denial, automatic promotion bounds/revocation, tax snapshots, payment uncertainty/expiry, duplicate reconciliation/cash retries, partial refund arithmetic/approval, zero-value returns and immutable receipt/email payloads.

FunctionalMvpTests exercises a real password login and Owner MFA through variant/tax setup, purchase-unit receiving into a batch, terminal uncertainty/reconciliation, saved receipt/email, partial physical return and gross/refund/net report reconciliation. Existing B1/B2, last-unit checkout, duplicate payment and populated-upgrade regressions remain included.

Manual prerequisites: frontend implementation, actual physical label/scanner validation, owner/TAJ/accountant review of tax eligibility and rounding, verification of legacy valuation estimates/reservations, SMTP provider delivery, real external terminal operation and references, operational restore/key custody, production TLS/monitoring and PostgreSQL parity. No cloud deployment or production readiness claim is made.

The requested functional server MVP ends at B5. Review these contracts and behavior before authorizing B6 customer/loyalty/notification work.
