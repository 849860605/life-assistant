package com.lifeassistant.core.mail;

/** 发一封纯文本邮件；测试里换成假的，收集验证码。 */
public interface EmailSender {

    /** 有没有配好发信邮箱，没配时不能发验证码。 */
    boolean ready();

    /** 发一封纯文本邮件，失败抛 BizException。 */
    void send(String to, String subject, String text);
}
