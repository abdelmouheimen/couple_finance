package com.couplefinance.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.couplefinance.identity.application.EmailDelivery;
import com.couplefinance.identity.application.EmailMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Production email wiring: SMTP relay when configured, content-free drop otherwise. */
class ProductionEmailDeliveryConfigurationTest {

    private final ProductionEmailDeliveryConfiguration configuration = new ProductionEmailDeliveryConfiguration();

    @Test
    void with_an_smtp_relay_messages_are_sent_from_the_configured_address() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailDelivery delivery = configuration.emailDelivery(providerOf(sender), "no-reply@example.test");

        delivery.send(new EmailMessage("alice@example.test", "Subject", "Body"));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("no-reply@example.test");
        assertThat(sent.getValue().getTo()).containsExactly("alice@example.test");
        assertThat(sent.getValue().getSubject()).isEqualTo("Subject");
        assertThat(sent.getValue().getText()).isEqualTo("Body");
    }

    @Test
    void an_smtp_relay_without_a_sender_address_refuses_to_start() {
        JavaMailSender sender = mock(JavaMailSender.class);

        assertThatThrownBy(() -> configuration.emailDelivery(providerOf(sender), " "))
                .isInstanceOf(IllegalStateException.class);
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void without_an_smtp_relay_messages_are_dropped_without_failing() {
        EmailDelivery delivery = configuration.emailDelivery(
                new StaticListableBeanFactory().getBeanProvider(JavaMailSender.class), "");

        assertThatCode(() -> delivery.send(new EmailMessage("alice@example.test", "Subject", "Body")))
                .doesNotThrowAnyException();
    }

    private static org.springframework.beans.factory.ObjectProvider<JavaMailSender> providerOf(JavaMailSender sender) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("mailSender", sender);
        return beans.getBeanProvider(JavaMailSender.class);
    }
}
