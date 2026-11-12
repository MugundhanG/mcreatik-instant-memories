package com.mcreatik.gallery.mail;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Gmail / Google Workspace SMTP (smtp.gmail.com:587, STARTTLS) with an app password for connect@mcreatik.com.
 * Configured only through the server's environment (MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM); without
 * MAIL_HOST nothing is sent and a warning is logged.
 */
@Component
public class SmtpMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    private final JavaMailSenderImpl sender;
    private final String from;

    public SmtpMailer(@Value("${gallery.mail.host:}") String host, @Value("${gallery.mail.port:587}") int port,
                      @Value("${gallery.mail.username:}") String username, @Value("${gallery.mail.password:}") String password,
                      @Value("${gallery.mail.from:McreatiK <connect@mcreatik.com>}") String from) {
        this.from = from;
        if (!StringUtils.hasText(host)) {
            this.sender = null;
            return;
        }
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(host);
        s.setPort(port);
        s.setUsername(username);
        s.setPassword(password);
        s.setDefaultEncoding("UTF-8");
        Properties p = s.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        this.sender = s;
    }

    @Override
    public boolean send(String to, String subject, String text, String html) {
        if (sender == null) {
            log.warn("Email is not set up (MAIL_HOST is empty): could not send \"{}\"", subject);
            return false;
        }
        try {
            var m = sender.createMimeMessage();
            var h = new org.springframework.mail.javamail.MimeMessageHelper(m, true, "UTF-8");
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(text, html);
            sender.send(m);
            return true;
        } catch (Exception e) {
            log.error("Could not send \"{}\": {}", subject, e.toString());
            return false;
        }
    }

    @Override
    public boolean send(String to, String subject, String body) {
        if (sender == null) {
            log.warn("Email is not set up (MAIL_HOST is empty): could not send \"{}\"", subject);
            return false;
        }
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            sender.send(m);
            return true;
        } catch (RuntimeException e) {
            log.error("Could not send \"{}\": {}", subject, e.toString());
            return false;
        }
    }
}
