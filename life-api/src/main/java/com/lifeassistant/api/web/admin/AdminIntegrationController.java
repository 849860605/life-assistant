package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.error.BizException;
import com.lifeassistant.common.error.ErrorCode;
import com.lifeassistant.core.audit.ToolCallLogMapper;
import com.lifeassistant.core.integration.IntegrationCatalog;
import com.lifeassistant.core.integration.IntegrationSettings;
import com.lifeassistant.core.user.AdminUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 第三方平台配置：列表（状态、Key 只显示后 4 位、近 7 天调用量）、修改 Key 和地址、启用停用、测试。
 * 管理员都能看，只有超级管理员能改和测试（测试会占平台额度）。改完立即生效，操作日志里只记改了哪些字段，不记值。
 */
@RestController
@RequestMapping("/admin/api/v1/integrations")
@RequiredArgsConstructor
public class AdminIntegrationController {

    private final IntegrationSettings settings;
    private final IntegrationTester tester;
    private final ToolCallLogMapper callLogMapper;
    private final AdminContext adminContext;

    /**
     * 一个字段：value 是非敏感字段的值，敏感字段只给 masked（如 ••••a1b2）；source 是生效值从哪来
     * （ADMIN 后台填的、ENV 是 .env 的，没有为空）；envValue 表示 .env 里也有值（清掉后台的值后会用它）。
     */
    public record FieldView(String name, String label, String kind, String env, String placeholder, String value,
                            String masked, String source, boolean envValue) {
    }

    /**
     * 一个平台：status 是 ON（启用且配好了）、UNSET（启用了但缺 Key 或地址）、OFF（停用）；
     * calls7d、failures7d、avgMs 是近 7 天的平台调用统计（没记调用日志的平台为空）。
     */
    public record IntegrationView(String code, String name, String category, String description, String impact,
                                  String testNote, boolean keyOptional, boolean enabled, String status,
                                  List<FieldView> fields, Long calls7d, Long failures7d, Long avgMs,
                                  OffsetDateTime updatedAt) {
    }

    /** 修改：values 里填了的字段覆盖（敏感字段留空表示不改），clear 里的字段改回用 .env 的值。 */
    public record UpdateRequest(Map<String, String> values, List<String> clear) {
    }

    /** 启用停用的请求体。 */
    public record StatusRequest(boolean enabled) {
    }

    /** GET /admin/api/v1/integrations：全部平台，按分类排好。 */
    @GetMapping
    public ApiResponse<List<IntegrationView>> list() {
        adminContext.current();
        Map<String, Map<String, Object>> stats = platformStats();
        return ApiResponse.ok(IntegrationCatalog.PLATFORMS.stream().map(p -> view(p, stats)).toList());
    }

    /** PUT /admin/api/v1/integrations/{code}：改 Key、地址；地址换了要同时重新填 Key。 */
    @PutMapping("/{code}")
    public ApiResponse<IntegrationView> update(@PathVariable String code, @RequestBody UpdateRequest request) {
        AdminUser me = adminContext.requireSuper();
        IntegrationCatalog.Platform platform = platform(code);
        Map<String, String> values = request.values() == null ? Map.of() : request.values();
        List<String> changed = new ArrayList<>();
        for (IntegrationCatalog.Field f : platform.fields()) {
            String v = values.get(f.name());
            if (v != null && !v.isBlank() && f.kind() != IntegrationCatalog.Kind.READONLY
                    && !v.trim().equals(settings.savedValue(code, f.name()))) {
                changed.add("改了" + f.label());
            }
            if (request.clear() != null && request.clear().contains(f.name()) && settings.savedValue(code, f.name()) != null) {
                changed.add(f.label() + "改回用 .env");
            }
        }
        settings.save(code, null, values, request.clear(), me.getId());
        if (!changed.isEmpty()) {
            adminContext.log(me.getId(), "UPDATE_INTEGRATION", platform.name() + "：" + String.join("、", changed));
        }
        return ApiResponse.ok(view(platform, platformStats()));
    }

    /** POST /admin/api/v1/integrations/{code}/status：启用或停用，立即生效。 */
    @PostMapping("/{code}/status")
    public ApiResponse<IntegrationView> setStatus(@PathVariable String code, @RequestBody StatusRequest request) {
        AdminUser me = adminContext.requireSuper();
        IntegrationCatalog.Platform platform = platform(code);
        settings.save(code, request.enabled(), Map.of(), List.of(), me.getId());
        adminContext.log(me.getId(), request.enabled() ? "ENABLE_INTEGRATION" : "DISABLE_INTEGRATION", platform.name());
        return ApiResponse.ok(view(platform, platformStats()));
    }

    /** POST /admin/api/v1/integrations/{code}/test：用当前配置调一次平台，返回成败、耗时和说明。 */
    @PostMapping("/{code}/test")
    public ApiResponse<IntegrationTester.Result> test(@PathVariable String code) {
        AdminUser me = adminContext.requireSuper();
        IntegrationCatalog.Platform platform = platform(code);
        IntegrationTester.Result result = tester.test(platform);
        adminContext.log(me.getId(), "TEST_INTEGRATION", platform.name() + (result.ok() ? "：通过" : "：失败"));
        return ApiResponse.ok(result);
    }

    private IntegrationCatalog.Platform platform(String code) {
        return IntegrationCatalog.find(code).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "没有这个平台"));
    }

    private IntegrationView view(IntegrationCatalog.Platform p, Map<String, Map<String, Object>> stats) {
        List<FieldView> fields = p.fields().stream().map(f -> field(p.code(), f)).toList();
        boolean enabled = settings.enabled(p.code());
        Map<String, Object> stat = p.callName() == null ? null : stats.get(p.callName());
        IntegrationSettings.Stored stored = settings.stored(p.code());
        return new IntegrationView(p.code(), p.name(), p.category(), p.description(), p.impact(), p.testNote(),
                p.keyOptional(), enabled, !enabled ? "OFF" : configured(p) ? "ON" : "UNSET", fields,
                stat == null ? (p.callName() == null ? null : 0L) : number(stat.get("calls")),
                stat == null ? (p.callName() == null ? null : 0L) : number(stat.get("failures")),
                stat == null ? null : number(stat.get("avg_ms")),
                stored == null ? null : stored.updatedAt());
    }

    private FieldView field(String code, IntegrationCatalog.Field f) {
        String value = settings.rawValue(code, f.name());
        String source = settings.savedValue(code, f.name()) != null ? "ADMIN" : value != null ? "ENV" : null;
        boolean secret = f.kind() == IntegrationCatalog.Kind.SECRET;
        return new FieldView(f.name(), f.label(), f.kind().name(), f.env(), f.placeholder(), secret ? null : value,
                secret ? mask(value) : null, source, settings.fallback(code, f.name()) != null);
    }

    /** 该填的都有值：Key（不填也能用的平台除外）和地址；高德 MCP 看高德 Web 服务的 Key。 */
    private boolean configured(IntegrationCatalog.Platform p) {
        if (p.code().equals("amap-mcp")) {
            return settings.value("amap", "key") != null;
        }
        return p.fields().stream()
                .filter(f -> f.kind() != IntegrationCatalog.Kind.READONLY)
                .filter(f -> f.kind() != IntegrationCatalog.Kind.SECRET || !p.keyOptional())
                .allMatch(f -> settings.rawValue(p.code(), f.name()) != null);
    }

    /** Key 只露后 4 位；太短的全遮住。 */
    private static String mask(String value) {
        if (value == null) {
            return null;
        }
        return value.length() >= 12 ? "••••" + value.substring(value.length() - 4) : "••••";
    }

    /** 近 7 天各平台的调用统计，按调用日志里的平台名。 */
    private Map<String, Map<String, Object>> platformStats() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> row : callLogMapper.stats(7)) {
            if ("PLATFORM".equals(row.get("kind"))) {
                out.put(Objects.toString(row.get("tool_name")), row);
            }
        }
        return out;
    }

    private static Long number(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
