package org.osyntra.osyntrainventorymanagement.payments;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class PaymentProviders {
    @Bean
    PaymentProvider cashProvider() {
        return new PaymentProvider() {
            public String id() {
                return "CASH";
            }

            public Outcome prepare(String ignored) {
                return Outcome.CONFIRMED;
            }
        };
    }

    @Bean
    PaymentProvider terminalProvider() {
        return new PaymentProvider() {
            public String id() {
                return "MANUAL_TERMINAL";
            }

            public Outcome prepare(String ignored) {
                return Outcome.UNKNOWN;
            }
        };
    }

    @Bean
    PaymentProvider simulatorProvider(Environment environment, @Value("${orvexa.payments.simulator-enabled:false}") boolean enabled) {
        return new PaymentProvider() {
            public String id() {
                return "SIMULATOR";
            }

            public Outcome prepare(String outcome) {
                if (!enabled || !environment.matchesProfiles("demo", "test", "dev"))
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Simulator is enabled only explicitly in development/demo/test");
                return outcome == null ? Outcome.CONFIRMED : Outcome.valueOf(outcome);
            }
        };
    }
}
