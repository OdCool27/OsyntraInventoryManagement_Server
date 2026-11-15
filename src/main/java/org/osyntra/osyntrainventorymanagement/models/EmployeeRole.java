package org.osyntra.osyntrainventorymanagement.models;

public enum EmployeeRole {
    OWNER, ADMINISTRATOR, MANAGER, INVENTORY_CLERK, CASHIER,
    /**
     * Legacy input alias; memberships and API outputs use INVENTORY_CLERK.
     */
    @Deprecated CLERK;

    public EmployeeRole canonical() {
        return this == CLERK ? INVENTORY_CLERK : this;
    }
}
