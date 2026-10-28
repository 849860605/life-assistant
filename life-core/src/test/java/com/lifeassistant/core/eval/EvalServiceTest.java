package com.lifeassistant.core.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.orchestrator.RouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 路由评测：用例校验，以及单条出错时整次运行仍能正常结束。 */
class EvalServiceTest {

    @TempDir
    Path casesDir;

    private final RouterService routerService = mock(RouterService.class);
    private final EvalRunMapper evalRunMapper = mock(EvalRunMapper.class);
    private EvalService evalService;

    @BeforeEach
    void setUp() {
        LifeProperties properties = new LifeProperties();
        properties.getEval().setCasesDir(casesDir.toString());
        evalService = new EvalService(routerService, evalRunMapper, properties, new ObjectMapper());
    }

    @Test
    void caseWithoutExpectSkillFailsBeforeRunIsCreated() throws Exception {
        Files.writeString(casesDir.resolve("routing.yaml"), """
                cases:
                  - id: ok
                    input: 周六去爬泰山
                    expectSkill: travel
                  - id: missing
                    input: 明天提醒我交电费
                """);

        assertThatThrownBy(() -> evalService.runRouting("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("routing.yaml").hasMessageContaining("第 2 条");
        verify(evalRunMapper, never()).insert(any(EvalRun.class));
    }

    @Test
    void routerErrorCountsAsFailureAndRunStillFinishes() throws Exception {
        Files.writeString(casesDir.resolve("routing.yaml"), """
                cases:
                  - id: hiking
                    input: 周六去爬泰山
                    expectSkill: travel
                  - id: reminder
                    input: 明天提醒我交电费
                    expectSkill: schedule
                """);
        when(routerService.route(eq("周六去爬泰山"), any(), any(), any()))
                .thenReturn(new RouterService.RouteDecision("travel", "爬山"));
        when(routerService.route(eq("明天提醒我交电费"), any(), any(), any()))
                .thenThrow(new IllegalStateException("模型超时"));

        EvalRun run = evalService.runRouting("test");

        assertThat(run.getTotal()).isEqualTo(2);
        assertThat(run.getPassed()).isEqualTo(1);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getResult()).contains("调用出错：模型超时");
        verify(evalRunMapper).updateById(run);
    }
}
