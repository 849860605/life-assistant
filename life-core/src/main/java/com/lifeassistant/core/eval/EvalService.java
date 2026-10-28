package com.lifeassistant.core.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.orchestrator.RouterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 评测集第一版：只评路由（这句话有没有交给正确的技能）。
 * 用例放在 eval/cases/*.yaml。以后加上工具调用检查和回答打分。
 */
@Slf4j
@Service
public class EvalService {

    private final RouterService routerService;
    private final EvalRunMapper evalRunMapper;
    private final LifeProperties properties;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public EvalService(RouterService routerService, EvalRunMapper evalRunMapper, LifeProperties properties,
                       ObjectMapper jsonMapper) {
        this.routerService = routerService;
        this.evalRunMapper = evalRunMapper;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /** 一条路由用例：input 在上一轮技能为 lastSkill 时应路由到 expectSkill。 */
    public record EvalCase(String id, String input, String expectSkill, String lastSkill) {
    }

    /** 一个 yaml 用例文件的结构。 */
    public record CaseFile(List<EvalCase> cases) {
    }

    /** 单条用例的评测结果，reason 是路由模型给出的理由。 */
    public record CaseResult(String id, String input, String expectSkill, String actualSkill, boolean passed,
                             String reason) {
    }

    /**
     * 同步跑完全部路由用例：先校验用例、插入运行记录，逐条调路由模型比对技能，最后写回通过数和结果 JSON。
     * 单条用例调用出错只记为失败，不中断整次运行，保证运行记录一定有结束时间。
     */
    public EvalRun runRouting(String triggerBy) {
        List<EvalCase> cases = loadCases();
        EvalRun run = new EvalRun();
        run.setTriggerBy(triggerBy);
        run.setTotal(cases.size());
        run.setPassed(0);
        evalRunMapper.insert(run);

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            results.add(runCase(c));
        }
        run.setPassed((int) results.stream().filter(CaseResult::passed).count());
        try {
            run.setResult(jsonMapper.writeValueAsString(results));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        run.setFinishedAt(OffsetDateTime.now());
        evalRunMapper.updateById(run);
        log.info("路由评测完成：{}/{} 通过", run.getPassed(), run.getTotal());
        return run;
    }

    /** 跑一条用例；路由调用抛异常时记为失败，reason 写错误信息。 */
    private CaseResult runCase(EvalCase c) {
        try {
            RouterService.RouteDecision decision = routerService.route(c.input(), c.lastSkill(), null, "eval");
            return new CaseResult(c.id(), c.input(), c.expectSkill(), decision.skillId(),
                    c.expectSkill().equals(decision.skillId()), decision.reason());
        } catch (RuntimeException e) {
            log.warn("评测用例 {} 调用出错", c.id(), e);
            return new CaseResult(c.id(), c.input(), c.expectSkill(), null, false, "调用出错：" + e.getMessage());
        }
    }

    /**
     * 读取用例目录下所有 .yaml 文件（按文件名排序）合并成用例列表。
     * 目录不存在，或有用例缺少 id / input / expectSkill 时报错，报错信息带文件名。
     */
    public List<EvalCase> loadCases() {
        Path dir = Path.of(properties.getEval().getCasesDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("找不到评测用例目录：" + dir.toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<EvalCase> cases = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                CaseFile caseFile = yamlMapper.readValue(file.toFile(), CaseFile.class);
                if (caseFile.cases() == null) {
                    continue;
                }
                for (int i = 0; i < caseFile.cases().size(); i++) {
                    EvalCase c = caseFile.cases().get(i);
                    if (c == null || isBlank(c.id()) || isBlank(c.input()) || isBlank(c.expectSkill())) {
                        throw new IllegalStateException("评测用例不完整：" + file.getFileName() + " 第 " + (i + 1)
                                + " 条，id、input、expectSkill 都必须填写");
                    }
                    cases.add(c);
                }
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** null 或只有空白。 */
    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
