package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.memory.MemoryCategory;
import com.lifeassistant.core.memory.MemoryItem;
import com.lifeassistant.core.memory.MemoryService;
import com.lifeassistant.core.memory.MemorySource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/** “我的”页面：用户查看、修改、删除自己的记忆。 */
@RestController
@RequestMapping("/api/v1/memories")
@RequiredArgsConstructor
public class MemoryController {

    private final AuthFacade authFacade;
    private final MemoryService memoryService;

    /** 新增/修改记忆的请求体；同一分类下 key 相同即覆盖，expireAt 为空表示不过期。 */
    public record UpsertMemoryRequest(@NotNull MemoryCategory category, @NotBlank String key,
                                      @NotBlank String content, OffsetDateTime expireAt) {
    }

    /** GET /api/v1/memories：列出自己的全部记忆，敏感内容解密后返回。 */
    @GetMapping
    public ApiResponse<List<MemoryItem>> list() {
        return ApiResponse.ok(memoryService.listForOwner(authFacade.current()));
    }

    /** PUT /api/v1/memories：按分类 + key 新增或覆盖记忆，来源记为用户主动告知。 */
    @PutMapping
    public ApiResponse<Void> upsert(@Valid @RequestBody UpsertMemoryRequest request) {
        memoryService.upsert(authFacade.current(), request.category(), request.key(), request.content(),
                MemorySource.USER_STATED, request.expireAt());
        return ApiResponse.ok();
    }

    /** DELETE /api/v1/memories/{id}：删除自己的一条记忆，不存在时抛 NOT_FOUND。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        memoryService.delete(authFacade.current(), id);
        return ApiResponse.ok();
    }
}
