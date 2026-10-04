package com.couplefinance.identity.infrastructure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.couplefinance.identity.application.EmailDelivery;
import com.couplefinance.identity.application.EmailMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Fake adapter for local development and automated tests: keeps messages in memory, logs nothing. */
@Component
@Profile({"local", "test"})
public class InMemoryEmailDelivery implements EmailDelivery {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    /** Messages addressed to {@code recipient}, oldest first. */
    public List<EmailMessage> sentTo(String recipient) {
        return sent.stream().filter(message -> message.to().equals(recipient)).toList();
    }
}
