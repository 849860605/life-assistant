package com.lifeassistant.core.orchestrator;

import com.lifeassistant.core.audit.UsageRecorder;
import com.lifeassistant.core.card.Card;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.ModelSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 回答下面的快捷回复：猜用户下一步最可能说的话，点一下就当用户发了这句。
 * 用路由那个便宜的小模型，回答结束后再生成；出错或不需要时返回空列表，不影响回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestionService {

    static final int MAX = 4;
    static final int MAX_LENGTH = 18;
    /** 自带输入或按钮的卡片（需求卡、方案卡），出现时不再给快捷回复，免得两套按钮 */
    static final Set<String> SELF_ACTION_CARDS = Set.of("requirement", "plan_options");

    private static final String PROMPT = """
            你在"星络"这个生活 App 里，给用户生成"快捷回复"按钮：猜用户看完助手这条回答后，下一句最可能说什么。
            用户点按钮就等于发了这句话，所以每条都要是用户口吻、能直接执行的完整短句，不超过 16 个字。

            规则：
            - 助手在等用户拍板（问"要不要/确认吗/选哪个"，或提议了改动、生成了草稿）时：
              第一条是确认，并写清确认的是什么，例如"确定，保存这一版""可以，就订这家"；
              其余是最可能的替代选择，例如"改回兵马俑""全程都改打车""只要直飞"。
            - 助手问了几选一的问题，就把几个选项直接写成按钮。
            - 助手只是回答了问题、没有等用户决定时，最多给 2 条最自然的后续动作；没有明显的下一步就给空列表。
            - 只用对话里出现过的地点、日期、价格，不要编新的；不要出现付款、下单。
            - 不要重复用户刚说过的话，不要"谢谢""好的"这种没有动作的话。
            最多 4 条，按可能性从高到低排。只输出 JSON。""";

    private final ChatModelRegistry chatModels;
    private final UsageRecorder usageRecorder;

    /** 模型按这个结构输出。 */
    public record Suggestions(List<String> chips) {
    }

    /** 生成快捷回复；本轮有需求卡、方案卡，或回答是空的，直接返回空。 */
    public List<String> suggest(String userInput, String answer, List<Card> cards, Long userId, String conversationId) {
        if (answer == null || answer.isBlank() || cards.stream().anyMatch(c -> SELF_ACTION_CARDS.contains(c.kind()))) {
            return List.of();
        }
        String cardText = cards.stream().map(c -> c.kind() + (c.summary() == null ? "" : "：" + cut(c.summary(), 200)))
                .collect(Collectors.joining("\n"));
        String user = "用户这轮说：" + cut(userInput, 400) + "\n\n助手的回答（结尾部分）：\n" + tail(answer, 1500)
                + (cardText.isEmpty() ? "" : "\n\n回答里展示的卡片：\n" + cardText);
        try {
            ModelSpec model = chatModels.resolve(chatModels.models().getRouter());
            ResponseEntity<ChatResponse, Suggestions> response = chatModels.quickClient(model).prompt()
                    .system(PROMPT).user(user)
                    .options(ChatModelRegistry.options(model, 0.3))
                    .call().responseEntity(Suggestions.class);
            if (response.response() != null) {
                usageRecorder.record(userId, conversationId, "SUGGEST", model.id(), response.response().getMetadata().getUsage());
            }
            Suggestions s = response.entity();
            return s == null || s.chips() == null ? List.of() : clean(s.chips(), userInput);
        } catch (RuntimeException e) {
            log.warn("生成快捷回复失败：{}", e.getMessage());
            return List.of();
        }
    }

    /** 去空、去重、去掉和用户原话一样的、太长的，最多 MAX 条。 */
    static List<String> clean(List<String> chips, String userInput) {
        String said = userInput == null ? "" : userInput.trim();
        return chips.stream().filter(c -> c != null && !c.isBlank()).map(String::trim)
                .map(c -> c.replaceAll("^[\"“”'「」]+|[\"“”'「」]+$", ""))
                .filter(c -> !c.isEmpty() && c.length() <= MAX_LENGTH && !c.equals(said))
                .distinct().limit(MAX).toList();
    }

    private static String cut(String text, int max) {
        return text == null ? "" : text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static String tail(String text, int max) {
        return text.length() > max ? "…" + text.substring(text.length() - max) : text;
    }
}
