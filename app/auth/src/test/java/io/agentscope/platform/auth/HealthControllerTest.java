package io.agentscope.platform.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 用户中心健康检查的契约测试，与工作台后端同一套写法。 */
class HealthControllerTest {

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new HealthController("0.1.0")).build();

    @Test
    void healthReportsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("auth"))
                .andExpect(jsonPath("$.data.version").value("0.1.0"));
    }
}
