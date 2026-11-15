package org.osyntra.osyntrainventorymanagement.identity;

public interface EmailTransport {
    void send(String messageId, String recipient, String subject, String body);
}
