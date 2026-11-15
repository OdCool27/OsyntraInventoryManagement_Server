package org.osyntra.osyntrainventorymanagement.repositories;

import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.identity.SessionPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

@Repository
public class EmployeeRepository {
    private final JdbcClient jdbc;

    public EmployeeRepository(JdbcClient jdbc, DatabaseDialect dialect) {
        this.jdbc = jdbc;
    }

    public Optional<Employee> findByEmail(String email) {
        var users = jdbc.sql("SELECT * FROM users WHERE lower(email)=lower(:email)").param("email", email).query((rs, row) -> {
            Employee e = new Employee(rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"),
                    rs.getString("password_hash"), null, null, null);
            e.setId(rs.getInt("id"));
            e.setBusinessId(null);
            e.setStatus(UserStatus.valueOf(rs.getString("status")));
            e.setCreatedAt(DatabaseDialect.utcInstant(rs, "created_at"));
            e.setModifiedAt(DatabaseDialect.utcInstant(rs, "modified_at"));
            return e;
        }).optional();
        if (users.isEmpty()) return users;
        Employee employee = users.get();
        var staff = jdbc.sql("SELECT role FROM platform_staff WHERE user_id=:id AND active=1").param("id", employee.getId()).query(String.class).optional();
        if (staff.isPresent()) {
            employee.setOperator(true);
            employee.setPlatformRole(staff.get());
            return users;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Integer selected = null;
        boolean explicit = false;
        if (auth != null && auth.getPrincipal() instanceof SessionPrincipal principal && principal.userId() == employee.getId()) {
            selected = principal.membershipId();
            explicit = true;
            if (selected == null) return users;
        }
        String sql = "SELECT id,business_id,role,status,job_title,all_branches FROM memberships WHERE user_id=:user" + (explicit ? " AND id=:membership" : " ORDER BY id LIMIT 1");
        var query = jdbc.sql(sql).param("user", employee.getId());
        if (explicit) query.param("membership", selected);
        var membership = query.query((rs, row) -> {
            employee.setEmpId(Integer.toString(rs.getInt("id")));
            employee.setMembershipId(rs.getInt("id"));
            employee.setBusinessId(rs.getInt("business_id"));
            employee.setRole(EmployeeRole.valueOf(rs.getString("role")));
            employee.setJobTitle(rs.getString("job_title"));
            employee.setAllBranches(rs.getBoolean("all_branches"));
            if (!rs.getString("status").equals("ACTIVE")) employee.setStatus(UserStatus.DEACTIVATED);
            return employee;
        }).optional();
        return membership.isPresent() ? membership : users;
    }

    public void save(Employee employee) {
        Integer id = jdbc.sql("""
                        INSERT INTO users(first_name,last_name,email,password_hash,status,is_operator) VALUES(:first,:last,:email,:password,'ACTIVE',:operator) RETURNING id
                        """).param("first", employee.getFirstName()).param("last", employee.getLastName()).param("email", employee.getEmail())
                .param("password", employee.getPasswordHash()).param("operator", employee.isOperator()).query(Integer.class).single();
        employee.setId(id);
        if (employee.isOperator()) {
            jdbc.sql("INSERT INTO platform_staff(user_id,role) VALUES(:id,'SUPER_ADMIN')").param("id", id).update();
            employee.setPlatformRole("SUPER_ADMIN");
            employee.setBusinessId(null);
        } else {
            Integer membership = jdbc.sql("""
                            INSERT INTO memberships(user_id,business_id,role,job_title,all_branches) VALUES(:user,:business,:role,:title,:all) RETURNING id
                            """).param("user", id).param("business", employee.getBusinessId()).param("role", employee.getRole().canonical().name())
                    .param("title", employee.getJobTitle()).param("all", employee.hasAllBranches() ? 1 : 0).query(Integer.class).single();
            employee.setMembershipId(membership);
            employee.setEmpId(membership.toString());
            employee.setRole(employee.getRole().canonical());
        }
    }

    public boolean hasEmployees() {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM memberships)").query(Boolean.class).single();
    }

    public boolean hasOperators() {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform_staff)").query(Boolean.class).single();
    }

    public void lockBootstrap() { /* SQLite IMMEDIATE transactions serialize bootstrap. PostgreSQL remains guarded. */ }
}
