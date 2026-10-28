package com.lifeassistant.core.memory;

import com.lifeassistant.core.auth.ToolContexts;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/** 所有技能共用的记忆工具，供模型读写用户长期记忆。 */
@Component
@RequiredArgsConstructor
public class MemoryTools {

    private static final String CATEGORY_HINT = "category 只能是 " + Arrays.toString(MemoryCategory.values());

    private final MemoryService memoryService;

    /** 写入或覆盖一条记忆；userStated 决定来源和置信度。参数不合法时返回提示文字让模型重试，不抛异常。 */
    @Tool(description = """
            记住关于用户的一条长期信息，以后对话会用到。只在用户明确告诉你，或者从对话中能确定时调用。
            例如：常住城市、忌口、体能水平、预算习惯、家人情况、伤病。同一 category 和 key 会覆盖旧值。
            行程、计划、待办、提醒不要用这个记，它们有专门的行程和日程工具。""")
    public String rememberFact(
            @ToolParam(description = "分类：PROFILE 基本信息 / PREFERENCE 偏好 / RELATION 家人朋友 / HEALTH 健康伤病 / FINANCE 财务") String category,
            @ToolParam(description = "英文小写下划线的键，例如 home_city、diet_avoid、fitness_level、knee_injury") String key,
            @ToolParam(description = "用一句中文描述，例如：不吃辣，海鲜过敏") String content,
            @ToolParam(description = "是否是用户亲口明确说的；从对话推断的填 false") boolean userStated,
            ToolContext toolContext) {
        Optional<MemoryCategory> memoryCategory = parseCategory(category);
        if (memoryCategory.isEmpty()) {
            return "没有保存：" + CATEGORY_HINT;
        }
        if (isBlank(key) || isBlank(content)) {
            return "没有保存：key 和 content 都不能为空";
        }
        memoryService.upsert(ToolContexts.user(toolContext), memoryCategory.get(), key.trim(), content.trim(),
                userStated ? MemorySource.USER_STATED : MemorySource.AI_INFERRED, null);
        return "已记住：" + content.trim();
    }

    /** 按分类和键删除记忆；没匹配到时如实告诉模型，避免它对用户说"已删除"。 */
    @Tool(description = "删除一条关于用户的长期信息。用户说某条信息不对、过时了或者让你忘掉时调用。")
    public String forgetFact(
            @ToolParam(description = "分类：PROFILE / PREFERENCE / RELATION / HEALTH / FINANCE") String category,
            @ToolParam(description = "要删除的键") String key,
            ToolContext toolContext) {
        Optional<MemoryCategory> memoryCategory = parseCategory(category);
        if (memoryCategory.isEmpty()) {
            return "没有删除：" + CATEGORY_HINT;
        }
        if (isBlank(key)) {
            return "没有删除：key 不能为空";
        }
        int deleted = memoryService.deleteByKey(ToolContexts.user(toolContext), memoryCategory.get(), key.trim());
        return deleted > 0 ? "已删除" : "没有找到这条记忆：" + memoryCategory.get() + " / " + key.trim();
    }

    /** 分类名不区分大小写；空值或不在枚举里返回空。 */
    static Optional<MemoryCategory> parseCategory(String category) {
        if (isBlank(category)) {
            return Optional.empty();
        }
        String name = category.trim().toUpperCase();
        return Arrays.stream(MemoryCategory.values()).filter(c -> c.name().equals(name)).findFirst();
    }

    /** null 或只有空白。 */
    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
