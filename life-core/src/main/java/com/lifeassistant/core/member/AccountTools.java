package com.lifeassistant.core.member;

import com.lifeassistant.core.auth.ToolContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 用户问自己账号的事（还有多少积分、今天送的还剩多少、积分花在哪、各项多少积分、怎么买、会员到期）时用。
 * 只读，返回给模型的是整理好的中文，数字全部来自数据库，不让模型自己算或编。
 */
@Component
@RequiredArgsConstructor
public class AccountTools {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    /** 流水类型的中文 */
    private static final Map<String, String> KINDS = Map.of("SIGNUP", "注册赠送", "PURCHASE", "购买", "SPEND", "使用",
            "RETURN", "失败退回", "ADMIN", "客服调整", "REFUND", "退款扣回");

    private final CreditService credits;

    @Tool(description = """
            查当前用户自己的积分和账号：可用积分、今天赠送的还剩多少、最近 10 笔积分记录、各项功能多少积分、积分包价格、
            老会员到期时间。用户问"我还有多少积分""积分花哪了""为什么扣了积分""怎么买积分""对话多少积分""会员什么时候到期"时调用，
            按返回的数字回答，不要自己估算。""")
    public String myCredits(ToolContext toolContext) {
        long userId = ToolContexts.user(toolContext).userId();
        CreditService.View v = credits.view(userId);
        StringBuilder out = new StringBuilder();
        if (v.member()) {
            out.append("老会员：").append(v.memberExpiresAt() == null ? "有效" : v.memberExpiresAt().atZoneSameInstant(ZONE).format(DATE) + "到期")
                    .append("，到期前所有功能不扣积分（每天对话有上限防刷）。\n");
        }
        out.append("可用积分：").append(Math.max(0, v.balance()) + v.dailyLeft()).append("（买的和送的 ").append(Math.max(0, v.balance()))
                .append("，今天赠送的还剩 ").append(v.dailyLeft()).append("/").append(v.daily()).append("，当天有效、不累积，扣的时候先扣它）\n");
        if (v.balance() < 0) {
            out.append("之前透支了 ").append(-v.balance()).append(" 积分（行程做完才扣、当时余额不够），需要买积分补上才能继续用。\n");
        }
        out.append("各项价格：");
        List<String> prices = CreditService.FEATURES.entrySet().stream()
                .map(e -> e.getValue() + " " + v.plan().prices().getOrDefault(e.getKey(), 0) + " 积分").toList();
        out.append(String.join("；", prices)).append("；语音转文字、查看景点详情免费。\n");
        out.append("积分包：");
        out.append(String.join("；", v.plan().packs().stream().map(p -> "¥" + p.yuan().stripTrailingZeros().toPlainString()
                + " = " + p.credits() + " 积分" + (p.bonusPercent() > 0 ? "（多送 " + p.bonusPercent() + "%）" : "")).toList()));
        out.append("。1 元 = 100 积分，积分长期有效，不能提现、不能转让。\n");
        out.append("怎么买：点左下角头像（手机在「我的」）→「购买积分」，用支付宝付款，付完马上到账。\n");
        List<CreditService.Entry> recent = credits.ledger(userId, 10);
        if (recent.isEmpty()) {
            out.append("最近积分记录：还没有。\n");
        } else {
            out.append("最近积分记录（新的在前）：\n");
            for (CreditService.Entry e : recent) {
                String what = e.feature() != null ? CreditService.FEATURES.getOrDefault(e.feature(), e.feature()) : "";
                out.append("- ").append(e.createdAt() == null ? "" : e.createdAt().atZoneSameInstant(ZONE).format(TIME)).append(" ")
                        .append(KINDS.getOrDefault(e.kind(), e.kind())).append(what.isEmpty() ? "" : "·" + what).append(" ")
                        .append(e.amount() > 0 ? "+" : "").append(e.amount())
                        .append(e.fromDaily() != 0 ? "（其中每日赠送抵 " + Math.abs(e.fromDaily()) + "）" : "")
                        .append(e.note() == null || e.note().isBlank() ? "" : "，" + e.note()).append('\n');
            }
        }
        out.append("完整记录在「我的积分」页。");
        return out.toString();
    }
}
