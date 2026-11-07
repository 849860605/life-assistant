package com.lifeassistant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.conversation.ConversationService;
import com.lifeassistant.core.mail.EmailSender;
import com.lifeassistant.core.member.ChatQuota;
import com.lifeassistant.core.orchestrator.ChatEvent;
import com.lifeassistant.core.orchestrator.ChatOrchestrator;
import com.lifeassistant.core.orchestrator.RouterService;
import com.lifeassistant.core.schedule.ScheduleService;
import com.lifeassistant.core.orchestrator.SkillRunner;
import com.lifeassistant.core.user.User;
import com.lifeassistant.core.user.UserService;
import com.lifeassistant.skill.travel.trip.TripService;
import com.lifeassistant.skill.travel.trip.TripVersion;
import com.lifeassistant.skill.travel.trip.TripVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 用真实的 PostgreSQL 和 Redis 跑：建表、登录、会话归属、记忆加密、行程版本、日历订阅、
 * 以及编排图的流式输出和断点持久化。模型调用（路由、技能）用 Mock 代替，不需要 API Key。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // 不填任何模型 Key 也要能启动：模型客户端在第一次用到时才创建
        "life.llm.providers.dashscope.api-key=",
        "spring.ai.mcp.client.enabled=false",
        "life.crypto.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "life.scheduling.enabled=false",
        "life.bootstrap.username=alice",
        "life.bootstrap.password=alice-pass-123",
        "life.bootstrap.admin-username=admin",
        "life.bootstrap.admin-password=admin-pass-123"
})
class LifeApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("life").withUsername("life").withPassword("life");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("DB_HOST", POSTGRES::getHost);
        registry.add("DB_PORT", () -> POSTGRES.getMappedPort(5432));
        registry.add("REDIS_HOST", REDIS::getHost);
        registry.add("REDIS_PORT", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    RouterService routerService;
    @MockitoBean
    SkillRunner skillRunner;
    /** 不真发邮件：从调用记录里取验证码 */
    @MockitoBean
    EmailSender emailSender;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserService userService;
    @Autowired
    TripService tripService;
    @Autowired
    TripVersionMapper tripVersionMapper;
    @Autowired
    ChatOrchestrator chatOrchestrator;
    @Autowired
    ConversationService conversationService;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ChatQuota chatQuota;

    String token;

    @BeforeEach
    void login() throws Exception {
        token = loginAs("alice", "alice-pass-123");
    }

    @Test
    void protectedApiRequiresLogin() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/memories", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminManagesUsersAndAdmins() throws Exception {
        String admin = adminLogin("admin", "admin-pass-123");
        // 管理端 token 不能当用户 token 用，反过来也不行
        assertThat(call(admin, HttpMethod.GET, "/api/v1/memories", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(token, HttpMethod.GET, "/admin/api/v1/overview", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 弱密码建不了用户
        assertThat(call(admin, HttpMethod.POST, "/admin/api/v1/users",
                Map.of("username", "carol", "password", "short")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long bobId = body(call(admin, HttpMethod.POST, "/admin/api/v1/users",
                Map.of("username", "carol", "password", "carol-pass-1234", "nickname", "卡罗尔"))).path("data").path("id").asLong();
        String bobToken = loginAs("carol", "carol-pass-1234");

        // 重置密码：旧 token 失效，新密码能登录
        assertThat(call(admin, HttpMethod.POST, "/admin/api/v1/users/" + bobId + "/password",
                Map.of("password", "carol-new-5678")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(bobToken, HttpMethod.GET, "/api/v1/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        bobToken = loginAs("carol", "carol-new-5678");

        // 停用：已登录的立即下线，再登录报"账号已被禁用"
        assertThat(call(admin, HttpMethod.POST, "/admin/api/v1/users/" + bobId + "/status",
                Map.of("enabled", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(bobToken, HttpMethod.GET, "/api/v1/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/api/v1/auth/login", Map.of("username", "carol", "password", "carol-new-5678"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 列表和概要只有计数，能搜到 bob
        JsonNode page = body(call(admin, HttpMethod.GET, "/admin/api/v1/users?keyword=caro", null)).path("data");
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("status").asInt()).isZero();
        assertThat(body(call(admin, HttpMethod.GET, "/admin/api/v1/overview", null)).path("data").path("users").asLong())
                .isGreaterThanOrEqualTo(2);

        // 不能停用自己；普通管理员不能管管理员
        long adminId = body(call(admin, HttpMethod.GET, "/admin/api/v1/auth/me", null)).path("data").path("id").asLong();
        assertThat(call(admin, HttpMethod.POST, "/admin/api/v1/admins/" + adminId + "/status",
                Map.of("enabled", false)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.POST, "/admin/api/v1/admins",
                Map.of("username", "ops", "password", "ops-pass-1234", "role", "ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
        String ops = adminLogin("ops", "ops-pass-1234");
        assertThat(call(ops, HttpMethod.GET, "/admin/api/v1/admins", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 改自己密码要原密码；改完新密码能登录
        assertThat(call(ops, HttpMethod.POST, "/admin/api/v1/auth/password",
                Map.of("oldPassword", "wrong-pass-1", "newPassword", "ops-new-5678")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(ops, HttpMethod.POST, "/admin/api/v1/auth/password",
                Map.of("oldPassword", "ops-pass-1234", "newPassword", "ops-new-5678")).getStatusCode()).isEqualTo(HttpStatus.OK);
        adminLogin("ops", "ops-new-5678");

        // 操作都记了日志
        JsonNode logs = body(call(admin, HttpMethod.GET, "/admin/api/v1/op-logs?size=50", null)).path("data").path("items");
        assertThat(logs.findValuesAsText("action")).contains("CREATE_USER", "RESET_USER_PASSWORD", "DISABLE_USER",
                "CREATE_ADMIN", "CHANGE_OWN_PASSWORD");
    }

    @Test
    void membershipGrantsTimeAndRaisesDailyQuota() throws Exception {
        String admin = adminLogin("admin", "admin-pass-123");
        long daveId = body(call(admin, HttpMethod.POST, "/admin/api/v1/users",
                Map.of("username", "dave", "password", "dave-pass-1234"))).path("data").path("id").asLong();
        String dave = loginAs("dave", "dave-pass-1234");
        assertThat(body(call(dave, HttpMethod.GET, "/api/v1/membership", null)).path("data").path("active").asBoolean()).isFalse();

        // 免费 1 次、会员 2 次：免费用户第二次就被挡住，发消息接口返回 402
        body(call(admin, HttpMethod.PUT, "/admin/api/v1/pay/member/quota", Map.of("freeDaily", 1, "memberDaily", 2)));
        try {
            chatQuota.consume(daveId);
            String conversationId = body(call(dave, HttpMethod.POST, "/api/v1/conversations", Map.of())).path("data").path("id").asText();
            ResponseEntity<String> blocked = call(dave, HttpMethod.POST, "/api/v1/conversations/" + conversationId + "/messages",
                    Map.of("content", "你好"));
            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(blocked.getBody()).contains("开通会员");

            // 后台送 30 天：变成会员，次数上限换成会员的，再加 30 天从原到期日往后顺延
            JsonNode first = body(call(admin, HttpMethod.POST, "/admin/api/v1/pay/members/" + daveId + "/grant",
                    Map.of("days", 30, "note", "送朋友"))).path("data");
            assertThat(first.path("active").asBoolean()).isTrue();
            assertThat(first.path("dailyLimit").asInt()).isEqualTo(2);
            chatQuota.consume(daveId);
            JsonNode second = body(call(admin, HttpMethod.POST, "/admin/api/v1/pay/members/" + daveId + "/grant",
                    Map.of("days", 30))).path("data");
            assertThat(Duration.between(OffsetDateTime.parse(first.path("expiresAt").asText()),
                    OffsetDateTime.parse(second.path("expiresAt").asText())).toDays()).isEqualTo(30);

            // 账单两条，新的在前，第二条记着变动前的到期时间；用户自己看不到后台备注
            JsonNode logs = second.path("logs");
            assertThat(logs).hasSize(2);
            assertThat(logs.get(0).path("expiresBefore").asText()).isEqualTo(first.path("expiresAt").asText());
            assertThat(logs.get(1).path("note").asText()).isEqualTo("送朋友");
            JsonNode mine = body(call(dave, HttpMethod.GET, "/api/v1/membership/logs", null)).path("data");
            assertThat(mine).hasSize(2);
            assertThat(mine.get(0).has("note")).isFalse();
            assertThat(body(call(admin, HttpMethod.GET, "/admin/api/v1/pay/stats", null)).path("data").path("activeMembers").asLong())
                    .isGreaterThanOrEqualTo(1);
        } finally {
            body(call(admin, HttpMethod.PUT, "/admin/api/v1/pay/member/quota", Map.of("freeDaily", 20, "memberDaily", 200)));
        }
    }

    @Test
    void wrongPasswordIsRejected() {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", "alice", "password", "wrong"), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void sensitiveMemoryIsEncryptedAtRest() throws Exception {
        put("/api/v1/memories", Map.of("category", "HEALTH", "key", "knee_injury", "content", "左膝半月板旧伤"));
        put("/api/v1/memories", Map.of("category", "PREFERENCE", "key", "diet_avoid", "content", "不吃辣"));

        JsonNode list = get("/api/v1/memories").path("data");
        assertThat(list.findValuesAsText("content")).contains("左膝半月板旧伤", "不吃辣");

        String stored = jdbc.queryForObject(
                "SELECT content FROM t_memory WHERE mem_key = 'knee_injury'", String.class);
        assertThat(stored).isNotEqualTo("左膝半月板旧伤").doesNotContain("半月板");
    }

    @Test
    void chatStreamsThroughGraphAndPersistsCheckpoint() throws Exception {
        when(routerService.route(anyString(), any(), any(), anyString()))
                .thenReturn(new RouterService.RouteDecision("travel", "测试"));
        when(skillRunner.stream(any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> Flux.just(chunk("泰山"), chunk("周六"), chunk("天气不错")));

        String conversationId = post("/api/v1/conversations", Map.of()).path("data").path("id").asText();

        String firstTurn = streamChat(conversationId, "周六去爬泰山");
        assertThat(firstTurn).contains("event:route").contains("\"skillId\":\"travel\"")
                .contains("泰山").contains("天气不错").contains("event:done");

        // 第二轮会从数据库里的断点恢复状态，验证状态能正常序列化和反序列化
        String secondTurn = streamChat(conversationId, "那周日呢");
        assertThat(secondTurn).contains("event:delta").contains("event:done").doesNotContain("event:error");

        Integer checkpointTables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name ILIKE '%checkpoint%'""", Integer.class);
        assertThat(checkpointTables).isPositive();

        JsonNode conversations = get("/api/v1/conversations").path("data");
        assertThat(conversations.findValuesAsText("title")).contains("周六去爬泰山");
    }

    @Test
    void burstyChunksKeepTheirOrderUnderBackpressure() {
        // 真实模型一次网络读取会到好几个分片（突发），而 SSE 一次只要一个（背压）。
        // 编排图内部用 flatMap 转换输出，背压下会打乱顺序，ChatOrchestrator 必须保证顺序不变
        when(routerService.route(anyString(), any(), any(), anyString()))
                .thenReturn(new RouterService.RouteDecision("travel", "测试"));
        when(skillRunner.stream(any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> Flux.range(0, 300).buffer(20).delayElements(Duration.ofMillis(2))
                        .flatMapIterable(batch -> batch).map(i -> chunk(i + ",")));
        User alice = userService.findByUsername("alice").orElseThrow();
        UserCtx ctx = UserCtx.of(alice.getId());
        String conversationId = conversationService.create(ctx, null).getId();

        List<ChatEvent> events = chatOrchestrator.chat(ctx, conversationId, "顺序测试")
                .limitRate(1)
                .delayElements(Duration.ofMillis(1))
                .collectList().block(Duration.ofSeconds(30));

        String text = events.stream().filter(e -> "delta".equals(e.type())).map(ChatEvent::text)
                .collect(Collectors.joining());
        String expected = IntStream.range(0, 300).mapToObj(i -> i + ",").collect(Collectors.joining());
        assertThat(text).isEqualTo(expected);
    }

    @Test
    void otherUsersConversationIsForbidden() throws Exception {
        String conversationId = post("/api/v1/conversations", Map.of()).path("data").path("id").asText();
        if (userService.findByUsername("bob").isEmpty()) {
            userService.create("bob", "bob-pass-123", "Bob");
        }
        String bobToken = loginAs("bob", "bob-pass-123");
        ResponseEntity<String> response = rest.exchange("/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.GET, new HttpEntity<>(headers(bobToken)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void tripKeepsVersions() throws Exception {
        User alice = userService.findByUsername("alice").orElseThrow();
        UserCtx ctx = UserCtx.of(alice.getId());
        TripService.TripDetail first = tripService.saveVersion(ctx, null, new TripService.TripDraft(
                "泰山日出", "HIKING", "泰山", null, null, "第一版", "首次生成"), "AI");
        tripService.saveVersion(ctx, first.trip().getId(), new TripService.TripDraft(
                null, null, null, null, null, "第二版：改成坐缆车下山", "下山改缆车"), "AI");

        JsonNode current = get("/api/v1/trips/" + first.trip().getId()).path("data");
        assertThat(current.path("version").path("version").asInt()).isEqualTo(2);
        assertThat(current.path("version").path("content").asText()).contains("缆车");
        assertThat(get("/api/v1/trips/" + first.trip().getId() + "/versions").path("data")).hasSize(2);
    }

    @Test
    void tripLedgerKeepsBookingsAndExpensesPrivateAndCountsOnce() throws Exception {
        User alice = userService.findByUsername("alice").orElseThrow();
        long tripId = tripService.saveVersion(UserCtx.of(alice.getId()), null, new TripService.TripDraft(
                "成都三日", "CITY", "成都", null, null, "正文", null), "AI").trip().getId();
        String base = "/api/v1/trips/" + tripId;

        JsonNode info = body(call(token, HttpMethod.PUT, base + "/info",
                Map.of("title", "成都三日（改）", "people", 2, "budget", "3000", "budgetCurrency", "CNY"))).path("data");
        assertThat(info.path("title").asText()).isEqualTo("成都三日（改）");
        assertThat(info.path("people").asInt()).isEqualTo(2);

        JsonNode booking = post(base + "/bookings", Map.of("kind", "HOTEL", "title", "春熙路酒店", "platform", "携程",
                "amount", "688.00", "currency", "CNY", "status", "BOOKED")).path("data");
        long bookingId = booking.path("id").asLong();
        assertThat(booking.path("source").asText()).isEqualTo("MANUAL");
        assertThat(booking.has("userId")).isFalse();

        // 用户不能把来源写成平台同步，冒充平台确认
        assertThat(call(token, HttpMethod.POST, base + "/bookings", Map.of("kind", "HOTEL", "title", "x", "source", "SYNC"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 一条预订只能记一笔支出
        post(base + "/expenses", Map.of("category", "STAY", "title", "酒店两晚", "amount", "688", "bookingId", bookingId));
        assertThat(call(token, HttpMethod.POST, base + "/expenses",
                Map.of("category", "STAY", "title", "又记一次", "amount", "688", "bookingId", bookingId))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        post(base + "/expenses", Map.of("category", "FOOD", "title", "火锅", "amount", "20.5", "currency", "HKD"));
        JsonNode expenses = get(base + "/expenses").path("data");
        assertThat(expenses).hasSize(2);
        assertThat(get(base + "/bookings").path("data").get(0).path("expenseId").asLong())
                .isEqualTo(expenses.get(0).path("id").asLong());
        assertThat(call(token, HttpMethod.POST, base + "/expenses", Map.of("category", "FOOD", "title", "x", "amount", "1.234"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 别人看不到、改不了
        if (userService.findByUsername("bob").isEmpty()) {
            userService.create("bob", "bob-pass-123", "Bob");
        }
        String bobToken = loginAs("bob", "bob-pass-123");
        assertThat(call(bobToken, HttpMethod.GET, base + "/expenses", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(bobToken, HttpMethod.DELETE, base + "/bookings/" + bookingId, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 删预订后支出保留，只是不再关联
        assertThat(call(token, HttpMethod.DELETE, base + "/bookings/" + bookingId, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(base + "/expenses").path("data").get(0).path("bookingId").isNull()).isTrue();
    }

    @Test
    void favoritesAreDedupedSanitizedAndPrivate() throws Exception {
        Map<String, Object> hotel = Map.of("kind", "HOTEL", "refKey", "B0FFTEST01", "title", "测试酒店",
                "url", "javascript:alert(1)", "city", "成都");
        JsonNode first = post("/api/v1/favorites", hotel).path("data");
        JsonNode again = post("/api/v1/favorites", hotel).path("data");
        assertThat(again.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(first.path("url").isNull()).isTrue();
        assertThat(get("/api/v1/favorites?kind=HOTEL&q=测试").path("data")).hasSize(1);
        assertThat(call(token, HttpMethod.POST, "/api/v1/favorites", Map.of("kind", "CAR", "refKey", "x", "title", "x"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        if (userService.findByUsername("bob").isEmpty()) {
            userService.create("bob", "bob-pass-123", "Bob");
        }
        String bobToken = loginAs("bob", "bob-pass-123");
        assertThat(objectMapper.readTree(call(bobToken, HttpMethod.GET, "/api/v1/favorites", null).getBody())
                .path("data")).isEmpty();
        assertThat(call(bobToken, HttpMethod.DELETE, "/api/v1/favorites/" + first.path("id").asLong(), null)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(token, HttpMethod.DELETE, "/api/v1/favorites/" + first.path("id").asLong(), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void calendarFeedWorksWithTokenOnly() throws Exception {
        post("/api/v1/events", Map.of("title", "爬泰山", "startAt", "2026-11-07T04:00:00+08:00",
                "location", "泰山红门", "remindMinutesBefore", 60));
        String plainKey = post("/api/v1/tokens", Map.of("name", "iPhone 日历", "scope", "CALENDAR_FEED"))
                .path("data").path("plainKey").asText();

        ResponseEntity<String> feed = rest.getForEntity("/api/v1/calendar/feed/" + plainKey + ".ics", String.class);
        assertThat(feed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(feed.getBody()).contains("BEGIN:VCALENDAR").contains("SUMMARY:爬泰山").contains("DTSTART:20261106T200000Z");

        ResponseEntity<String> bad = rest.getForEntity("/api/v1/calendar/feed/lk_invalid.ics", String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Integer reminders = jdbc.queryForObject("SELECT count(*) FROM t_reminder WHERE title = '爬泰山'", Integer.class);
        assertThat(reminders).isEqualTo(1);
    }

    @Test
    void deletingEventAlsoDeletesItsReminders() throws Exception {
        long eventId = post("/api/v1/events", Map.of("title", "交电费", "startAt", "2026-11-10T09:00:00+08:00",
                "remindMinutesBefore", 30)).path("data").path("id").asLong();
        String countSql = "SELECT count(*) FROM t_reminder WHERE event_id = ?";
        assertThat(jdbc.queryForObject(countSql, Integer.class, eventId)).isEqualTo(1);

        ResponseEntity<String> response = rest.exchange("/api/v1/events/" + eventId, HttpMethod.DELETE,
                new HttpEntity<>(headers(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(countSql, Integer.class, eventId)).isZero();
    }

    @Test
    void deletingTripAlsoDeletesItsEvents() throws Exception {
        User alice = userService.findByUsername("alice").orElseThrow();
        UserCtx ctx = UserCtx.of(alice.getId());
        long tripId = tripService.saveVersion(ctx, null, new TripService.TripDraft(
                "千佛山半日", "HIKING", "济南", null, null, "正文", null), "AI").trip().getId();
        scheduleService.createEvent(ctx, new ScheduleService.NewEvent("出发去千佛山",
                OffsetDateTime.parse("2026-11-01T14:00:00+08:00"), null, null, null, "TRAVEL", String.valueOf(tripId), 15));

        assertThat(get("/api/v1/trips/" + tripId + "/events").path("data")).hasSize(1);
        assertThat(get("/api/v1/trips/" + tripId).path("data").path("trip").path("status").asText()).isEqualTo("CONFIRMED");

        ResponseEntity<String> response = rest.exchange("/api/v1/trips/" + tripId, HttpMethod.DELETE,
                new HttpEntity<>(headers(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_schedule_event WHERE source = 'TRAVEL' AND source_ref = ?",
                Integer.class, String.valueOf(tripId))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_reminder WHERE title = '出发去千佛山'", Integer.class)).isZero();
    }

    @Test
    void tripItineraryStringIsStoredAsJsonb() {
        User alice = userService.findByUsername("alice").orElseThrow();
        TripService.TripDetail trip = tripService.saveVersion(UserCtx.of(alice.getId()), null, new TripService.TripDraft(
                "杭州两日", "CITY", "杭州", null, null, "第一版", "首次生成"), "AI");
        TripVersion version = new TripVersion();
        version.setTripId(trip.trip().getId());
        version.setVersion(99);
        version.setContent("带结构化行程的版本");
        version.setItinerary("{\"days\":[{\"day\":1,\"items\":[{\"time\":\"09:00\",\"place\":\"西湖\"}]}]}");
        version.setCreatedBy("AI");

        tripVersionMapper.insert(version);

        String type = jdbc.queryForObject("SELECT jsonb_typeof(itinerary) FROM t_trip_version WHERE trip_id = ? AND version = 99",
                String.class, trip.trip().getId());
        assertThat(type).isEqualTo("object");
        String place = jdbc.queryForObject("SELECT itinerary #>> '{days,0,items,0,place}' FROM t_trip_version "
                + "WHERE trip_id = ? AND version = 99", String.class, trip.trip().getId());
        assertThat(place).isEqualTo("西湖");
    }

    @Test
    void adminUsesSeparateLogin() throws Exception {
        ResponseEntity<String> withUserToken = rest.exchange("/admin/api/v1/usage/daily", HttpMethod.GET,
                new HttpEntity<>(headers(token)), String.class);
        assertThat(withUserToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String adminToken = objectMapper.readTree(rest.postForEntity("/admin/api/v1/auth/login",
                        Map.of("username", "admin", "password", "admin-pass-123"), String.class).getBody())
                .path("data").path("token").asText();
        ResponseEntity<String> withAdminToken = rest.exchange("/admin/api/v1/usage/daily", HttpMethod.GET,
                new HttpEntity<>(headers(adminToken)), String.class);
        assertThat(withAdminToken.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- helpers ----------

    @Test
    void emailRegisterLoginResetAndBind() throws Exception {
        when(emailSender.ready()).thenReturn(true);
        assertThat(body(anon(HttpMethod.GET, "/api/v1/auth/register-options", null)).path("data").path("open").asBoolean()).isTrue();

        // 注册：要验证码，60 秒内不能再要
        String email = "Dave@Example.com";
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", email, "purpose", "REGISTER"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> again = anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", email, "purpose", "REGISTER"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(again.getBody()).contains("秒后再试");
        String code = lastCode("dave@example.com");

        // 密码太弱、验证码错都注册不了；密码弱时验证码不会被用掉
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("email", email, "code", code, "password", "short")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String wrong = code.equals("000000") ? "000001" : "000000";
        ResponseEntity<String> bad = anon(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("email", email, "code", wrong, "password", "dave-pass-123"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).contains("验证码不对");
        JsonNode registered = body(anon(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("email", email, "code", code, "password", "dave-pass-123", "nickname", "戴夫"))).path("data");
        String daveToken = registered.path("token").asText();
        assertThat(registered.path("user").path("email").asText()).isEqualTo("dave@example.com");
        assertThat(registered.path("user").path("nickname").asText()).isEqualTo("戴夫");
        // 验证码用过就作废
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("email", "dave2@example.com", "code", code, "password", "dave-pass-123")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 用邮箱登录（不区分大小写）；邮箱已注册时不能再要注册验证码
        assertThat(loginAs("DAVE@example.com", "dave-pass-123")).isNotBlank();
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", email, "purpose", "REGISTER"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 找回密码：没注册的邮箱照样回成功但不发信
        int sent = sends("nobody@example.com");
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", "nobody@example.com", "purpose", "RESET"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sends("nobody@example.com")).isEqualTo(sent);
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", email, "purpose", "RESET"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(anon(HttpMethod.POST, "/api/v1/auth/password/reset", Map.of("email", email,
                "code", lastCode("dave@example.com"), "newPassword", "dave-new-456"))).path("code").asInt()).isZero();
        // 重置后旧登录失效，新密码能登录
        assertThat(call(daveToken, HttpMethod.GET, "/api/v1/auth/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginAs("dave@example.com", "dave-new-456")).isNotBlank();

        // 验证码最多试 5 次
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", "erin@example.com", "purpose", "REGISTER"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        String erinCode = lastCode("erin@example.com");
        String erinWrong = erinCode.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < 5; i++) {
            anon(HttpMethod.POST, "/api/v1/auth/register", Map.of("email", "erin@example.com", "code", erinWrong, "password", "erin-pass-123"));
        }
        ResponseEntity<String> tooMany = anon(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("email", "erin@example.com", "code", erinCode, "password", "erin-pass-123"));
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 老用户绑定邮箱：别人的邮箱绑不了；绑好后能用邮箱登录
        assertThat(call(token, HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", email, "purpose", "BIND"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(token, HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", "alice@example.com", "purpose", "BIND"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(call(token, HttpMethod.POST, "/api/v1/auth/email/bind", Map.of("email", "alice@example.com",
                "code", lastCode("alice@example.com")))).path("data").path("email").asText()).isEqualTo("alice@example.com");
        assertThat(loginAs("alice@example.com", "alice-pass-123")).isNotBlank();
        // 没登录不能要绑定验证码
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", "x@example.com", "purpose", "BIND"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 后台关掉注册：要注册验证码报 403；用户列表能按邮箱搜到，来源是自己注册
        String admin = adminLogin("admin", "admin-pass-123");
        assertThat(call(admin, HttpMethod.PUT, "/admin/api/v1/users/register", Map.of("open", false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(body(anon(HttpMethod.GET, "/api/v1/auth/register-options", null)).path("data").path("open").asBoolean()).isFalse();
        assertThat(anon(HttpMethod.POST, "/api/v1/auth/email-code", Map.of("email", "frank@example.com", "purpose", "REGISTER"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        call(admin, HttpMethod.PUT, "/admin/api/v1/users/register", Map.of("open", true));
        JsonNode found = body(call(admin, HttpMethod.GET, "/admin/api/v1/users?keyword=dave@example", null)).path("data").path("items");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).path("source").asText()).isEqualTo("EMAIL");
    }

    /** 不带登录态调接口（注册、要验证码这些公开接口）。 */
    private ResponseEntity<String> anon(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** 发给这个邮箱的最后一封验证码邮件里的验证码（标题最后 6 位）。 */
    private String lastCode(String email) {
        String subject = null;
        for (var invocation : Mockito.mockingDetails(emailSender).getInvocations()) {
            if (invocation.getMethod().getName().equals("send") && email.equals(invocation.getArgument(0))) {
                subject = invocation.getArgument(1);
            }
        }
        assertThat(subject).as("没给 " + email + " 发过验证码").isNotNull();
        return subject.substring(subject.length() - 6);
    }

    /** 给这个邮箱发过几封信。 */
    private int sends(String email) {
        return (int) Mockito.mockingDetails(emailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send") && email.equals(i.getArgument(0))).count();
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private String loginAs(String username, String password) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", username, "password", password), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("token").asText();
    }

    private String adminLogin(String username, String password) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/admin/api/v1/auth/login",
                Map.of("username", username, "password", password), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data").path("token").asText();
    }

    private ResponseEntity<String> call(String bearer, HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers(bearer)), String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private String streamChat(String conversationId, String content) {
        HttpHeaders headers = headers(token);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        ResponseEntity<String> response = rest.exchange("/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST, new HttpEntity<>(Map.of("content", content), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode get(String path) throws Exception {
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode post(String path, Object body) throws Exception {
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private void put(String path, Object body) {
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers(token)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }
}
