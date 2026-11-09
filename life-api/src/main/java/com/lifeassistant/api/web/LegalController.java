package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户协议、隐私政策页面要用的联系方式（不用登录）：默认就是发验证码的那个邮箱，
 * 想换成别的在 .env 里配 LIFE_CONTACT_EMAIL。不像邮箱地址时返回空，页面上不显示。
 */
@RestController
@RequestMapping("/api/v1/legal")
public class LegalController {

    private final String email;

    public LegalController(@Value("${life.contact-email:}") String contactEmail,
                           @Value("${life.mail.username:}") String mailUsername) {
        String e = contactEmail.isBlank() ? mailUsername.trim() : contactEmail.trim();
        this.email = e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? e : null;
    }

    /** 联系方式：email 为空表示没配。 */
    public record Contact(String email) {
    }

    /** GET /api/v1/legal/contact：用户联系我们（投诉、退款、个人信息请求）用的邮箱。 */
    @GetMapping("/contact")
    public ApiResponse<Contact> contact() {
        return ApiResponse.ok(new Contact(email));
    }
}
