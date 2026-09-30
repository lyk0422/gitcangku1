package com.example.starter.calibration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * local profile 冒烟测试：验证 H2 内存库自动建表与 data-local.sql 合成数据可正常加载，
 * 并完成题述可复现合成调用（25℃ 放行成功、重放返回同批次）。
 * 使用独立命名的内存库 calibration-local，上下文结束随 JVM 释放，不污染标准测试库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalProfileSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void seedDataLoadsAndSyntheticReleaseCallSucceeds() throws Exception {
        // 合成数据中的待放行测量存在且为 PENDING
        mvc.perform(get("/api/measurements/{key}", "M-LOCAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value("INS-ENV"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reading").value("10"));

        String body = """
                {"requestId":"RQ-LOCAL","items":[
                  {"measurementKey":"M-LOCAL","temperature":"25","humidity":"55"}]}""";
        String first = mvc.perform(MockMvcRequestBuilders.post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("RQ-LOCAL"))
                .andExpect(jsonPath("$.results[0].finalValue").value("10.0550"))
                .andExpect(jsonPath("$.results[0].compensationValue").value("0.055"))
                .andExpect(jsonPath("$.results[0].temperature").value("25"))
                .andExpect(jsonPath("$.results[0].coeffVersionId").value(1))
                .andReturn().getResponse().getContentAsString();
        String batchId = com.jayway.jsonpath.JsonPath.read(first, "$.batchId");

        // 同 requestId 重放：同一批次快照
        mvc.perform(MockMvcRequestBuilders.post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId));
    }
}
