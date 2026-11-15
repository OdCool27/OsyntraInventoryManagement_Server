# Orvexa B2 — Settings, tax and onboarding

> Historical B2 snapshot: B3–B5 are now implemented. See [ORVEXA_MVP_B3_B5.md](ORVEXA_MVP_B3_B5.md) for the current catalogue, inventory, payment and receipt contracts and verification.

B2 extends the B1 server using SQLite. The frontend stays separate. This phase implements tenant settings, controlled branch inheritance, persisted onboarding and effective-dated sales tax with immutable order snapshots. B3 has not started.

## Features and boundaries

Business preferences cover receipt name/footer, low-stock notification preference and permission to override price mode at branches. The existing /api/business endpoint still manages name, currency and default stock threshold; currency remains locked after the first order. Notification preferences are stored configuration, not a new notification delivery system.

Branches inherit business receipt/notification preferences unless explicitly overridden. Null restores inheritance; an empty footer clears it, while a blank receipt name falls back to the business name. Branch price mode is ignored unless the business explicitly enables it. Turning that permission off immediately restores the effective business policy. Seller registration and product classifications cannot be overridden per branch.

Only all-branch Owners/Administrators manage business preferences, onboarding and tax configuration. Only Owners may attest verified tax policies/rates, with a review reference/note. Branch preferences require Owner/Administrator authority and a permitted branch. Other staff may read effective display settings. Platform staff cannot access tenant settings. Tenant IDs and caller-supplied totals/authority fields are rejected.

Tax configuration has three append-only histories:

- Seller policy: registered/nonregistered, registration reference, inclusive/exclusive pricing, verification flag/note and effective timestamp.
- Profile rate/rule: tenant code, label, TAXABLE/ZERO_RATED/EXEMPT classification, decimal percentage, illustrative flag, owner verification and effective timestamp.
- Product classification: product-to-profile assignment with an effective timestamp.

The latest effective version at checkout is selected using the server clock. Future versions do not apply early. A new version never rewrites old orders; in-place updates are blocked. Equal effective timestamps for the same history conflict. Effective timestamps require ISO-8601 offsets, at most millisecond precision, and dates after the Unix epoch through year 9999.

Seeded templates are **illustrative only**: standard GCT example 15%, zero-rated example 0%, and exempt example 0%. Every template retains illustrative=1 and requires_verification=1. Templates are not automatically assigned to products or enabled for collection. No item eligibility, registration threshold, legal exemption or current tax-law determination is automated.

Registered sellers require an owner-reviewed effective policy and reviewed effective profile for every sold product. Missing or unverified rules reject preview/checkout with 409 before stock allocation. Zero-rated and exempt remain distinct even though both calculate zero tax.

For compatibility, existing/new businesses start with an explicitly **unverified NOT_REGISTERED** baseline. Prices remain unchanged and no sales GCT is collected. Owners must review their actual status; the baseline is not evidence of exemption or legal eligibility. A nonregistered policy takes precedence over a product's assigned rate. Verified nonregistered status can be recorded through a new owner-reviewed policy.

## Calculation and snapshots

Calculations use BigDecimal and the existing explicit currency/two-decimal monetary model. Tax rounds HALF_UP once per extended line (unit price times quantity), then order totals sum those lines. No invoice-wide rerounding or per-unit tax rounding is applied.

- EXCLUSIVE: net = extended price; tax = round(net × percentage / 100); total = net + tax.
- INCLUSIVE: total = extended price; tax = round(total × percentage / (100 + percentage)); net = total − tax.
- Nonregistered: tax = zero, and net = total, regardless of price mode.
- Zero-rated/exempt profiles must have a zero percentage. Supported configured rates are 0–100%, with up to four decimal places. Invalid/overflowing amounts fail rather than wrapping.

Examples in JMD: three items at 25.50 exclusive of an illustrative 15% rate yield net 76.50, tax 11.48 and total 87.98. An inclusive price of 115.00 at that rate yields net 100.00, tax 15.00 and total 115.00. These are arithmetic examples, not product tax classifications.

Cart preview and POS/public checkout use the same server engine. Preview never reserves stock. New orders capture net/tax/gross, policy ID, seller status/reference/review flag, price mode, calculation instant, receipt branding and preferences revisions. Lines capture classification, applied rate, source profile/rate/assignment IDs, label, review/illustrative flags and amounts. Monetary/snapshot updates are blocked by database triggers. Payment, retries, cancellation and full refunds use the frozen total, even after a rate/configuration change. Prices/rules are fixed at checkout reservation creation for this five-minute simulated flow; tax-point/invoice requirements need professional review before real use.

Receipts render the saved branding, net/tax/total, seller review state, line classification/rate and illustrative-profile notice. Existing sales reports retain gross collected/refunded totals and add pre-tax net and sales tax. Product profit estimates use saved pre-tax net minus saved cost. Tax totals are gross sale-cohort figures before refund reconciliation; these reports are not tax returns.

Customs/import duty, import GCT, SCT, freight and landed-cost valuation are not checkout tax inputs. Product cost changes do not change sales tax. Purchasing/landed-cost treatment remains B4; tax filing, input credits, compound taxes and legal eligibility automation are outside B2.

## API contracts and examples

All endpoints require a FULL Bearer session with a selected tenant membership. Existing identity/MFA instructions are in README. Lists of policy/rate/product-classification history accept limit (default 50, maximum 200) and offset.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET/PUT | /api/v1/settings | Business receipt/notification/override preferences |
| GET/PUT | /api/v1/branches/{id}/settings | Raw overrides plus effective inherited preferences |
| GET | /api/v1/tax/templates | Explicitly illustrative Jamaica examples |
| GET/POST | /api/v1/tax/policies | Seller policy history/new version |
| GET | /api/v1/tax/profiles | Profile rate-version history |
| POST | /api/v1/tax/profiles/{code}/versions | New immutable profile version |
| GET/POST | /api/v1/products/{id}/tax | Classification history/new assignment |
| GET | /api/v1/onboarding | Persisted checklist plus current server readiness |
| PUT | /api/v1/onboarding/{step} | Resume/update one checklist step |

Business preferences PUT:

```json
{"revision":0,"receiptName":"Example Shop","receiptFooter":"Thank you","lowStockNotifications":true,"allowBranchPriceMode":false}
```

Branch preferences PUT (null inherits):

```json
{"revision":0,"receiptName":"Example Main Branch","receiptFooter":null,"lowStockNotifications":null,"priceMode":null}
```

Fetch the current revision before saving. Revision 0 creates a branch override/checklist entry; subsequent writes use its returned revision. Stale edits return 409 rather than overwriting another administrator's work. These PUTs do not use command request IDs.

Example seller policy POST below deliberately remains unverified. Replace the effective date as appropriate; a future timestamp schedules the policy and does not activate it early:

```json
{"effectiveFrom":"2030-01-01T00:00:00Z","registrationStatus":"REGISTERED","registrationNumber":"YOUR_REVIEWED_REFERENCE","priceMode":"EXCLUSIVE","verified":false,"verificationNote":"Awaiting owner/accountant review","requestId":"unique-policy-command"}
```

Example profile POST /api/v1/tax/profiles/STANDARD/versions:

```json
{"effectiveFrom":"2030-01-01T00:00:00Z","label":"Illustrative standard GCT — verify applicability","classification":"TAXABLE","ratePercent":15,"illustrative":true,"verified":false,"verificationNote":"Awaiting review","requestId":"unique-rate-command"}
```

An Owner records verified=true only after reviewing the correct rule and reference. To review an earlier unverified version, create a new version at a later effective timestamp; versions are not edited in place. Preserve illustrative=true for demonstration rules even when arithmetic is reviewed.

Assign a product using POST /api/v1/products/{id}/tax:

```json
{"code":"STANDARD","effectiveFrom":"2030-01-01T00:00:00Z","requestId":"unique-classification-command"}
```

Version/assignment creation uses the existing tenant-scoped command receipts. Identical requestId/payload retries return the original version; a changed command with the same ID returns 409. Only use the server's checkout response totals; tax/price/tenant fields in checkout requests are rejected.

## Resumable onboarding

Fixed checklist steps: BUSINESS_DETAILS, BRANCHES, TAX_REVIEW, CATALOG, OPENING_STOCK, STAFF, POS_TEST. Each stores NOT_STARTED/IN_PROGRESS/COMPLETE/SKIPPED, notes, revision, actor and update instant independently of login sessions.

PUT /api/v1/onboarding/TAX_REVIEW:

```json
{"revision":0,"status":"IN_PROGRESS","notes":"Waiting for accountant confirmation"}
```

COMPLETE requires the corresponding current server check: business metadata, active branch, verified effective tax policy/classifications, active products, opening/receipt/count movements, active non-owner staff, or a successful simulated POS payment. Saved completion is preserved when data changes, but ready=false and needsReview=true identify stale completion. For registered sellers, adding an unclassified product makes tax review stale. SKIPPED is a checklist choice and never bypasses checkout authorization/tax checks.

Onboarding state does not block dashboard/catalog access. The separate frontend can resume progress from these records; no frontend pages were added.

## Migration and changed files

Only SQLite V8__settings_tax_and_onboarding.sql is added for B2. It creates preferences/overrides, illustrative templates, policy/profile/assignment histories and onboarding state; extends existing orders/lines with snapshots; and adds monetary/version immutability constraints. Triggers provide preference/nonregistered baseline rows for future businesses created through existing provisioning/onboarding paths.

Existing orders preserve IDs, prices, costs, payment/refund amounts and status. They receive tax_snapshot_version=0 and LEGACY_UNSPECIFIED classification/status, with net provisionally equal to the original total and zero stored tax. This explicitly means historical tax is unknown and was not reconstructed. New orders use snapshot version 1. V1–V7, PostgreSQL settings/history, local environment and identity encryption key are preserved.

New production code: tax/TaxDtos, TaxCalculator, TaxService, SettingsService, OnboardingService and SettingsTaxController. Existing CommerceService, ReportService and SecurityConfig are extended. Tests extend migration/fixture support and add TaxB2Tests and TaxCalculatorTests. No dependency or external provider was added.

## Verification

Actual results:

- `mvn -q test`: **92 tests passed, zero failures/errors/skips**. This includes 16 B2 HTTP/authorization scenarios, five independent arithmetic tests and the populated V7 financial-history migration regression, plus all 70 B1/foundation/catalog/inventory/commerce tests.
- `mvn -q "-DskipTests" package`: successful executable JAR packaging after the passing suite.
- A consistent integrity-checked backup was created at `target/orvexa-b2-before-upgrade.db`; SQLite V8 applied successfully to the existing demo database.
- The packaged server was restarted in `sqlite,demo` mode on port 8080. Health/readiness were UP, OpenAPI remained available and Bearer authentication was retained.
- Live requests verified all eight existing demo passwords, Owner MFA gates, staff settings/branch access, illustrative template flags, nonregistered net/tax/total preview and logout revocation. Business tax policies/product classifications were not changed by smoke checks.
- Before/after snapshots matched user IDs/emails/password hashes, inventory balances and existing order/line monetary fields. SQLite integrity and foreign-key checks passed.
- The local `.env` SHA-256 matched the pre-B1/B2 baseline. PostgreSQL was not connected or migrated; no external email was sent.
- `git diff --check` passed.

Coverage includes nonregistered/taxable/zero-rated/exempt behavior; inclusive/exclusive and fractional rates; rounding/overflow; preview/order/payment/receipt/report consistency; future registration/rate/classification selection; historical orders/retry/refund preservation; public checkout capabilities/cost omission; settings inheritance/revisions; real Bearer role/branch denial; tenant/total spoofing; owner attestation; onboarding resume/stale completion; and populated V7 financial-history migration.

## TAJ/accountant validation and manual prerequisites

[Jamaica Business Gateway's GCT certificate guidance](https://jamaicabusinessgateway.com/en/acquire-gct-certificate) describes registration and standard-rated collection. [Jamaica Customs' duties and taxes guidance](https://jca.gov.jm/business/duties-and-taxes/) distinguishes zero-rated/exempt supplies and separate import charges. These sources guide the model; the seeded templates are examples and are not a complete current tax schedule.

Before a real business uses collection, the Owner and TAJ/accountant must confirm registration status/reference, applicable rates and effective dates, each product's taxable/zero-rated/exempt treatment, inclusive/exclusive display rules and any permitted branch differences, rounding/tax point, invoice contents, returns/credit notes, input credits and import/landed-cost separation. Record the reviewed source/reference in the owner verification note. Do not infer exemption from missing classifications or from the compatibility baseline.

SQLite remains the explicitly chosen local database. PostgreSQL parity, real payment providers, production tax compliance, frontend onboarding UI and advanced tax/refund reconciliation remain separate work. SMTP remains disabled unless explicitly configured as described in B1. Recommended next phase after B2 review: **B3 — catalogue variants, units, barcode and imports**.
