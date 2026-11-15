package org.osyntra.osyntrainventorymanagement.identity;

import java.security.Principal;

public record SessionPrincipal(int userId, String email, String sessionId, Integer membershipId, String purpose,
                               boolean mfaVerified) implements Principal {
    @Override
    public String getName() {
        return email;
    }
}
