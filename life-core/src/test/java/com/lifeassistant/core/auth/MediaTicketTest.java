package com.lifeassistant.core.auth;

import com.lifeassistant.core.config.LifeProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTicketTest {

    private final MediaTicket tickets = new MediaTicket(new LifeProperties());

    @Test
    void acceptsOwnTicketAndRejectsTamperedOrForeignOnes() {
        String ticket = tickets.issue(42, 3).ticket();

        assertThat(tickets.verify(ticket)).contains(new MediaTicket.Claims(42, 3));
        // 改了用户 id 或版本，签名就对不上
        assertThat(tickets.verify("43" + ticket.substring(2))).isEmpty();
        assertThat(tickets.verify(ticket.replaceFirst("^42\\.3\\.", "42.4."))).isEmpty();
        // 另一个实例（不同密钥）签的不认
        assertThat(new MediaTicket(new LifeProperties()).verify(ticket)).isEmpty();
        assertThat(tickets.verify("42.0.99999999999.x")).isEmpty();
        assertThat(tickets.verify(null)).isEmpty();
        // 已过期、旧格式（没有版本）的都不认
        assertThat(tickets.verify("42.0.1.abc")).isEmpty();
        assertThat(tickets.verify("42.99999999999.abc")).isEmpty();
    }

    @Test
    void ticketLivesFourHours() {
        assertThat(MediaTicket.TTL.toHours()).isEqualTo(4);
    }
}
