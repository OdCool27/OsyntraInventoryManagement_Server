package org.osyntra.osyntrainventorymanagement.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.*;
import jakarta.mail.internet.MimeMessage;

@Component
@ConditionalOnProperty(name = "orvexa.mail.smtp-enabled", havingValue = "true")
public class SmtpTransport implements EmailTransport {
    private final JavaMailSender sender;
    private final String from;

    public SmtpTransport(JavaMailSender sender, @Value("${ORVEXA_MAIL_FROM:}") String from) {
        if (from.isBlank())
            throw new IllegalStateException("ORVEXA_MAIL_FROM and spring.mail provider configuration are required for SMTP");
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(String id, String recipient, String subject, String body) {
        try {
            MimeMessage message = sender.createMimeMessage();
            message.setFrom(from);
            message.setRecipients(jakarta.mail.Message.RecipientType.TO, recipient);
            message.setSubject(subject, "UTF-8");
            message.setText(body, "UTF-8");
            message.setHeader("Message-ID", "<" + id + "@orvexa.local>");
            sender.send(message);
        } catch (jakarta.mail.MessagingException exception) {
            throw new IllegalStateException("Email delivery failed");
        }
    }
}
