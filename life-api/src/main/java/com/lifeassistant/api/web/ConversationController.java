package com.lifeassistant.api.web;

import com.lifeassistant.common.api.ApiResponse;
import com.lifeassistant.common.auth.UserCtx;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.core.audit.ToolCallLogMapper;
import com.lifeassistant.core.auth.AuthFacade;
import com.lifeassistant.core.card.CardHub;
import com.lifeassistant.core.conversation.ChatMessageEntity;
import com.lifeassistant.core.conversation.Conversation;
import com.lifeassistant.core.conversation.ConversationService;
import com.lifeassistant.core.file.UploadService;
import com.lifeassistant.core.limit.RateLimiter;
import com.lifeassistant.core.member.AccountQuestion;
import com.lifeassistant.core.member.AccountReplies;
import com.lifeassistant.core.member.ChatQuota;
import com.lifeassistant.core.member.CreditService;
import com.lifeassistant.core.member.MembershipService;
import com.lifeassistant.core.orchestrator.ChatEvent;
import com.lifeassistant.core.orchestrator.ChatOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.lifeassistant.core.conversation.TurnFlags;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 会话接口：会话的增删查、历史消息查询，以及流式发送消息。
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final AuthFacade authFacade;
    private final TurnFlags turnFlags;
    private final ConversationService conversationService;
    private final ChatOrchestrator chatOrchestrator;
    private final UploadService uploadService;
    private final ToolCallLogMapper callLog;
    private final ObjectMapper objectMapper;
    private final CardHub cardHub;
    private final ChatQuota chatQuota;
    private final CreditService credits;
    private final MembershipService membership;
    private final AccountReplies accountReplies;
    private final RateLimiter limiter;

    /** 单纯问积分的消息每人每天最多免费答多少次，超过就照常走模型、照常扣（防止拿它刷库） */
    static final int FREE_ACCOUNT_ANSWERS_PER_DAY = 30;

    /**
     * 发消息请求体：内容最长 4000 字；files 是这条消息带的上传文件 id，最多 5 个；
     * chip 为 true 表示点的是上一条回答下面的快捷回复。
     */
    public record SendMessageRequest(@NotBlank @Size(max = 4000) String content, @Size(max = 5) List<String> files,
                                     Boolean chip) {
    }

    /** POST /api/v1/conversations：新建一个无标题会话，标题在首轮对话后自动生成。 */
    @PostMapping
    public ApiResponse<Conversation> create() {
        return ApiResponse.ok(conversationService.create(authFacade.current(), null));
    }

    /** GET /api/v1/conversations?q=：按更新时间倒序列出自己的会话，limit 限制在 1~100；q 按标题和消息内容搜索。 */
    @GetMapping
    public ApiResponse<List<Conversation>> list(@RequestParam(defaultValue = "30") int limit,
                                                @RequestParam(required = false) String q) {
        return ApiResponse.ok(conversationService.list(authFacade.current(), limit, q));
    }

    /** 置顶的请求体。 */
    public record PinRequest(boolean pinned) {
    }

    /** POST /api/v1/conversations/{id}/pin：置顶或取消置顶自己的会话。 */
    @PostMapping("/{id}/pin")
    public ApiResponse<Conversation> pin(@PathVariable String id, @RequestBody PinRequest request) {
        return ApiResponse.ok(conversationService.pin(authFacade.current(), id, request.pinned()));
    }

    /** 改标题的请求体。 */
    public record RenameRequest(@NotBlank(message = "标题不能为空") @Size(max = 60, message = "标题最多 60 个字") String title) {
    }

    /** PUT /api/v1/conversations/{id}：改自己会话的标题。 */
    @PutMapping("/{id}")
    public ApiResponse<Conversation> rename(@PathVariable String id, @Valid @RequestBody RenameRequest request) {
        return ApiResponse.ok(conversationService.rename(authFacade.current(), id, request.title()));
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
        // 先校验会话是不是自己的、附件能不能用，都没问题了才扣积分
        conversationService.getOwned(ctx, id);
        String input = uploadService.compose(ctx, request.content(), request.files());
        boolean chip = Boolean.TRUE.equals(request.chip());
        boolean noFiles = request.files() == null || request.files().isEmpty();
        // 单纯问自己积分（还有多少、花哪了、多少钱、怎么买）：按模板答，不扣积分、不进模型、不占会员每天的次数，积分是 0 也能问
        AccountQuestion.Intent intent = chip || !noFiles ? null : AccountQuestion.match(request.content());
        if (intent != null && limiter.perDay("account-answer:" + ctx.userId(), FREE_ACCOUNT_ANSWERS_PER_DAY)) {
            return chatOrchestrator.quickReply(ctx, id, request.content(), accountReplies.answer(ctx.userId(), intent))
                    .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
        }
        // 发一条扣一次对话的积分，不够直接报 402、不进模型；老会员到期前不扣积分，但仍有每天的次数上限防刷
        if (membership.status(ctx.userId()).active()) {
            chatQuota.consume(ctx.userId());
        }
        CreditService.Charge charge = credits.spend(ctx.userId(), CreditService.CHAT, id, false);
        // 新的一轮：清掉上一轮工具打的标记（比如"正在规划"）
        turnFlags.start(id, request.content());
        if (chip) {
            // 记下点了哪个快捷回复，以后看哪些有用
            try {
                callLog.insert(new ToolCallLogMapper.Row(ctx.userId(), id, null, "CHIP", "suggestion",
                        objectMapper.writeValueAsString(Map.of("text", request.content())), null, null, true, null));
            } catch (Exception e) {
                // 统计失败不影响发消息
            }
        }
        // 这一轮出错了（模型或网络问题、超时，不是用户主动停）就把对话的积分退回
        java.util.concurrent.atomic.AtomicBoolean refunded = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable refund = () -> {
            if (refunded.compareAndSet(false, true)) {
                credits.refund(charge);
            }
        };
        return chatOrchestrator.chat(ctx, id, input, request.content(), chip)
                .doOnNext(event -> {
                    if ("error".equals(event.type())) {
                        refund.run();
                    }
                })
                .doOnError(e -> refund.run())
                .map(event -> ServerSentEvent.builder(event).event(event.type()).build());
    }

    /** 回答过程中补充的一句话：id 是前端给的编号，星络看到后用它回一个进度事件；补充最长 500 字（更长的等这轮结束后正常发）。 */
    public record InterjectRequest(@NotBlank @Size(max = 64) String id,
                                   @NotBlank @Size(max = CardHub.MAX_INTERJECTION_LENGTH, message = "补充最多 500 个字") String content) {
    }

    /**
     * POST /api/v1/conversations/{id}/interject：回答还没结束时补充一句，星络在下一次工具调用结束后看到并调整。
     * accepted 为 false 表示这个会话现在没有正在进行的回答，或者这一轮已经补充过 3 句了，前端等这轮结束后改成正常发送。
     */
    @PostMapping("/{id}/interject")
    public ApiResponse<Map<String, Boolean>> interject(@PathVariable String id, @Valid @RequestBody InterjectRequest request) {
        conversationService.getOwned(authFacade.current(), id);
        return ApiResponse.ok(Map.of("accepted", cardHub.interject(id, request.id(), request.content().trim())));
    }

    /** DELETE /api/v1/conversations/{id}：删除自己的会话，不属于自己时报错。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        conversationService.delete(authFacade.current(), id);
        return ApiResponse.ok();
    }
}
