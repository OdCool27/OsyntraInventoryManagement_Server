package org.osyntra.osyntrainventorymanagement.identity;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

@Component
public class EmailDispatcher {
    private final MailOutbox outbox;
    private final ObjectProvider<EmailTransport> transports;

    public EmailDispatcher(MailOutbox outbox, ObjectProvider<EmailTransport> transports) {
        this.outbox = outbox;
        this.transports = transports;
    }

    // The claim is committed by a separate bean before any external network side effect.
    public boolean dispatchOne() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Email delivery must run outside a database transaction");
        EmailTransport transport = transports.getIfAvailable();
        if (transport == null) return false;
        var message = outbox.claim();
        if (message == null) return false;
        String id = message.get("id").toString();
        try {
            transport.send(id, message.get("recipient").toString(), message.get("subject").toString(), message.get("body").toString());
            outbox.delivered(id);
        } catch (RuntimeException ignored) {
            outbox.failed(id);
        }
        return true;
    }
}
