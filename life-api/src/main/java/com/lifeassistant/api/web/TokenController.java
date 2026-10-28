package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.token.ApiKey;
import com.lifeassistant.core.token.ApiKeyScope;
import com.lifeassistant.core.token.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 个人访问令牌管理（如日历订阅用）：明文只在创建时返回一次。 */
@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final AuthFacade authFacade;
    private final ApiKeyService apiKeyService;

    /** 创建令牌请求体；scope 决定令牌能用在哪个接口。 */
    public record CreateTokenRequest(@NotBlank String name, @NotNull ApiKeyScope scope) {
    }

    /** 令牌的对外视图：plainKey 只在创建时有值，列表里为空。 */
    public record TokenView(long id, String name, String scope, String prefix, String plainKey) {
        /** 由实体构造视图，plainKey 传 null 表示不返回明文。 */
        static TokenView of(ApiKey key, String plainKey) {
            return new TokenView(key.getId(), key.getName(), key.getScope(), key.getKeyPrefix(), plainKey);
        }
    }

    /** POST /api/v1/tokens：创建令牌，响应里带一次性明文。 */
    @PostMapping
    public ApiResponse<TokenView> create(@Valid @RequestBody CreateTokenRequest request) {
        ApiKeyService.Created created = apiKeyService.create(authFacade.current().userId(), request.name(), request.scope());
        return ApiResponse.ok(TokenView.of(created.apiKey(), created.plainKey()));
    }

    /** GET /api/v1/tokens：列出未吊销的令牌，不含明文。 */
    @GetMapping
    public ApiResponse<List<TokenView>> list() {
        return ApiResponse.ok(apiKeyService.list(authFacade.current().userId()).stream()
                .filter(k -> k.getRevokedAt() == null)
                .map(k -> TokenView.of(k, null))
                .toList());
    }

    /** DELETE /api/v1/tokens/{id}：吊销令牌（软删除），不属于自己时报不存在。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> revoke(@PathVariable long id) {
        apiKeyService.revoke(authFacade.current().userId(), id);
        return ApiResponse.ok();
    }
}
