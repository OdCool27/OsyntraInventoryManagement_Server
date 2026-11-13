package org.osyntra.osyntrainventorymanagement.config;

import jakarta.validation.Validator;
import org.osyntra.osyntrainventorymanagement.dtos.user.CreateEmployeeRequest;
import org.osyntra.osyntrainventorymanagement.models.*;
import org.osyntra.osyntrainventorymanagement.repositories.EmployeeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Component
@Order(0)
@ConditionalOnProperty(name = "osyntra.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class AdminInitializer implements ApplicationRunner {
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final String email;
    private final String password;

    public AdminInitializer(EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder, Validator validator,
                            @Value("${OSYNTRA_OPERATOR_EMAIL:${SENTRA_ADMIN_EMAIL:}}") String email,
                            @Value("${OSYNTRA_OPERATOR_PASSWORD:${SENTRA_ADMIN_PASSWORD:}}") String password) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) {
            return;
        }
        employeeRepository.lockBootstrap();
        if (employeeRepository.hasOperators() || employeeRepository.findByEmail(email.trim()).isPresent()) {
            return;
        }
        CreateEmployeeRequest request = new CreateEmployeeRequest("System", "Owner", email, password,
                "Owner", EmployeeRole.OWNER);
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalStateException("Bootstrap requires a valid email and a password of 12-72 characters");
        }
        Employee employee = new Employee(request.firstName(), request.lastName(), email.trim().toLowerCase(Locale.ROOT),
                passwordEncoder.encode(password), null, request.jobTitle(), request.role());
        employee.setBusinessId(null);
        employee.setOperator(true);
        employeeRepository.save(employee);
    }
}
