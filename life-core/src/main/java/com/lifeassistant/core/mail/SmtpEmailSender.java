package com.lifeassistant.core.mail;

import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 用 SMTP 发信（默认 QQ 邮箱 465 端口 SSL；阿里云服务器封了 25 端口）。发件人显示名"星络"。
 * 配置读 life.mail.*（来自 .env 的 LIFE_MAIL_*），没配地址、账号或密码时 ready() 为 false。
 */
@Slf4j
@Component
public class SmtpEmailSender implements EmailSender {

    private final String username;
    private final JavaMailSenderImpl mailer;

    public SmtpEmailSender(@Value("${life.mail.host:}") String host, @Value("${life.mail.port:465}") int port,
                           @Value("${life.mail.username:}") String username,
                           @Value("${life.mail.password:}") String password) {
        this.username = username;
        if (!StringUtils.hasText(host) || !StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            this.mailer = null;
            return;
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        // 465 是 SSL 直连，587 之类走 STARTTLS
        props.put(port == 465 ? "mail.smtp.ssl.enable" : "mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        this.mailer = sender;
    }

    @Override
    public boolean ready() {
        return mailer != null;
    }

    @Override
    public void send(String to, String subject, String text) {
        if (mailer == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "暂时不能发邮件，请联系管理员");
        }
        try {
            MimeMessage message = mailer.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(username, "星络", StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            mailer.send(message);
        } catch (Exception e) {
            log.warn("发邮件到 {} 失败：{}", mask(to), e.getMessage());
            throw new BizException(ErrorCode.BAD_REQUEST, "邮件没发出去，请稍后再试");
        }
    }

    /** 日志里的邮箱只留前两位，如 zj***@foxmail.com。 */
    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at < 0 ? "***" : email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }
}
