package com.example.starter.germination.web;

import com.example.starter.germination.AbstractGerminationH2Test;
import com.example.starter.germination.FixedClockTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 协议层测试：状态码（201/400/404/409/422）、拒绝原因与查询结果。
 */
@Import(FixedClockTestConfiguration.class)
class GerminationControllerH2Test extends AbstractGerminationH2Test {

    private static final String TAG = "ITAPI-";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected void setupScenario() {
        createBatch(TAG + "B1", "合成水稻");
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    void fullChainOverHttp_withVoidThenRelease409() throws Exception {
        mockMvc.perform(post("/api/germination/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"testNo":"%sT1","batchNo":"%sB1","sampleCount":100,"germinatedCount":86,"requestId":"%sR1"}
                                """.formatted(TAG, TAG, TAG)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ratioPercent").value(86.00));

        mockMvc.perform(post("/api/germination/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"testNo":"%sT2","batchNo":"%sB1","sampleCount":100,"germinatedCount":90,"requestId":"%sR2"}
                                """.formatted(TAG, TAG, TAG)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/germination/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"%sB1","requestId":"%sR3"}""".formatted(TAG, TAG)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstTestNo").value(TAG + "T1"))
                .andExpect(jsonPath("$.secondTestNo").value(TAG + "T2"))
                .andExpect(jsonPath("$.issuedAt").value("2026-10-01T03:00:00Z"));

        // 异参重放 → 409
        mockMvc.perform(post("/api/germination/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"%sOTHER","requestId":"%sR3"}""".formatted(TAG, TAG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENT_PARAM_MISMATCH"));

        mockMvc.perform(post("/api/germination/tests/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"testNo":"%sT1","batchNo":"%sB1","requestId":"%sR4"}
                                """.formatted(TAG, TAG, TAG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));

        // 失效证书放行 → 409
        mockMvc.perform(post("/api/germination/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"%sB1","requestId":"%sR5"}""".formatted(TAG, TAG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CERTIFICATE_INVALID"));

        // 查询链：证书 INVALID、可见依赖检测编号、未放行
        mockMvc.perform(get("/api/germination/batches/{batchNo}", TAG + "B1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests.length()").value(2))
                .andExpect(jsonPath("$.certificate.status").value("INVALID"))
                .andExpect(jsonPath("$.certificate.firstTestNo").value(TAG + "T1"))
                .andExpect(jsonPath("$.certificate.secondTestNo").value(TAG + "T2"))
                .andExpect(jsonPath("$.release.released").value(false));
    }

    @Test
    void httpStatusesForValidationNotFoundAndUnprocessable() throws Exception {
        // 样本越界 → 422
        mockMvc.perform(post("/api/germination/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"testNo":"%sT1","batchNo":"%sB1","sampleCount":500,"germinatedCount":10,"requestId":"%sR1"}
                                """.formatted(TAG, TAG, TAG)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("SAMPLE_OUT_OF_RANGE"));

        // Bean Validation 失败（空字段）→ 400
        mockMvc.perform(post("/api/germination/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testNo\":\"\",\"sampleCount\":100,\"germinatedCount\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("VALIDATION_FAILED"));

        // 批次不存在 → 404
        mockMvc.perform(get("/api/germination/batches/{batchNo}", TAG + "MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("BATCH_NOT_FOUND"));

        // 无证书放行 → 409
        mockMvc.perform(post("/api/germination/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchNo":"%sB1","requestId":"%sR2"}""".formatted(TAG, TAG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("CERTIFICATE_NOT_FOUND"));
    }
}
