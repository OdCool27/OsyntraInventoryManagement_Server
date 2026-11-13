-- Preserve existing accounts and hashes while adopting the MVP role vocabulary.
ALTER TABLE employees DROP CONSTRAINT employees_employee_role_check;
UPDATE employees SET employee_role = CASE employee_role
    WHEN 'ADMIN' THEN 'OWNER'
    WHEN 'SUPERVISOR' THEN 'CLERK'
    WHEN 'STAFF' THEN 'CLERK'
    ELSE employee_role END;
ALTER TABLE employees ADD CONSTRAINT employees_employee_role_check
    CHECK (employee_role IN ('OWNER', 'MANAGER', 'CLERK', 'CASHIER'));
