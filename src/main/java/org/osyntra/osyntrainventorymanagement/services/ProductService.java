package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.product.*;
import org.osyntra.osyntrainventorymanagement.repositories.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class ProductService {
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final TenantContext tenant;
    private final org.osyntra.osyntrainventorymanagement.repositories.MvpStore db;

    public ProductService(ProductRepository productRepository, AuditService auditService, TenantContext tenant, org.osyntra.osyntrainventorymanagement.repositories.MvpStore db) {
        this.productRepository = productRepository;
        this.auditService = auditService;
        this.tenant = tenant;
        this.db = db;
    }

    public ProductResponse findById(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    public ProductResponse lockActive(UUID id) {
        productRepository.lock(id);
        ProductResponse product = findById(id);
        if (!product.active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is inactive");
        }
        return product;
    }

    public List<ProductResponse> findAll(String search, String category, boolean includeInactive, int limit, int offset) {
        return productRepository.findAll(search, category, includeInactive, limit, offset);
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        tenant.requireRole("OWNER", "MANAGER");
        validateCategory(request.categoryId());
        if ("VARIANT".equals(request.productKind())) {
            if (request.parentId() == null || request.variantKey() == null || request.variantKey().isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Variants require a parent and variant key");
            db.one("SELECT id FROM products WHERE id=? AND business_id=? AND product_kind='PARENT' AND active=1", request.parentId().toString(), tenant.businessId());
        } else if (request.parentId() != null || request.variantKey() != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only variants can specify a parent/key");
        UUID id = productRepository.create(request);
        ProductResponse product = findById(id);
        auditService.log("PRODUCT_CREATED", "PRODUCT", id, null, product);
        return product;
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        tenant.requireRole("OWNER", "MANAGER");
        ProductResponse previous = lockActive(id);
        validateCategory(request.categoryId());
        if (request.unitOfMeasure() != null && !request.unitOfMeasure().equals(previous.unitOfMeasure()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Base units are immutable; add explicit conversion units");
        if (request.productKind() != null && !request.productKind().equals(previous.productKind()) ||
                request.parentId() != null && !Objects.equals(request.parentId(), previous.parentId()) ||
                request.variantKey() != null && !Objects.equals(request.variantKey(), previous.variantKey()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Variant identity is immutable");
        productRepository.update(id, request);
        db.update("UPDATE inventory SET reorder_level=coalesce((SELECT min_stock_threshold FROM products WHERE id=?),(SELECT default_threshold FROM businesses WHERE id=?)) WHERE product_id=? AND business_id=?", id.toString(), tenant.businessId(), id.toString(), tenant.businessId());
        ProductResponse product = findById(id);
        auditService.log("PRODUCT_UPDATED", "PRODUCT", id, previous, product);
        return product;
    }

    @Transactional
    public void deactivate(UUID id) {
        tenant.requireRole("OWNER", "MANAGER");
        ProductResponse previous = lockActive(id);
        if (previous.productKind().equals("PARENT") && db.number("SELECT count(*) FROM products WHERE business_id=? AND parent_id=? AND active=1", tenant.businessId(), id.toString()) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Archive active variants before their parent");
        productRepository.deactivate(id);
        auditService.log("PRODUCT_DEACTIVATED", "PRODUCT", id, previous.active(), false);
    }

    public List<CategoryResponse> categories() {
        return productRepository.categories();
    }

    @Transactional
    public CategoryResponse createCategory(CategoryRequest request) {
        tenant.requireRole("OWNER", "MANAGER");
        CategoryResponse category = productRepository.createCategory(request.name());
        auditService.log("CATEGORY_CREATED", "CATEGORY", category.id(), null, category.name());
        return category;
    }

    private void validateCategory(Integer id) {
        if (id != null && !productRepository.categoryExists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
        }
    }

    public CategoryResponse category(int id) {
        return productRepository.category(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
    }

    @Transactional
    public CategoryResponse updateCategory(int id, CategoryRequest request) {
        tenant.requireRole("OWNER", "MANAGER");
        var old = category(id);
        productRepository.updateCategory(id, request.name());
        auditService.log("CATEGORY_UPDATED", "CATEGORY", id, old.name(), request.name());
        return category(id);
    }

    @Transactional
    public void deleteCategory(int id) {
        tenant.requireRole("OWNER", "MANAGER");
        var old = category(id);
        productRepository.deleteCategory(id);
        auditService.log("CATEGORY_DELETED", "CATEGORY", id, old.name(), null);
    }
}
