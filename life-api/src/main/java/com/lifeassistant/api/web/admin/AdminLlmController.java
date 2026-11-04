package com.lifeassistant.api.web.admin;

import com.lifeassistant.api.auth.AdminContext;
import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.config.LifeProperties;
import com.lifeassistant.core.llm.ChatModelRegistry;
import com.lifeassistant.core.llm.ModelSpec;
import com.lifeassistant.core.llm.config.LlmConfigService;
import com.lifeassistant.core.llm.config.LlmModel;
import com.lifeassistant.core.llm.config.LlmProvider;
import com.lifeassistant.core.user.AdminUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型配置：提供方（地址、Key）、模型（单价、App 里能不能选）、各用途用哪个模型。
 * 管理员都能看（Key 只显示后 4 位），只有超级管理员能改；改完立即生效，不用重启。
 */
@RestController
@RequestMapping("/admin/api/v1/llm")
@RequiredArgsConstructor
public class AdminLlmController {

    private final LlmConfigService llmConfig;
    private final ChatModelRegistry chatModels;
    private final AdminContext adminContext;

    /** 测试连接的请求体：model 为空用这个提供方下排第一的模型。 */
    public record TestRequest(String model) {
    }

    /** 测试连接的结果：成功时带回复，失败时带原因；millis 是耗时。 */
    public record TestResult(boolean ok, long millis, String model, String reply, String error) {
    }

    /** 分配用途的请求体。 */
    public record RoleRequest(long modelId) {
    }

    /** GET /admin/api/v1/llm/providers：全部提供方。 */
    @GetMapping("/providers")
    public ApiResponse<List<LlmConfigService.ProviderView>> providers() {
        adminContext.current();
        return ApiResponse.ok(llmConfig.listProviders());
    }

    /** POST /admin/api/v1/llm/providers：新建提供方。 */
    @PostMapping("/providers")
    public ApiResponse<Void> createProvider(@RequestBody LlmConfigService.ProviderForm form) {
        AdminUser me = adminContext.requireSuper();
        LlmProvider p = llmConfig.createProvider(form);
        adminContext.log(me.getId(), "CREATE_LLM_PROVIDER", p.getCode() + "（" + p.getName() + "）");
        return ApiResponse.ok();
    }

    /** PUT /admin/api/v1/llm/providers/{code}：修改提供方，apiKey 为空表示不改 Key。 */
    @PutMapping("/providers/{code}")
    public ApiResponse<Void> updateProvider(@PathVariable String code, @RequestBody LlmConfigService.ProviderForm form) {
        AdminUser me = adminContext.requireSuper();
        llmConfig.updateProvider(code, form);
        boolean keyChanged = form.apiKey() != null && !form.apiKey().isBlank();
        adminContext.log(me.getId(), "UPDATE_LLM_PROVIDER", code + (keyChanged ? "（换了 Key）" : ""));
        return ApiResponse.ok();
    }

    /** DELETE /admin/api/v1/llm/providers/{code}：删除提供方，下面还有模型时不让删。 */
    @DeleteMapping("/providers/{code}")
    public ApiResponse<Void> deleteProvider(@PathVariable String code) {
        AdminUser me = adminContext.requireSuper();
        llmConfig.deleteProvider(code);
        adminContext.log(me.getId(), "DELETE_LLM_PROVIDER", code);
        return ApiResponse.ok();
    }

    /** POST /admin/api/v1/llm/providers/{code}/test：用一个型号发一句很短的话，看连不连得通。 */
    @PostMapping("/providers/{code}/test")
    public ApiResponse<TestResult> test(@PathVariable String code, @RequestBody(required = false) TestRequest request) {
        adminContext.requireSuper();
        ModelSpec spec = llmConfig.testSpec(code, request == null ? null : request.model());
        long start = System.currentTimeMillis();
        try {
            String reply = chatModels.ping(spec);
            return ApiResponse.ok(new TestResult(true, System.currentTimeMillis() - start, spec.model(),
                    cut(reply == null ? "" : reply.trim(), 60), null));
        } catch (RuntimeException e) {
            return ApiResponse.ok(new TestResult(false, System.currentTimeMillis() - start, spec.model(), null,
                    cut(scrub(code, rootMessage(e)), 300)));
        }
    }

    /** GET /admin/api/v1/llm/providers/{code}/remote-models：从提供方拉型号列表，添加模型时下拉选。 */
    @GetMapping("/providers/{code}/remote-models")
    public ApiResponse<List<String>> remoteModels(@PathVariable String code) {
        adminContext.requireSuper();
        return ApiResponse.ok(chatModels.remoteModels(code));
    }

    /** GET /admin/api/v1/llm/models：全部模型。 */
    @GetMapping("/models")
    public ApiResponse<List<LlmModel>> models() {
        adminContext.current();
        return ApiResponse.ok(llmConfig.listModels());
    }

    /** POST /admin/api/v1/llm/models：新建模型。 */
    @PostMapping("/models")
    public ApiResponse<LlmModel> createModel(@RequestBody LlmConfigService.ModelForm form) {
        AdminUser me = adminContext.requireSuper();
        LlmModel m = llmConfig.createModel(form);
        adminContext.log(me.getId(), "CREATE_LLM_MODEL", m.spec());
        return ApiResponse.ok(m);
    }

    /** PUT /admin/api/v1/llm/models/{id}：修改模型（提供方和型号不能改）。 */
    @PutMapping("/models/{id}")
    public ApiResponse<LlmModel> updateModel(@PathVariable long id, @RequestBody LlmConfigService.ModelForm form) {
        AdminUser me = adminContext.requireSuper();
        LlmModel m = llmConfig.updateModel(id, form);
        adminContext.log(me.getId(), "UPDATE_LLM_MODEL", m.spec());
        return ApiResponse.ok(m);
    }

    /** DELETE /admin/api/v1/llm/models/{id}：删除模型，正在用于某个用途时不让删。 */
    @DeleteMapping("/models/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable long id) {
        AdminUser me = adminContext.requireSuper();
        LlmModel m = llmConfig.deleteModel(id);
        adminContext.log(me.getId(), "DELETE_LLM_MODEL", m.spec());
        return ApiResponse.ok();
    }

    /** GET /admin/api/v1/llm/roles：各用途分配的模型 id（没分配为空，用 application.yml 的）。 */
    @GetMapping("/roles")
    public ApiResponse<Map<String, Long>> roles() {
        adminContext.current();
        return ApiResponse.ok(llmConfig.roleAssignments());
    }

    /** PUT /admin/api/v1/llm/roles/{role}：给用途分配模型。 */
    @PutMapping("/roles/{role}")
    public ApiResponse<Void> assignRole(@PathVariable String role, @RequestBody RoleRequest request) {
        AdminUser me = adminContext.requireSuper();
        LlmModel m = llmConfig.assignRole(role, request.modelId());
        adminContext.log(me.getId(), "ASSIGN_LLM_ROLE", role + " → " + m.spec());
        return ApiResponse.ok();
    }

    /** 最里层的异常信息，模型接口的报错（如 401、404）一般在这里。 */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    /** 报错里如果带了 Key（有的厂商会回显），换成星号再给页面。 */
    private String scrub(String code, String text) {
        LifeProperties.Provider p = chatModels.provider(code);
        if (p != null && p.getApiKey() != null && p.getApiKey().length() >= 6) {
            text = text.replace(p.getApiKey(), "****");
        }
        return text;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
