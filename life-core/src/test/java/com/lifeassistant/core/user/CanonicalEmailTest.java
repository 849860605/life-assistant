package com.lifeassistant.core.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalEmailTest {

    @Test
    void stripsPlusTagForAllDomainsAndDotsForGmail() {
        assertThat(UserService.canonicalEmail("Dave+promo@Example.com")).isEqualTo("dave@example.com");
        assertThat(UserService.canonicalEmail("d.a.v.e@example.com")).isEqualTo("d.a.v.e@example.com");
        assertThat(UserService.canonicalEmail("D.a.ve+1@gmail.com")).isEqualTo("dave@gmail.com");
        assertThat(UserService.canonicalEmail("dave@googlemail.com")).isEqualTo("dave@gmail.com");
        assertThat(UserService.canonicalEmail("12345+a+b@qq.com")).isEqualTo("12345@qq.com");
        assertThat(UserService.canonicalEmail(" plain@qq.com ")).isEqualTo("plain@qq.com");
        assertThat(UserService.canonicalEmail(null)).isEmpty();
    }
}
