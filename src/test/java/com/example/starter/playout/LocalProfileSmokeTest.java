package com.example.starter.playout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认 local profile 冒烟测试：以主资源目录的 application-local.yaml 启动，
 * 使用嵌入式 H2 命名内存库 local_playout 与主资源 schema-h2.sql 自动建表，
 * 证明无需外部 MySQL/Docker 即可启动并走通合成数据业务调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalProfileSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void localProfileServesApiWithEmbeddedH2() throws Exception {
        // 走通素材创建主流程（真实 H2 写入与主键约束）
        mvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"local-smoke-fb\",\"durationMs\":30000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("local-smoke-fb"));

        // 插播表已由 schema-h2.sql 建好：不存在的键走完整 MVC/服务/H2 链路返回 404
        mvc.perform(get("/api/emergency-overrides/local-smoke-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
