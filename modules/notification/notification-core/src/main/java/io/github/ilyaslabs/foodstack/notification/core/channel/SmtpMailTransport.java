package io.github.ilyaslabs.foodstack.notification.core.channel;

import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Email over SMTP ({@code SMTP_HOST}, {@code SMTP_PORT}, ...; Mailpit on a local install). */
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender sender;

    private final InternetAddress from;

    public SmtpMailTransport(JavaMailSender sender, String fromAddress, String fromName) {
        this.sender = sender;
        try {
            this.from = new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name());
        }
        catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void send(String to, String subject, String html, String text) {
        try {
            MimeMessage message = this.sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(this.from);
            helper.setTo(new InternetAddress(to, true));
            helper.setSubject(subject);
            helper.setText(text, html);
            this.sender.send(message);
        }
        catch (AddressException ex) {
            throw new MailFailure("Invalid address", true, ex);
        }
        catch (MailSendException ex) {
            boolean rejected = ex.getMessageExceptions().length > 0
                    && ex.getMessageExceptions()[0] instanceof SendFailedException failed && failed.getValidUnsentAddresses() == null
                    && failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0;
            throw new MailFailure(ex.getMessage(), rejected, ex);
        }
        catch (MailException | MessagingException ex) {
            throw new MailFailure(ex.getMessage(), false, ex);
        }
    }
}
