package com.lifeassistant.core.member;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepliesTest {

    private static final CreditService.Plan PLAN = new CreditService.Plan(
            new java.util.LinkedHashMap<>(java.util.Map.of("chat", 5, "plan", 60, "edit", 20, "compare", 15, "flight", 100)),
            300, 10, CreditService.parsePacks("6:600,30:3300"));

    private static CreditService.View view(long balance, int dailyLeft) {
        return new CreditService.View(balance, dailyLeft, 10, false, null, PLAN);
    }

    @Test
    void balanceCountsDailyGiftAndIgnoresOverdraft() {
        String text = AccountReplies.format(AccountQuestion.Intent.BALANCE, view(120, 7), List.of());
        assertThat(text).contains("可用 **127** 积分").contains("7/10");

        // 透支时可用按 0 算，还提醒怎么买
        String broke = AccountReplies.format(AccountQuestion.Intent.BALANCE, view(-30, 0), List.of());
        assertThat(broke).contains("可用 **0** 积分").contains("透支了 30").contains(AccountReplies.BUY_PATH);
    }

    @Test
    void ledgerListsRecentEntries() {
        CreditService.Entry spend = new CreditService.Entry(1, "SPEND", "chat", -5, -5, 115, null, null, OffsetDateTime.now());
        CreditService.Entry signup = new CreditService.Entry(2, "SIGNUP", null, 300, 0, 300, null, "新用户赠送", OffsetDateTime.now());
        String text = AccountReplies.format(AccountQuestion.Intent.LEDGER, view(115, 5), List.of(spend, signup));
        assertThat(text).contains("最近 2 笔").contains("使用·和星络对话（每条） -5").contains("注册赠送 +300").contains("可用 120");

        assertThat(AccountReplies.format(AccountQuestion.Intent.LEDGER, view(0, 10), List.of())).contains("还没有积分记录");
    }

    @Test
    void pricesAndBuyComeFromThePlan() {
        String prices = AccountReplies.format(AccountQuestion.Intent.PRICE, view(0, 10), List.of());
        assertThat(prices).contains("规划一个完整行程：60 积分").contains("和星络对话（每条）：5 积分").contains("¥6 = 600 积分");

        String buy = AccountReplies.format(AccountQuestion.Intent.BUY, view(0, 10), List.of());
        assertThat(buy).startsWith(AccountReplies.BUY_PATH).contains("¥30 = 3300 积分（多送 10%）");
    }
}
