package com.lifeassistant.core.member;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 单纯问积分（见 AccountQuestion）时的模板回答：数字全部来自数据库，不经过模型、不扣积分，积分为 0 时也能问。
 * 每一类只答问到的那部分，简短直接；算法和 AccountTools 一致（可用 = 余额（负数按 0）+ 今天赠送剩下的）。
 */
@Component
@RequiredArgsConstructor
public class AccountReplies {

    /** 最近几笔记录 */
    static final int RECENT = 5;
    static final String BUY_PATH = "点左下角头像（手机在「我的」）→「购买积分」";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final Map<String, String> KINDS = Map.of("SIGNUP", "注册赠送", "PURCHASE", "购买", "SPEND", "使用",
            "RETURN", "失败退回", "ADMIN", "客服调整", "REFUND", "退款扣回");

    private final CreditService credits;

    /** 按问的类别查好数字、拼成回答。 */
    public String answer(long userId, AccountQuestion.Intent intent) {
        CreditService.View view = credits.view(userId);
        List<CreditService.Entry> recent = intent == AccountQuestion.Intent.LEDGER ? credits.ledger(userId, RECENT) : List.of();
        return format(intent, view, recent);
    }

    /** 拼回答（纯函数，单元测试用）。 */
    static String format(AccountQuestion.Intent intent, CreditService.View v, List<CreditService.Entry> recent) {
        return switch (intent) {
            case BALANCE -> balance(v);
            case LEDGER -> ledger(v, recent);
            case PRICE -> prices(v);
            case BUY -> buy(v);
        };
    }

    /** 可用积分（买的和送的 + 今天赠送剩下的）。 */
    static long available(CreditService.View v) {
        return Math.max(0, v.balance()) + v.dailyLeft();
    }

    private static String balance(CreditService.View v) {
        StringBuilder out = new StringBuilder("你现在可用 **").append(available(v)).append("** 积分（买的和送的 ")
                .append(Math.max(0, v.balance())).append("，今天赠送的还剩 ").append(v.dailyLeft()).append("/").append(v.daily())
                .append("，当天有效）。");
        out.append(memberLine(v));
        if (v.balance() < 0) {
            out.append("\n\n之前规划行程透支了 ").append(-v.balance()).append(" 积分，买一点补上就能继续用。");
        }
        int chat = v.plan().prices().getOrDefault(CreditService.CHAT, 0);
        if (!v.member() && chat > 0 && available(v) < chat) {
            out.append("\n\n现在不够发一条消息（").append(chat).append(" 积分），").append(BUY_PATH).append("就能继续用。");
        }
        return out.toString();
    }

    private static String ledger(CreditService.View v, List<CreditService.Entry> recent) {
        if (recent.isEmpty()) {
            return "你还没有积分记录。现在可用 " + available(v) + " 积分。";
        }
        StringBuilder out = new StringBuilder("每用一次功能按价格扣积分，先扣今天赠送的；没做成的会自动退回。最近 ")
                .append(recent.size()).append(" 笔：\n");
        for (CreditService.Entry e : recent) {
            String what = e.feature() == null ? "" : CreditService.FEATURES.getOrDefault(e.feature(), e.feature());
            out.append("\n- ").append(e.createdAt() == null ? "" : e.createdAt().atZoneSameInstant(ZONE).format(TIME) + " ")
                    .append(KINDS.getOrDefault(e.kind(), e.kind())).append(what.isEmpty() ? "" : "·" + what).append(" ")
                    .append(e.amount() > 0 ? "+" : "").append(e.amount())
                    .append(e.fromDaily() != 0 ? "（其中每日赠送抵 " + Math.abs(e.fromDaily()) + "）" : "");
        }
        out.append("\n\n现在可用 ").append(available(v)).append(" 积分，完整记录在「我的积分」页。");
        return out.toString();
    }

    private static String prices(CreditService.View v) {
        StringBuilder out = new StringBuilder("各项价格：\n");
        CreditService.FEATURES.forEach((k, name) -> out.append("\n- ").append(name).append("：")
                .append(v.plan().prices().getOrDefault(k, 0)).append(" 积分"));
        out.append("\n\n语音转文字、看景点详情、问积分余额和记录不扣积分。规划行程是做完才扣。");
        out.append(memberLine(v));
        out.append("\n\n").append(packs(v));
        return out.toString();
    }

    private static String buy(CreditService.View v) {
        return BUY_PATH + "，选一个积分包用支付宝付款，付完马上到账。\n\n" + packs(v) + "\n\n你现在可用 " + available(v) + " 积分。";
    }

    /** 积分包价格一行。 */
    private static String packs(CreditService.View v) {
        if (v.plan().packs().isEmpty()) {
            return "1 元 = 100 积分，积分长期有效。";
        }
        return "积分包：" + String.join("；", v.plan().packs().stream().map(p -> "¥" + p.yuan().stripTrailingZeros().toPlainString()
                + " = " + p.credits() + " 积分" + (p.bonusPercent() > 0 ? "（多送 " + p.bonusPercent() + "%）" : "")).toList())
                + "。1 元 = 100 积分，积分长期有效。";
    }

    /** 老会员到期前不扣积分的说明；不是会员时为空。 */
    private static String memberLine(CreditService.View v) {
        if (!v.member()) {
            return "";
        }
        return "\n\n你是老会员，" + (v.memberExpiresAt() == null ? "有效期内"
                : v.memberExpiresAt().atZoneSameInstant(ZONE).format(DATE) + "到期前") + "用功能不扣积分。";
    }
}
