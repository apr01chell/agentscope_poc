package io.agentscope.platform.server.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentscope.platform.server.event.EventRecord;
import io.agentscope.platform.server.event.EventStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * 接缝 1：HTTP 边界。会话归属、提交即返回、事件流内容都在这一层断言。
 *
 * <p>MockMvc 手动构建（Boot 4 把 @AutoConfigureMockMvc 挪到了独立模块），
 * 身份用 spring-security-test 注入，不伪造整个 OAuth 登录流程。
 */
@Testcontainers
@SpringBootTest(properties = "app.model.provider=stub")
class ConversationApiTest {

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

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousCannotReachApi() throws Exception {
        mvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
    }

    @Test
    void createListAndReopenConversation() throws Exception {
        long id = createConversation("42", "第一轮对话");

        mvc.perform(get("/api/conversations").with(authentication(asUser("42"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].title").value("第一轮对话"));

        mvc.perform(get("/api/conversations/" + id).with(authentication(asUser("42"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns").isArray());
    }

    @Test
    void anotherUserCannotSeeOrUseMyConversation() throws Exception {
        long id = createConversation("42", "私有会话");

        mvc.perform(get("/api/conversations/" + id).with(authentication(asUser("43"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/conversations/" + id + "/turns")
                        .with(authentication(asUser("43"))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"偷看\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUserCannotReadMyEventStream() throws Exception {
        long id = createConversation("42", "事件流归属");
        mvc.perform(get("/api/conversations/" + id + "/events").with(authentication(asUser("43"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitReturns202WithCursorAndStubAgentStreamsTokens() throws Exception {
        long id = createConversation("42", "流式");

        String body = mvc.perform(post("/api/conversations/" + id + "/turns")
                        .with(authentication(asUser("42"))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"你好\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.turnId").exists())
                .andExpect(jsonPath("$.data.cursor").exists())
                .andReturn().getResponse().getContentAsString();

        long turnId = ((Number) dataOf(body).get("turnId")).longValue();
        long cursor = ((Number) dataOf(body).get("cursor")).longValue();
        assertTrue(cursor > 0, "应返回游标，前端靠它续读");

        // 桩 agent 在后台线程里发事件；等 done 出现
        List<String> types = waitForDone(id);

        assertEquals("turn/queued", types.get(0), "第一个事件应是排队");
        assertTrue(types.contains("turn/started"));
        assertEquals("done", types.get(types.size() - 1), "最后一个事件应是 done");
        assertEquals(5, types.stream().filter("token"::equals).count(), "桩 agent 固定 5 个分片");

        // 从 cursor 之后读，应拿到 started 及后续（不含 queued）
        List<EventRecord> after = events.readAfter(id, cursor, 200);
        assertEquals("turn/started", after.get(0).type());

        // 落库事实：事件按 id 升序、seq 从 1 连续递增，重放可复现
        List<EventRecord> all = events.readAfter(id, 0, 200);
        assertEquals(8, all.size(), "queued + started + 5×token + done");
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 1, all.get(i).seq(), "事件序号应从 1 连续递增");
            if (i > 0) {
                assertTrue(all.get(i).id() > all.get(i - 1).id(), "id 必须严格递增，游标才可靠");
            }
        }
        assertEquals("你好", all.get(0).payload().get("input"), "queued 事件要带 input，重放才能还原用户气泡");

        // 轮次落库状态
        mvc.perform(get("/api/conversations/" + id).with(authentication(asUser("42"))))
                .andExpect(jsonPath("$.data.turns[0].status").value("DONE"))
                .andExpect(jsonPath("$.data.turns[0].seq").value(1));
        assertTrue(turnId > 0);
    }

    @Test
    void emptyInputIsRejected() throws Exception {
        long id = createConversation("42", "空输入");
        mvc.perform(post("/api/conversations/" + id + "/turns")
                        .with(authentication(asUser("42"))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------

    private long createConversation(String sub, String title) throws Exception {
        String body = mvc.perform(post("/api/conversations")
                        .with(authentication(asUser(sub))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) dataOf(body).get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(String json) {
        return (Map<String, Object>) JSON.readValue(json, Map.class).get("data");
    }

    private List<String> waitForDone(long conversationId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> types = events.readAfter(conversationId, 0, 100).stream()
                    .map(EventRecord::type)
                    .toList();
            if (types.contains("done") || types.contains("error")) {
                return types;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待 done 事件超时");
    }

    private static OAuth2AuthenticationToken asUser(String sub) {
        OidcIdToken idToken = new OidcIdToken(
                "test-id-token",
                Instant.now(),
                Instant.now().plusSeconds(600),
                Map.of("sub", sub, "tenant", "default", "name", "用户" + sub));
        OidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "workbench");
    }
}
