package org.osyntra.osyntrainventorymanagement.repositories;

import org.osyntra.osyntrainventorymanagement.dtos.product.*;
import org.osyntra.osyntrainventorymanagement.services.TenantContext;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class ProductRepository {
    private final JdbcClient jdbc;
    private final DatabaseDialect dialect;
    private final TenantContext tenant;
    private static final String SELECT = "SELECT p.*,c.name AS category FROM products p LEFT JOIN categories c ON c.id=p.category_id AND c.business_id=p.business_id";
    public static final RowMapper<ProductResponse> MAPPER = (rs, row) -> new ProductResponse(
            UUID.fromString(rs.getString("id")), rs.getString("sku"), rs.getString("barcode"), rs.getString("item_name"),
            rs.getString("item_description"), rs.getBigDecimal("unit_price"),
            rs.getObject("category_id") == null ? null : rs.getInt("category_id"), rs.getString("category"), rs.getBoolean("active"),
            rs.getTimestamp("added_at").toInstant(), rs.getTimestamp("modified_at").toInstant(), rs.getBigDecimal("cost"),
            rs.getString("unit_of_measure"), rs.getObject("min_stock_threshold") == null ? null : rs.getInt("min_stock_threshold"));

    public ProductRepository(JdbcClient jdbc, DatabaseDialect dialect, TenantContext tenant) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.tenant = tenant;
    }

    public Optional<ProductResponse> findById(UUID id) {
        return forBusiness(tenant.businessId(), id);
    }

    public Optional<ProductResponse> forBusiness(int business, UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id=:id AND p.business_id=:business").param("id", dialect.id(id)).param("business", business).query(MAPPER).optional();
    }

    public void lock(UUID id) {
        jdbc.sql("SELECT id FROM products WHERE id=:id AND business_id=:business" + dialect.lock())
                .param("id", dialect.id(id)).param("business", tenant.businessId()).query(String.class).optional();
    }

    public List<ProductResponse> findAll(String search, String category, boolean inactive, int limit, int offset) {
        return search(tenant.businessId(), search, category, null, null, null, null, null, null, inactive, limit, offset);
    }

    public List<ProductResponse> search(int business, String search, String category, String barcode, String make, String model, Integer year, String engine, String trim, boolean inactive, int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE p.business_id=:business AND (:inactive OR p.active)");
        Map<String, Object> params = new HashMap<>();
        params.put("business", business);
        params.put("inactive", inactive);
        if (search != null && !search.isBlank()) {
            sql.append(" AND (lower(p.item_name) LIKE lower(:search) OR lower(p.sku) LIKE lower(:search) OR lower(p.item_description) LIKE lower(:search))");
            params.put("search", "%" + search.trim() + "%");
        }
        filter(sql, params, "category", "c.name", category);
        filter(sql, params, "barcode", "p.barcode", barcode);
        if (make != null || model != null || year != null || engine != null || trim != null) {
            sql.append(" AND EXISTS(SELECT 1 FROM product_fitments pf JOIN vehicle_fitments f ON f.id=pf.fitment_id AND f.business_id=pf.business_id WHERE pf.product_id=p.id AND pf.business_id=p.business_id");
            filter(sql, params, "make", "f.make", make);
            filter(sql, params, "model", "f.model", model);
            if (year != null) {
                sql.append(" AND :year BETWEEN f.year_from AND f.year_to");
                params.put("year", year);
            }
            // A specific engine/trim fitment cannot promise a match when the customer omits that detail.
            if (engine == null || engine.isBlank()) sql.append(" AND f.engine IS NULL");
            else {
                sql.append(" AND (f.engine IS NULL OR lower(f.engine)=lower(:engine))");
                params.put("engine", engine.trim());
            }
            if (trim == null || trim.isBlank()) sql.append(" AND f.trim IS NULL");
            else {
                sql.append(" AND (f.trim IS NULL OR lower(f.trim)=lower(:trim))");
                params.put("trim", trim.trim());
            }
            sql.append(")");
        }
        sql.append(" ORDER BY p.item_name,p.id LIMIT :limit OFFSET :offset");
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(sql.toString()).params(params).query(MAPPER).list();
    }

    private void filter(StringBuilder sql, Map<String, Object> params, String name, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND lower(").append(column).append(")=lower(:").append(name).append(")");
            params.put(name, value.trim());
        }
    }

    public UUID create(ProductRequest r) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO products(id,business_id,sku,barcode,item_name,item_description,unit_price,category_id,cost,unit_of_measure,min_stock_threshold)
                VALUES(:id,:business,:sku,:barcode,:name,:description,:price,:category,:cost,:unit,:threshold)
                """).params(parameters(id, r)).update();
        return id;
    }

    public void update(UUID id, ProductRequest r) {
        jdbc.sql("""
                UPDATE products SET sku=:sku,barcode=:barcode,item_name=:name,item_description=:description,unit_price=:price,
                 category_id=:category,cost=:cost,unit_of_measure=:unit,min_stock_threshold=:threshold,
                 modified_at=%s WHERE id=:id AND business_id=:business
                """.formatted(dialect.now())).params(parameters(id, r)).update();
    }

    private Map<String, Object> parameters(UUID id, ProductRequest r) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", dialect.id(id));
        p.put("business", tenant.businessId());
        p.put("sku", r.sku().trim());
        p.put("barcode", r.barcode() == null || r.barcode().isBlank() ? null : r.barcode().trim());
        p.put("name", r.itemName().trim());
        p.put("description", r.itemDescription().trim());
        p.put("price", r.unitPrice());
        p.put("category", r.categoryId());
        p.put("cost", r.cost() == null ? java.math.BigDecimal.ZERO : r.cost());
        p.put("unit", r.unitOfMeasure() == null ? "EACH" : r.unitOfMeasure());
        p.put("threshold", r.minStockThreshold());
        return p;
    }

    public void deactivate(UUID id) {
        jdbc.sql("UPDATE products SET active=FALSE,modified_at=" + dialect.now() + " WHERE id=:id AND business_id=:business")
                .param("id", dialect.id(id)).param("business", tenant.businessId()).update();
    }

    public List<CategoryResponse> categories() {
        return jdbc.sql("SELECT id,name FROM categories WHERE business_id=:business ORDER BY name")
                .param("business", tenant.businessId()).query(CategoryResponse.class).list();
    }

    public CategoryResponse createCategory(String name) {
        Integer id = jdbc.sql("INSERT INTO categories(business_id,name) VALUES(:business,:name) RETURNING id")
                .param("business", tenant.businessId()).param("name", name.trim()).query(Integer.class).single();
        return new CategoryResponse(id, name.trim());
    }

    public boolean categoryExists(int id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM categories WHERE id=:id AND business_id=:business)")
                .param("id", id).param("business", tenant.businessId()).query(Boolean.class).single();
    }

    public Optional<CategoryResponse> category(int id) {
        return jdbc.sql("SELECT id,name FROM categories WHERE id=:id AND business_id=:business")
                .param("id", id).param("business", tenant.businessId()).query(CategoryResponse.class).optional();
    }

    public void updateCategory(int id, String name) {
        jdbc.sql("UPDATE categories SET name=:name WHERE id=:id AND business_id=:business")
                .param("name", name.trim()).param("id", id).param("business", tenant.businessId()).update();
    }

    public void deleteCategory(int id) {
        jdbc.sql("DELETE FROM categories WHERE id=:id AND business_id=:business")
                .param("id", id).param("business", tenant.businessId()).update();
    }
}
