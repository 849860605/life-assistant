package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.conversation.ChatMessageEntity;
import com.lifeassistant.core.conversation.Conversation;
import com.lifeassistant.core.conversation.ConversationService;
import com.lifeassistant.core.orchestrator.ChatEvent;
import com.lifeassistant.core.orchestrator.ChatOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 会话接口：会话的增删查、历史消息查询，以及流式发送消息。
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final AuthFacade authFacade;
    private final ConversationService conversationService;
    private final ChatOrchestrator chatOrchestrator;

    /** 发消息请求体，内容最长 4000 字。 */
    public record SendMessageRequest(@NotBlank @Size(max = 4000) String content) {
    }

    /** POST /api/v1/conversations：新建一个无标题会话，标题在首轮对话后自动生成。 */
    @PostMapping
    public ApiResponse<Conversation> create() {
        return ApiResponse.ok(conversationService.create(authFacade.current(), null));
    }

    /** GET /api/v1/conversations：按更新时间倒序列出自己的会话，limit 限制在 1~100。 */
    @GetMapping
    public ApiResponse<List<Conversation>> list(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(conversationService.list(authFacade.current(), limit));
    }

    /** GET /api/v1/conversations/{id}/messages：查会话最近的消息，先校验会话归属，limit 限制在 1~500。 */
    @GetMapping("/{id}/messages")
    public ApiResponse<List<ChatMessageEntity>> messages(@PathVariable String id,
                                                         @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(conversationService.messages(authFacade.current(), id, limit));
    }

    /**
     * POST /api/v1/conversations/{id}/messages：发消息，SSE 流式返回。事件类型：route / delta / done / error。
     * 浏览器的 EventSource 不能带 Authorization 头，前端用 fetch 读取这个流。
     */
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatEvent>> send(@PathVariable String id, @Valid @RequestBody SendMessageRequest request) {
        // 身份必须在请求线程里取，后面的图和工具都在其他线程执行
        UserCtx ctx = authFacade.current();
        return chatOrchestrator.chat(ctx, id, request.content())
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }

    /** DELETE /api/v1/conversations/{id}：删除自己的会话，不属于自己时报错。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        conversationService.delete(authFacade.current(), id);
        return ApiResponse.ok();
    }
}
