package org.osyntra.osyntrainventorymanagement.config;

import org.osyntra.osyntrainventorymanagement.services.CommerceService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "osyntra.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiry {
    private final CommerceService commerce;

    public ReservationExpiry(CommerceService commerce) {
        this.commerce = commerce;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void expire() {
        commerce.expire();
    }
}
