package org.osyntra.osyntrainventorymanagement.identity;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

@Component
@ConditionalOnProperty(name = "orvexa.mail.smtp-enabled", havingValue = "true")
public class EmailDeliveryJob {
    private final EmailDispatcher dispatcher;

    public EmailDeliveryJob(EmailDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void deliver() {
        for (int i = 0; i < 20 && dispatcher.dispatchOne(); i++) {
        }
    }
}
