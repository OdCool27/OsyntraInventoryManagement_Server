package org.osyntra.osyntrainventorymanagement.repositories;

import org.osyntra.osyntrainventorymanagement.models.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class EmployeeRepository {
    private final JdbcClient jdbcClient;
    private final DatabaseDialect dialect;

    public EmployeeRepository(JdbcClient jdbcClient, DatabaseDialect dialect) {
        this.jdbcClient = jdbcClient;
        this.dialect = dialect;
    }

    public Optional<Employee> findByEmail(String email) {
        return jdbcClient.sql("""
                SELECT u.*, e.employee_id, e.job_title, e.employee_role, e.business_id
                FROM users u JOIN employees e ON e.user_id = u.id WHERE lower(u.email) = lower(:email)
                """).param("email", email).query((rs, row) -> {
            Employee employee = new Employee(rs.getString("first_name"), rs.getString("last_name"),
                    rs.getString("email"), rs.getString("password_hash"), rs.getString("employee_id"),
                    rs.getString("job_title"), EmployeeRole.valueOf(rs.getString("employee_role")));
            employee.setId(rs.getInt("id"));
            employee.setBusinessId(rs.getObject("business_id") == null ? null : rs.getInt("business_id"));
            employee.setOperator(rs.getBoolean("is_operator"));
            employee.setStatus(UserStatus.valueOf(rs.getString("status")));
            employee.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            employee.setModifiedAt(rs.getTimestamp("modified_at").toInstant());
            return employee;
        }).optional();
    }

    public void save(Employee employee) {
        Integer id = jdbcClient.sql("""
                        INSERT INTO users(first_name, last_name, email, password_hash, status, is_operator)
                        VALUES (:firstName, :lastName, :email, :passwordHash, 'ACTIVE', :operator) RETURNING id
                        """).param("firstName", employee.getFirstName()).param("lastName", employee.getLastName())
                .param("email", employee.getEmail()).param("passwordHash", employee.getPasswordHash())
                .param("operator", employee.isOperator())
                .query(Integer.class).single();
        Integer employeeId = jdbcClient.sql("""
                        INSERT INTO employees(user_id, job_title, employee_role, business_id)
                        VALUES (:id, :jobTitle, :role, :business) RETURNING employee_id
                        """).param("id", id).param("jobTitle", employee.getJobTitle())
                .param("role", employee.getRole().name()).param("business", employee.getBusinessId()).query(Integer.class).single();
        employee.setId(id);
        employee.setEmpId(employeeId.toString());
    }

    public boolean hasEmployees() {
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM employees)").query(Boolean.class).single();
    }

    public boolean hasOperators() {
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM users WHERE is_operator=1)").query(Boolean.class).single();
    }

    public void lockBootstrap() {
        if (dialect.isPostgres()) {
            jdbcClient.sql("LOCK TABLE employees IN EXCLUSIVE MODE").update();
        }
        // SQLite's IMMEDIATE transaction already serializes bootstrap writers.
    }
}
