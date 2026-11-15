package org.osyntra.osyntrainventorymanagement.services;

import org.osyntra.osyntrainventorymanagement.dtos.user.*;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.*;
import org.osyntra.osyntrainventorymanagement.identity.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class EmployeeService {
    private final EmployeeRepository employees;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final TenantContext tenant;
    private final MembershipService memberships;
    private final IdentityService identity;
    private final MvpStore db;

    public EmployeeService(EmployeeRepository employees, PasswordEncoder passwords, AuditService audit, TenantContext tenant, MembershipService memberships, IdentityService identity, MvpStore db) {
        this.employees = employees;
        this.passwords = passwords;
        this.audit = audit;
        this.tenant = tenant;
        this.memberships = memberships;
        this.identity = identity;
        this.db = db;
    }

    @Transactional
    public EmployeeResponse create(CreateEmployeeRequest r) {
        tenant.requireRole("OWNER", "ADMINISTRATOR", "MANAGER");
        EmployeeRole role = r.role().canonical();
        memberships.grants(role.name(), tenant.actor().hasAllBranches(), tenant.actor().hasAllBranches() ? java.util.List.of() : tenant.allowedLocations());
        if (employees.findByEmail(r.email()).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Use an invitation to add an existing global identity");
        Employee e = new Employee(r.firstName().trim(), r.lastName().trim(), r.email().trim().toLowerCase(Locale.ROOT),
                passwords.encode(r.password()), null, r.jobTitle().trim(), role);
        e.setBusinessId(tenant.businessId());
        e.setAllBranches(tenant.actor().hasAllBranches());
        employees.save(e);
        if (!e.hasAllBranches()) for (int branch : tenant.allowedLocations())
            db.update("INSERT INTO membership_branches(membership_id,business_id,location_id) VALUES(?,?,?)", e.getMembershipId(), e.getBusinessId(), branch);
        identity.requestEmail(e.getEmail(), false);
        audit.log("EMPLOYEE_CREATED", "EMPLOYEE", e.getId(), null, e.getRole());
        return response(e);
    }

    public EmployeeResponse findByEmail(String email) {
        return response(employees.findByEmail(email).orElseThrow());
    }

    private EmployeeResponse response(Employee e) {
        return new EmployeeResponse(e.getId(), e.getEmpId(), e.getFirstName(), e.getLastName(), e.getEmail(), e.getJobTitle(), e.getRole(), e.getStatus(), e.getBusinessId(), e.isOperator());
    }
}
