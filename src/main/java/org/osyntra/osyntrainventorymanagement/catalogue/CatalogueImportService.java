package org.osyntra.osyntrainventorymanagement.catalogue;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.osyntra.osyntrainventorymanagement.services.*;
import org.osyntra.osyntrainventorymanagement.dtos.product.ProductRequest;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;

import static org.osyntra.osyntrainventorymanagement.repositories.MvpStore.*;

@Service
public class CatalogueImportService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final ProductService products;
    private final CommandService commands;
    private final ObjectMapper json;
    private final Validator validator;
    private final Clock clock;
    private static final List<String> FIELDS = List.of("sku", "barcode", "itemName", "itemDescription", "unitPrice", "categoryId", "cost", "unitOfMeasure", "minStockThreshold", "productKind", "parentId", "variantKey");

    public CatalogueImportService(MvpStore db, TenantContext tenant, ProductService products, CommandService commands, ObjectMapper json, Validator validator, Clock clock) {
        this.db = db;
        this.tenant = tenant;
        this.products = products;
        this.commands = commands;
        this.json = json;
        this.validator = validator;
        this.clock = clock;
    }

    public String template() {
        return String.join(",", FIELDS) + "\r\n";
    }

    public byte[] templateXlsx() throws IOException {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var row = book.createSheet("Products").createRow(0);
            for (int i = 0; i < FIELDS.size(); i++) row.createCell(i).setCellValue(FIELDS.get(i));
            book.write(out);
            return out.toByteArray();
        }
    }

    public List<List<String>> xlsx(byte[] bytes) throws IOException {
        if (bytes.length > 1000000) UnitService.bad("Upload limit is 1 MB");
        int expanded = 0, entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > 200 || entry.getName().contains("vbaProject") || entry.getName().contains("externalLinks"))
                    UnitService.bad("Macros/external links or excessive archive entries are not accepted");
                byte[] buffer = new byte[8192];
                for (int n; (n = zip.read(buffer)) != -1; )
                    if ((expanded += n) > 8000000) UnitService.bad("Expanded workbook limit is 8 MB");
            }
        }
        if (entries == 0) UnitService.bad("Only XLSX workbooks are accepted");
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() != 1) UnitService.bad("Workbook must contain exactly one sheet");
            var sheet = book.getSheetAt(0);
            if (sheet.getLastRowNum() > 1000) UnitService.bad("At most 1000 data rows are accepted");
            var formatter = new DataFormatter(Locale.ROOT);
            List<List<String>> rows = new ArrayList<>();
            for (int n = 0; n <= sheet.getLastRowNum(); n++) {
                var row = sheet.getRow(n);
                List<String> cells = new ArrayList<>();
                int count = row == null ? 0 : row.getLastCellNum();
                if (count > 20) UnitService.bad("At most 20 columns are accepted");
                for (int col = 0; col < count; col++) {
                    var cell = row.getCell(col);
                    if (cell != null && cell.getCellType() == CellType.FORMULA)
                        UnitService.bad("Workbook formulas are not accepted");
                    String text = cell == null ? "" : formatter.formatCellValue(cell);
                    if (text.length() > 500) UnitService.bad("Cell value exceeds 500 characters");
                    cells.add(text);
                }
                rows.add(cells);
            }
            return rows;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            UnitService.bad("Malformed or unsupported XLSX workbook");
            return List.of();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> csv(CatalogueDtos.CsvImport r) throws Exception {
        return preview(CsvSupport.parse(r.csv()), r.options(), CommandService.hash(r.csv()));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> workbook(byte[] bytes, CatalogueDtos.ImportOptions options) throws Exception {
        return preview(xlsx(bytes), options, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private Map<String, Object> preview(List<List<String>> rows, CatalogueDtos.ImportOptions options, String digest) throws Exception {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        String fingerprint = "CATALOGUE_PREVIEW|" + digest + "|" + new TreeMap<>(options.mapping()) + "|" + options.duplicatePolicy();
        String prior = commands.replay(business, options.requestId(), fingerprint);
        if (prior != null) return get(prior);
        if (rows.size() < 2 || rows.size() > 1001) UnitService.bad("A header and 1–1000 data rows are required");
        List<String> header = rows.getFirst().stream().map(String::trim).toList();
        if (header.size() > 20 || new HashSet<>(header).size() != header.size())
            UnitService.bad("Headers must be unique, at most 20 columns");
        Map<String, Integer> columns = new HashMap<>();
        for (String field : FIELDS) {
            String source = options.mapping().getOrDefault(field, field);
            int index = header.indexOf(source);
            if (index >= 0) columns.put(field, index);
        }
        if (!FIELDS.containsAll(options.mapping().keySet()) || !header.containsAll(options.mapping().values()))
            UnitService.bad("Invalid header mapping");
        if (!columns.keySet().containsAll(List.of("sku", "itemName", "itemDescription", "unitPrice")))
            UnitService.bad("Required catalogue columns are missing");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO catalogue_imports VALUES(?,?,'PREVIEW',?,?,?,NULL)", id, business, options.duplicatePolicy(), tenant.actor().getId(), clock.millis());
        Set<String> skus = new HashSet<>(), codes = new HashSet<>();
        for (int n = 1; n < rows.size(); n++) {
            Map<String, Object> body = new LinkedHashMap<>();
            String error = null, disposition = "CREATE", existingId = null, expected = null;
            for (var col : columns.entrySet()) {
                int index = col.getValue();
                String text = index < rows.get(n).size() ? rows.get(n).get(index).trim() : "";
                if (!text.isEmpty()) body.put(col.getKey(), text);
            }
            try {
                ProductRequest request = json.convertValue(body, ProductRequest.class);
                if (!validator.validate(request).isEmpty())
                    throw new IllegalArgumentException("Invalid product fields");
                String sku = request.sku().trim(), barcode = request.barcode();
                if (!skus.add(sku)) {
                    if (options.duplicatePolicy().equals("SKIP")) disposition = "SKIP";
                    else throw new IllegalArgumentException("Duplicate SKU within file");
                }
                var existing = db.rows("SELECT id FROM products WHERE business_id=? AND sku=?", business, sku);
                if (!existing.isEmpty()) {
                    existingId = string(existing.getFirst(), "id");
                    disposition = switch (options.duplicatePolicy()) {
                        case "SKIP" -> "SKIP";
                        case "UPDATE" -> "UPDATE";
                        default -> throw new IllegalArgumentException("SKU already exists");
                    };
                    expected = json.writeValueAsString(products.findById(UUID.fromString(existingId)));
                }
                if (!disposition.equals("SKIP")) {
                    for (String code : new HashSet<>(barcode == null ? List.of(sku) : List.of(sku, barcode))) {
                        if (!codes.add(code)) throw new IllegalArgumentException("Ambiguous SKU/barcode in file");
                        if (db.number("SELECT count(*) FROM products WHERE business_id=? AND id<>? AND (sku=? OR barcode=?)", business, Objects.toString(existingId, ""), code, code) > 0)
                            throw new IllegalArgumentException("SKU/barcode already identifies another product");
                    }
                    if (request.categoryId() != null)
                        db.one("SELECT id FROM categories WHERE business_id=? AND id=?", business, request.categoryId());
                }
            } catch (Exception invalid) {
                error = "Invalid fields, duplicate/ambiguous code or inaccessible reference";
                disposition = "ERROR";
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("product", body);
            payload.put("existingId", existingId);
            payload.put("expected", expected);
            db.update("INSERT INTO catalogue_import_rows VALUES(?,?,?,?,?,?)", id, business, n + 1, json.writeValueAsString(payload), error, disposition);
        }
        commands.save(business, options.requestId(), fingerprint, id);
        return get(id);
    }

    public Map<String, Object> get(String id) {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var result = db.one("SELECT * FROM catalogue_imports WHERE id=? AND business_id=?", id, business);
        result.put("rows", db.rows("SELECT row_number,error,disposition FROM catalogue_import_rows WHERE import_id=? AND business_id=? ORDER BY row_number", id, business));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> commit(String id, CatalogueDtos.Commit r) throws Exception {
        tenant.requireRole("OWNER", "MANAGER");
        int business = tenant.businessId();
        var job = get(id);
        String command = "CATALOGUE_COMMIT|" + id;
        if (commands.replay(business, r.requestId(), command) != null) return get(id);
        if (job.get("status").equals("COMMITTED")) {
            commands.save(business, r.requestId(), command, id);
            return get(id);
        }
        if (db.number("SELECT count(*) FROM catalogue_import_rows WHERE import_id=? AND error IS NOT NULL", id) > 0)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Fix errors in a new preview before commit");
        for (var row : db.rows("SELECT * FROM catalogue_import_rows WHERE import_id=? AND business_id=? ORDER BY row_number", id, business)) {
            if (row.get("disposition").equals("SKIP")) continue;
            var payload = json.readTree(row.get("payload").toString());
            ProductRequest request = json.treeToValue(payload.get("product"), ProductRequest.class);
            if (!validator.validate(request).isEmpty()) UnitService.bad("Invalid persisted preview");
            if (row.get("disposition").equals("UPDATE")) {
                UUID product = UUID.fromString(payload.get("existingId").asString());
                if (!json.writeValueAsString(products.findById(product)).equals(payload.get("expected").asString()))
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Product changed after preview; create a new preview");
                products.update(product, request);
            } else products.create(request);
        }
        db.update("UPDATE catalogue_imports SET status='COMMITTED',committed_ms=? WHERE id=? AND business_id=?", clock.millis(), id, business);
        commands.save(business, r.requestId(), command, id);
        return get(id);
    }
}
