package io.agentscope.platform.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 健康检查端点的契约测试。
 *
 * <p>用 standalone MockMvc 而不是 {@code @SpringBootTest}：这条用例要验证的是「路由与响应形状」，
 * 不需要拉起整个上下文；也避免了依赖 Spring Boot 4 里已拆分出去的测试自动配置模块。
 */
class HealthControllerTest {

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new HealthController("0.1.0")).build();

    @Test
    void healthReportsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("server"))
                .andExpect(jsonPath("$.data.version").value("0.1.0"));
    }
}
