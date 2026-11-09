package com.lifeassistant.core.member;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 认出"只问自己积分"的短消息（还有多少积分、积分花哪了、各项多少积分、怎么买积分），这种直接按模板回答，不扣积分、不进模型。
 * 只认整句都是在问账号的：超过 20 个字、或者除了问积分还要办别的事（"我还有多少积分，帮我规划长沙三天"）都不算。
 */
public final class AccountQuestion {

    /** 问的是哪一类 */
    public enum Intent {
        /** 还有多少积分 */
        BALANCE,
        /** 积分花哪了、为什么扣了 */
        LEDGER,
        /** 各项多少积分 */
        PRICE,
        /** 怎么买积分 */
        BUY
    }

    /** 超过这么多字就不当成单纯问积分 */
    static final int MAX_LENGTH = 20;

    private static final Pattern NOISE = Pattern.compile("[\\s，。,.!！?？~～、…:：;；\"“”'‘’()（）]+");
    private static final Pattern LEADING = Pattern.compile("^(请问|问一下|问下|那么|那|嗯|哎|诶|喂|你好|您好|星络)+");
    private static final Pattern TRAILING = Pattern.compile("(呢|吗|呀|啊|啦|哈|嘛|哦|捏)+$");

    private static final String FEATURE = "(规划|规划行程|规划一次行程|规划一个行程|做行程|做规划|做一次规划|修改行程|改行程|改一次行程|比价|比一次价"
            + "|对话|聊天|聊一次|发消息|发一条消息|一条消息|每条消息|航班动态|查航班动态|查一次航班动态)";

    /** 按顺序匹配，整句都要对上 */
    private static final List<Rule> RULES = List.of(
            // 价格
            rule(Intent.PRICE, FEATURE + "(一次|一条|每次|每条|一个)?(要|需要|扣|花|得|收|算)?(多少|几)个?积分"),
            rule(Intent.PRICE, FEATURE + "(一次|一条|每次|每条)?(怎么|如何)?(收费|计费|扣费|扣积分)"),
            rule(Intent.PRICE, "(积分|功能|各项|每项)?(怎么|如何)(收费|计费|扣费|扣积分)的?|积分(怎么|如何)算的?"),
            rule(Intent.PRICE, "(收费|计费|积分)(标准|规则|价格表|价格)"),
            rule(Intent.PRICE, "(一个|1个|1)?积分(多少钱|值多少钱|怎么卖|什么价)"),
            rule(Intent.PRICE, "多少钱一个积分|积分包(多少钱|价格|怎么卖)|各项(都)?(要)?多少积分"),
            // 积分记录
            rule(Intent.LEDGER, "(为什么|为啥|怎么|咋)又?(扣|少|花|用)了?(我)?的?(这么多|那么多|好多|很多)?的?积分了?"),
            rule(Intent.LEDGER, "(为什么|为啥|怎么|咋)(我的)?积分(被)?(扣|少|没)了?(这么多|那么多|好多)?了?"),
            rule(Intent.LEDGER, "(我的)?积分都?(花|用|扣|去)(在|到)?(哪|哪里|哪儿)(了|去了)?"),
            rule(Intent.LEDGER, "((看|查)(一下|下|看)?)?(我的)?(积分|扣费|消费)(记录|明细|流水)"),
            rule(Intent.LEDGER, "(最近|今天|这几天)?(扣|花|用)了(多少|哪些)积分"),
            // 怎么买
            rule(Intent.BUY, "(怎么|如何|在哪|在哪里|在哪儿|哪里|哪儿|去哪|去哪里)(能|可以)?(买|购买|充值)(点|些)?积分"),
            rule(Intent.BUY, "(怎么|如何|在哪|在哪里|在哪儿|哪里|哪儿|去哪|去哪里)(能|可以)?充值"),
            rule(Intent.BUY, "积分(怎么|如何|在哪|在哪里|在哪儿|去哪|哪里)(能|可以)?(买|购买|充值|充)"),
            rule(Intent.BUY, "我?(要|想|想要)(买|购买|充值|充)(点|一些|些)?积分"),
            rule(Intent.BUY, "我?(要|想|想要)充值"),
            rule(Intent.BUY, "(充值|购买积分|买积分)(入口)?(在哪|在哪里|在哪儿)?"),
            rule(Intent.BUY, "积分(不够|没了|用完)了?(怎么办)?"),
            // 余额
            rule(Intent.BALANCE, "(我|我的)?(现在|目前|一共|总共)?(还)?(有|剩|剩下|还有|还剩)?(多少|几)个?积分了?"),
            rule(Intent.BALANCE, "(我的)?积分(现在|目前)?(还)?(有|剩|剩下|还有|还剩)?(多少|几个)了?"),
            rule(Intent.BALANCE, "(我的)?(积分)?余额(还)?(有|是|剩)?(多少)?了?"),
            rule(Intent.BALANCE, "(查|看)(一下|下|看)?(我的)?(积分|余额)(余额)?"),
            rule(Intent.BALANCE, "(我的)?积分")
    );

    private record Rule(Intent intent, Pattern pattern) {
    }

    private static Rule rule(Intent intent, String regex) {
        return new Rule(intent, Pattern.compile(regex));
    }

    private AccountQuestion() {
    }

    /** 整句是在问自己账号时返回问的哪一类，否则返回 null（照常交给星络回答）。 */
    public static Intent match(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return null;
        }
        String s = NOISE.matcher(trimmed.toLowerCase(Locale.ROOT)).replaceAll("");
        s = LEADING.matcher(s).replaceAll("");
        s = TRAILING.matcher(s).replaceAll("");
        if (s.isEmpty()) {
            return null;
        }
        for (Rule r : RULES) {
            if (r.pattern().matcher(s).matches()) {
                return r.intent();
            }
        }
        return null;
    }
}
