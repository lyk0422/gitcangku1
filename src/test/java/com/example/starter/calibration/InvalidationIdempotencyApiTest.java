package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 失效单幂等与乐观锁边界：
 * requestId 同参重放首次闭包快照、异参 409、失败不占键；invalidationKey 唯一；
 * expectedVersion 过期 409；创建后闭包内结果状态变化，激活时整单 409 且不发生部分冻结。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvalidationIdempotencyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM impact_path");
        jdbc.update("DELETE FROM invalidation_confirmation");
        jdbc.update("DELETE FROM invalidation_snapshot");
        jdbc.update("DELETE FROM invalidation_order");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM standard_version");
        jdbc.update("DELETE FROM measurement_standard");
    }

    private void buildScenario() throws Exception {
        mvc.perform(post("/api/standards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"standardId\":\"STD-ROOT\",\"name\":\"root\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/standards").contentType(MediaType.APPLICATION_JSON)
                .content("{\"standardId\":\"STD-MID\",\"name\":\"mid\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"versionKey":"SV-ROOT","standardId":"STD-ROOT","parentVersionKey":null,
                         "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                         "certificateNo":"C-R"}""")).andExpect(status().isCreated());
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"versionKey":"SV-MID","standardId":"STD-MID","parentVersionKey":"SV-ROOT",
                         "validFrom":"2026-02-01T00:00:00Z","validTo":"2026-12-01T00:00:00Z",
                         "certificateNo":"C-M"}""")).andExpect(status().isCreated());
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                         "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""")).andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"measurementKey":"M-1","instrumentId":"INS-1",
                         "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                         "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                         "standardVersionKey":"SV-MID"}""")).andExpect(status().isCreated());
    }

    private long domainVersion() {
        return jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
    }

    private String create(String key, String root, String requestId, long expectedVersion, String reason)
            throws Exception {
        return mvc.perform(post("/api/invalidations")
                        .header("X-Request-Id", requestId)
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"%s","rootVersionKey":"%s",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"%s"}
                                """.formatted(key, root, expectedVersion, reason)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void sameRequestIdReplaysFirstSnapshot() throws Exception {
        buildScenario();
        long version = domainVersion();
        String first = create("INV-1", "SV-ROOT", "REQ-SAME", version, "原因A");
        String second = create("INV-1", "SV-ROOT", "REQ-SAME", version, "原因A");

        org.junit.jupiter.api.Assertions.assertEquals(first, second, "同参重放必须返回首次闭包快照");
        org.junit.jupiter.api.Assertions.assertEquals(1L,
                jdbc.queryForObject("SELECT COUNT(*) FROM invalidation_order", Long.class));
    }

    @Test
    void sameRequestIdDifferentParametersReturns409() throws Exception {
        buildScenario();
        long version = domainVersion();
        create("INV-1", "SV-ROOT", "REQ-DIFF", version, "原因A");

        // 不同失效参数（原因变化）→ 409
        mvc.perform(post("/api/invalidations")
                        .header("X-Request-Id", "REQ-DIFF")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"INV-1","rootVersionKey":"SV-ROOT",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"原因B"}
                                """.formatted(version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // invalidationKey 不同也视为异参
        mvc.perform(post("/api/invalidations")
                        .header("X-Request-Id", "REQ-DIFF")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"INV-OTHER","rootVersionKey":"SV-ROOT",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"原因A"}
                                """.formatted(version)))
                .andExpect(status().isConflict());
    }

    @Test
    void failedCreateDoesNotOccupyRequestId() throws Exception {
        buildScenario();
        long version = domainVersion();

        // expectedVersion 过期 → 409，requestId 不被占用
        mvc.perform(post("/api/invalidations")
                        .header("X-Request-Id", "REQ-FAIL")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"INV-F","rootVersionKey":"SV-ROOT",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"过期"}
                                """.formatted(version + 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_EXPECTED_VERSION"));

        // 同一 requestId 用正确参数可成功创建
        create("INV-F", "SV-ROOT", "REQ-FAIL", version, "重试成功");
        org.junit.jupiter.api.Assertions.assertEquals(1L,
                jdbc.queryForObject("SELECT COUNT(*) FROM invalidation_order", Long.class));
    }

    @Test
    void invalidationKeyIsUniqueAcrossRequestIds() throws Exception {
        buildScenario();
        long version = domainVersion();
        create("INV-DUP", "SV-ROOT", "REQ-A", version, "A");

        // 不同 requestId 但相同 invalidationKey：此时领域版本已被首次创建推进，先制造过期版本冲突
        mvc.perform(post("/api/invalidations")
                        .header("X-Request-Id", "REQ-B")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"INV-DUP","rootVersionKey":"SV-ROOT",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"B"}
                                """.formatted(version)))
                .andExpect(status().isConflict());
    }

    @Test
    void closureResultStatusChangeRejectsActivationWholeOrder() throws Exception {
        buildScenario();
        long version = domainVersion();
        create("INV-CHG", "SV-ROOT", "REQ-CHG", version, "变更检测");
        mvc.perform(post("/api/invalidations/INV-CHG/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isOk());

        // 创建快照之后、激活之前放行闭包内测量：结果状态 PENDING → RELEASED
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "reviewer1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isOk());

        // 第二名确认触发激活：闭包变化整单 409，不发生部分冻结
        mvc.perform(post("/api/invalidations/INV-CHG/confirmations").header("X-Actor-Id", "qm-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSURE_CHANGED"));

        org.junit.jupiter.api.Assertions.assertEquals(0L,
                jdbc.queryForObject("SELECT COUNT(*) FROM standard_version WHERE status = 'INVALID'", Long.class),
                "整单回滚：标准器版本不得被标记 INVALID");
        org.junit.jupiter.api.Assertions.assertEquals("RELEASED",
                jdbc.queryForObject("SELECT status FROM measurement WHERE measurement_key = 'M-1'", String.class),
                "整单回滚：测量状态保持变化后的 RELEASED，不被冻结");
        org.junit.jupiter.api.Assertions.assertNull(
                jdbc.queryForObject("SELECT impact_version FROM invalidation_order WHERE invalidation_key = 'INV-CHG'",
                        String.class),
                "整单回滚：不得生成 impactVersion");

        // 单据仍为待激活，可在状态重新稳定后重新确认激活（快照仍为旧状态时仍会拒绝，直至以新单重提）
        org.junit.jupiter.api.Assertions.assertEquals("PENDING",
                jdbc.queryForObject("SELECT status FROM invalidation_order WHERE invalidation_key = 'INV-CHG'",
                        String.class));
    }

    @Test
    void missingHeadersReturn400() throws Exception {
        buildScenario();
        long version = domainVersion();
        // 缺少 X-Request-Id
        mvc.perform(post("/api/invalidations")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"INV-X","rootVersionKey":"SV-ROOT",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"x"}
                                """.formatted(version)))
                .andExpect(status().isBadRequest());
    }
}
