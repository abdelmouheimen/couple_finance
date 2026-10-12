package com.couplefinance.identity.infrastructure;

import com.couplefinance.identity.application.EmailDelivery;
import com.couplefinance.identity.application.EmailMessage;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Production adapter: plain-text mail through an SMTP relay (the Postfix relay of the production stack). Callers
 * already send outside any database transaction (CLAUDE.md 6.3); failures propagate as runtime exceptions and are
 * handled, content-free, by the caller.
 */
final class SmtpEmailDelivery implements EmailDelivery {

    private final JavaMailSender sender;
    private final String from;

    SmtpEmailDelivery(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        sender.send(mail);
    }
}
