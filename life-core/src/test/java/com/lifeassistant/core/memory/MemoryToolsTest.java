package com.lifeassistant.core.memory;

import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.auth.ToolContexts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 记忆工具对模型传来的非法参数要返回提示，而不是抛异常。 */
class MemoryToolsTest {

    private final MemoryService memoryService = mock(MemoryService.class);
    private final MemoryTools tools = new MemoryTools(memoryService);
    private final UserCtx ctx = UserCtx.of(1L);
    private final ToolContext toolContext = new ToolContext(ToolContexts.of(ctx, "c1"));

    @Test
    void invalidCategoryReturnsHintInsteadOfThrowing() {
        String result = tools.rememberFact("HOBBY", "sport", "喜欢爬山", true, toolContext);

        assertThat(result).startsWith("没有保存").contains("PREFERENCE");
        verify(memoryService, never()).upsert(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    void categoryIsCaseInsensitive() {
        String result = tools.rememberFact(" preference ", "diet_avoid", " 不吃辣 ", true, toolContext);

        assertThat(result).isEqualTo("已记住：不吃辣");
        verify(memoryService).upsert(ctx, MemoryCategory.PREFERENCE, "diet_avoid", "不吃辣",
                MemorySource.USER_STATED, null);
    }

    @Test
    void blankKeyIsRejected() {
        assertThat(tools.rememberFact("PROFILE", " ", "济南", true, toolContext)).startsWith("没有保存");
        assertThat(tools.forgetFact("PROFILE", null, toolContext)).startsWith("没有删除");
    }

    @Test
    void forgetReportsWhenNothingMatched() {
        when(memoryService.deleteByKey(ctx, MemoryCategory.PROFILE, "home_city")).thenReturn(0);
        assertThat(tools.forgetFact("PROFILE", "home_city", toolContext)).startsWith("没有找到");

        when(memoryService.deleteByKey(eq(ctx), eq(MemoryCategory.PROFILE), eq("home_city"))).thenReturn(1);
        assertThat(tools.forgetFact("PROFILE", "home_city", toolContext)).isEqualTo("已删除");
    }

    @Test
    void invalidCategoryOnForgetReturnsHint() {
        assertThat(tools.forgetFact("unknown", "x", toolContext)).startsWith("没有删除");
    }
}
