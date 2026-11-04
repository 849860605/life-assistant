package com.lifeassistant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeassistant.common.auth.UserCtx;
import com.lifeassistant.core.conversation.ConversationService;
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
