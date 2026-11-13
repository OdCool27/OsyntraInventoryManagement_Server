package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.FitmentRequest;
import org.osyntra.osyntrainventorymanagement.repositories.MvpStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class FitmentService {
    private final MvpStore db;
    private final TenantContext tenant;
    private final ProductService products;
    private final AuditService audit;

    public FitmentService(MvpStore db, TenantContext tenant, ProductService products, AuditService audit) {
        this.db = db;
        this.tenant = tenant;
        this.products = products;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> create(FitmentRequest r) {
        tenant.requireRole("OWNER", "MANAGER");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO vehicle_fitments(id,business_id,make,model,year_from,year_to,engine,trim,source,verified_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, tenant.businessId(), r.make().trim(), r.model().trim(), r.yearFrom(), r.yearTo(), blank(r.engine()), blank(r.trim()), r.source().trim(), tenant.actor().getId());
        audit.log("FITMENT_VERIFIED", "FITMENT", id, null, r);
        return db.one("SELECT * FROM vehicle_fitments WHERE id=? AND business_id=?", id, tenant.businessId());
    }

    public List<Map<String, Object>> list(UUID product) {
        products.findById(product);
        return db.rows("SELECT f.* FROM vehicle_fitments f JOIN product_fitments pf ON pf.fitment_id=f.id AND pf.business_id=f.business_id WHERE pf.product_id=? AND pf.business_id=?", product.toString(), tenant.businessId());
    }

    @Transactional
    public void link(UUID product, UUID fitment, boolean remove) {
        tenant.requireRole("OWNER", "MANAGER");
        products.lockActive(product);
        db.one("SELECT id FROM vehicle_fitments WHERE id=? AND business_id=?", fitment.toString(), tenant.businessId());
        if (remove)
            db.update("DELETE FROM product_fitments WHERE product_id=? AND fitment_id=? AND business_id=?", product.toString(), fitment.toString(), tenant.businessId());
        else
            db.update("INSERT INTO product_fitments(product_id,fitment_id,business_id) VALUES(?,?,?) ON CONFLICT DO NOTHING", product.toString(), fitment.toString(), tenant.businessId());
        audit.log(remove ? "FITMENT_UNLINKED" : "FITMENT_LINKED", "PRODUCT", product, null, fitment);
    }

    private String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
