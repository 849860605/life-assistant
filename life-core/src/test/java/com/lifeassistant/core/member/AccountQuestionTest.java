package com.lifeassistant.core.member;

import com.lifeassistant.core.member.AccountQuestion.Intent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AccountQuestionTest {

    @ParameterizedTest
    @CsvSource({
            "我还有多少积分, BALANCE",
            "我还有多少积分？, BALANCE",
            "积分还剩多少, BALANCE",
            "积分还剩多少呢, BALANCE",
            "余额多少, BALANCE",
            "我的积分, BALANCE",
            "查一下积分, BALANCE",
            "多少积分了, BALANCE",
            "为什么扣了我积分, LEDGER",
            "为什么扣了我的积分？, LEDGER",
            "怎么扣了这么多积分, LEDGER",
            "积分花哪了, LEDGER",
            "积分都去哪了, LEDGER",
            "积分记录, LEDGER",
            "怎么买积分, BUY",
            "怎么充值, BUY",
            "在哪充值, BUY",
            "积分怎么买, BUY",
            "我想买积分, BUY",
            "积分不够了怎么办, BUY",
            "规划一次多少积分, PRICE",
            "对话多少积分, PRICE",
            "发一条消息要多少积分, PRICE",
            "比价一次扣多少积分, PRICE",
            "积分怎么算, PRICE",
            "请问 对话多少积分？, PRICE",
    })
    void recognizesPureAccountQuestions(String text, Intent intent) {
        assertThat(AccountQuestion.match(text)).isEqualTo(intent);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "我还有多少积分，帮我规划长沙三天",
            "查一下积分然后帮我订酒店",
            "帮我查下长沙天气",
            "规划长沙三天行程",
            "规划长沙三天多少积分",
            "怎么买机票",
            "怎么买",
            "怎么算",
            "订一张去西安的机票",
            "酒店多少钱",
            "你好",
            "",
            "   ",
            "我的积分还剩多少啊我的积分还剩多少啊我的积分",
    })
    void leavesEverythingElseToTheModel(String text) {
        assertThat(AccountQuestion.match(text)).isNull();
    }
}
