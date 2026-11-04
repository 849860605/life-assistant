package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.llm.config.LlmConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 里切换对话模型：列出后台允许选的模型，保存用户自己的选择（存在账号上，换设备也一样）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ModelController {

    private final AuthFacade authFacade;
    private final LlmConfigService llmConfig;

    /** 选模型的请求体：modelId 为空表示用默认。 */
    public record ChooseBody(Long modelId) {
    }

    /** GET /api/v1/models：可选的模型、自己选的、默认的。 */
    @GetMapping("/models")
    public ApiResponse<LlmConfigService.Choices> models() {
        return ApiResponse.ok(llmConfig.choices(authFacade.current().userId()));
    }

    /** PUT /api/v1/me/model：保存自己选的对话模型，之后的对话都用它。 */
    @PutMapping("/me/model")
    public ApiResponse<LlmConfigService.Choices> choose(@RequestBody ChooseBody body) {
        long userId = authFacade.current().userId();
        llmConfig.choose(userId, body == null ? null : body.modelId());
        return ApiResponse.ok(llmConfig.choices(userId));
    }
}
