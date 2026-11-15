package org.osyntra.osyntrainventorymanagement.models;

public class Employee extends User {
    private String employeeId;
    private String jobTitle;
    private EmployeeRole role;
    private Integer businessId = 1;
    private boolean operator;
    private Integer membershipId;
    private boolean allBranches = true;
    private String platformRole;

    public Integer getMembershipId() {
        return membershipId;
    }

    public void setMembershipId(Integer membershipId) {
        this.membershipId = membershipId;
    }

    public boolean hasAllBranches() {
        return allBranches;
    }

    public void setAllBranches(boolean allBranches) {
        this.allBranches = allBranches;
    }

    public String getPlatformRole() {
        return platformRole;
    }

    public void setPlatformRole(String platformRole) {
        this.platformRole = platformRole;
    }

    public Integer getBusinessId() {
        return businessId;
    }

    public void setBusinessId(Integer businessId) {
        this.businessId = businessId;
    }

    public boolean isOperator() {
        return operator;
    }

    public void setOperator(boolean operator) {
        this.operator = operator;
    }

    //CONSTRUCTORS
    public Employee() {
    }

    public Employee(
            String firstName,
            String lastName,
            String email,
            String passwordHash,
            String empId,
            String jobTitle,
            EmployeeRole role
    ) {
        super(firstName, lastName, email, passwordHash);
        this.employeeId = empId;
        this.jobTitle = jobTitle;
        this.role = role;
    }

    public Employee(Employee employee) {
        super(employee);
        this.employeeId = employee.employeeId;
        this.jobTitle = employee.jobTitle;
        this.role = employee.role;
    }

    //ACCESSORS
    public String getEmpId() {
        return employeeId;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public EmployeeRole getRole() {
        return role;
    }

    //MUTATORS
    public void setEmpId(String empId) {
        this.employeeId = empId;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public void setRole(EmployeeRole role) {
        this.role = role;
    }
}
