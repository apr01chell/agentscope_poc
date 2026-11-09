package io.agentscope.platform.server.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.platform.server.event.EventRecord;
import io.agentscope.platform.server.event.EventStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * 接缝 1：并发控制。
 *
 * <p>用放慢的桩（每片 400ms，共 2s）让"执行中 / 排队 / 超时"三个状态可被稳定观察到；
 * 排队超时压到 1 秒，避免测试等 10 分钟。
 */
@Testcontainers
@SpringBootTest(properties = {
        "app.model.provider=stub",
        "app.model.stub-chunk-delay-ms=600",
        "app.turn.queue-timeout-seconds=2"
})
class TurnConcurrencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agent_platform").withUsername("app").withPassword("apppass");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired WebApplicationContext context;
    @Autowired EventStore events;
    @Autowired ConversationRepository repository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void secondRequestQueuesAndThirdIsRejected() throws Exception {
        long conversation = createConversation("42");

        MvcResult first = submit(conversation, "第一个");
        assertEquals(202, first.getResponse().getStatus());
        assertEquals(1, data(first).get("position"), "第一个应当立即执行");

        MvcResult second = submit(conversation, "第二个");
        assertEquals(202, second.getResponse().getStatus());
        assertEquals(2, data(second).get("position"), "第二个应进入排队");

        // 第三个直接拒：不是"报错"，而是"稍后重试"
        mvc.perform(post("/api/conversations/" + conversation + "/turns")
                        .with(authentication(asUser("42"))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"第三个\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TURN_QUEUE_FULL"));
    }

    @Test
    void cancellingQueuedTurnEmitsCancelledNotInterrupted() throws Exception {
        long conversation = createConversation("42");
        long running = turnId(submit(conversation, "第一个"));
        long queued = turnId(submit(conversation, "第二个"));

        mvc.perform(post("/api/conversations/" + conversation + "/turns/" + queued + "/cancel")
                        .with(authentication(asUser("42"))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("QUEUED"));

        EventRecord cancelled = awaitEvent(queued, "turn/cancelled", 3000).orElseThrow();
        assertEquals("QUEUED", cancelled.payload().get("stage"));
        assertEquals("CANCELLED", statusOf(queued));

        // 正在跑的那一轮不受影响，应继续到完成
        assertTrue(awaitEvent(running, "done", 15000).isPresent(), "排队取消不应波及执行中的轮次");
        assertEquals("DONE", statusOf(running));
    }

    @Test
    void cancellingRunningTurnEmitsInterrupted() throws Exception {
        long conversation = createConversation("42");
        long running = turnId(submit(conversation, "第一个"));
        awaitStatus(running, "RUNNING", 3000);

        mvc.perform(post("/api/conversations/" + conversation + "/turns/" + running + "/cancel")
                        .with(authentication(asUser("42"))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("RUNNING"));

        EventRecord interrupted = awaitEvent(running, "turn/interrupted", 3000).orElseThrow();
        assertEquals("RUNNING", interrupted.payload().get("stage"));
        assertEquals("CANCELLED", statusOf(running));
    }

    @Test
    void queuedTurnTimesOutWithReadableReason() throws Exception {
        long conversation = createConversation("42");
        submit(conversation, "第一个");
        long queued = turnId(submit(conversation, "第二个"));

        // 排队超时配的是 1 秒（第一个要跑 2 秒）
        EventRecord error = awaitEvent(queued, "turn/error", 8000).orElseThrow();
        String message = String.valueOf(error.payload().get("message"));
        assertTrue(message.contains("排队超时"), "应给出可读原因：" + message);
        assertEquals("FAILED", statusOf(queued));
    }

    @Test
    void cancellingFinishedTurnIsConflict() throws Exception {
        long conversation = createConversation("42");
        long turn = turnId(submit(conversation, "只有一轮"));
        awaitEvent(turn, "done", 15000).orElseThrow();

        mvc.perform(post("/api/conversations/" + conversation + "/turns/" + turn + "/cancel")
                        .with(authentication(asUser("42"))).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_CANCELLABLE"));
    }

    // ---------- helpers ----------

    private long createConversation(String sub) throws Exception {
        MvcResult result = mvc.perform(post("/api/conversations")
                        .with(authentication(asUser(sub))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"并发\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) data(result).get("id")).longValue();
    }

    private MvcResult submit(long conversation, String input) throws Exception {
        return mvc.perform(post("/api/conversations/" + conversation + "/turns")
                        .with(authentication(asUser(sub))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("input", input))))
                .andReturn();
    }

    private static String sub = "42";

    private static long turnId(MvcResult result) throws Exception {
        return ((Number) data(result).get("turnId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(MvcResult result) throws Exception {
        Map<String, Object> body = JSON.readValue(result.getResponse().getContentAsString(), Map.class);
        return (Map<String, Object>) body.get("data");
    }

    private Optional<EventRecord> awaitEvent(long turnId, String type, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<EventRecord> all = events.readAfter(conversationOf(turnId), 0, 200);
            Optional<EventRecord> hit = all.stream()
                    .filter(r -> r.turnId() == turnId && r.type().equals(type))
                    .findFirst();
            if (hit.isPresent()) {
                return hit;
            }
            Thread.sleep(50);
        }
        return Optional.empty();
    }

    private long conversationOf(long turnId) {
        return ((Number) repository.findTurn(turnId).orElseThrow().get("conversationId")).longValue();
    }

    private void awaitStatus(long turnId, String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(statusOf(turnId))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("轮次未进入状态 " + expected + "，当前=" + statusOf(turnId));
    }

    private String statusOf(long turnId) {
        return String.valueOf(repository.findTurn(turnId).orElseThrow().get("status"));
    }

    private OAuth2AuthenticationToken asUser(String sub) {
        Map<String, Object> claims = Map.of(
                "sub", sub,
                "name", "测试用户",
                "tenant", "default",
                "role", List.of("USER"),
                "perms", List.of(),
                "iat", Instant.now().getEpochSecond(),
                "exp", Instant.now().plusSeconds(600).getEpochSecond());
        OidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(600), claims),
                "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "workbench");
    }
}
