package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重新放行测试：全部位置精确映射、逐项血缘、旧批次不可变、整批回滚、
 * requestId 幂等（同参换序重放、异参 409、失败不占键）与第二复核周期修订链扩展。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReReleaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_lineage");
        jdbc.update("DELETE FROM batch_snapshot");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String reading, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, by)))
                .andExpect(status().isCreated());
    }

    private String release(String actor, String... keys) throws Exception {
        StringBuilder keyJson = new StringBuilder();
        for (String key : keys) {
            if (keyJson.length() > 0) {
                keyJson.append(',');
            }
            keyJson.append('"').append(key).append('"');
        }
        String body = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + keyJson + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.batchId");
    }

    private void review(String reviewKey, String batchId, String key, int version) throws Exception {
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"%s","batchId":"%s",
                                 "items":[{"measurementKey":"%s","version":%d,"reason":"bad"}]}
                                """.formatted(reviewKey, batchId, key, version)))
                .andExpect(status().isOk());
    }

    private void revise(String key, int version, String reading) throws Exception {
        mvc.perform(post("/api/measurements/{key}/revisions", key)
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"reading\":\"" + reading + "\"}"))
                .andExpect(status().isCreated());
    }

    /** 准备：3 条测量放行后驳回 R-1 并创建 v1 修订，返回原批次 ID。 */
    private String prepareReviewedBatch() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        submit("R-2", "INS-1", "2", "alice");
        submit("R-3", "INS-1", "3", "alice");
        String batchId = release("carol", "R-1", "R-2", "R-3");
        review("RV-1", batchId, "R-1", 0);
        revise("R-1", 0, "5");
        return batchId;
    }

    private String mappingJson(String requestId, String... keyVersionPairs) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < keyVersionPairs.length; i += 2) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"measurementKey\":\"").append(keyVersionPairs[i])
                    .append("\",\"version\":").append(keyVersionPairs[i + 1]).append('}');
        }
        return "{\"requestId\":\"" + requestId + "\",\"items\":[" + items + "]}";
    }

    @Test
    void reReleaseCreatesNewBatchWithLineageAndKeepsOldImmutable() throws Exception {
        String batchId = prepareReviewedBatch();

        String body = mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "1", "R-2", "0", "R-3", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceBatchId").value(batchId))
                .andExpect(jsonPath("$.releasedBy").value("erin"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].measurementKey").value("R-1"))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[1].measurementKey").value("R-2"))
                .andExpect(jsonPath("$.items[1].version").value(0))
                .andReturn().getResponse().getContentAsString();
        String newBatchId = com.jayway.jsonpath.JsonPath.read(body, "$.batchId");

        // 旧批次 SUPERSEDED 且指向后继；新批次 RELEASED 且指向来源
        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.successorBatchId").value(newBatchId));
        mvc.perform(get("/api/batches/{batchId}/diff", newBatchId))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.sourceBatchId").value(batchId));

        // 修订与未驳回项恢复可用；旧版本结果保持不可变
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.computedValue").value("5"))
                .andExpect(jsonPath("$.usable").value(true));
        mvc.perform(get("/api/measurements/{key}", "R-1").param("version", "0"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.computedValue").value("1"))
                .andExpect(jsonPath("$.usable").value(false));

        // 逐项血缘：新批次每个位置对应来源批次测量
        Integer lineageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_lineage WHERE batch_id = ?", Integer.class, newBatchId);
        assertEquals(3, lineageCount);
        var lineageRow = jdbc.queryForMap(
                "SELECT l.* FROM release_lineage l JOIN measurement m ON m.id = l.measurement_id "
                        + "WHERE l.batch_id = ? AND m.measurement_key = 'R-1'", newBatchId);
        long newMeasurementId = ((Number) lineageRow.get("measurement_id")).longValue();
        long sourceMeasurementId = ((Number) lineageRow.get("source_measurement_id")).longValue();
        org.junit.jupiter.api.Assertions.assertNotEquals(newMeasurementId, sourceMeasurementId);
        var sourceRow = jdbc.queryForMap("SELECT * FROM measurement WHERE id = ?", sourceMeasurementId);
        assertEquals(0, ((Number) sourceRow.get("version")).intValue());
        assertEquals("REJECTED", sourceRow.get("status"));
    }

    @Test
    void reReleaseMappingMustBeExactAndRollsBackWholly() throws Exception {
        String batchId = prepareReviewedBatch();

        // 缺漏 → 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-M", "R-1", "1", "R-2", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RE_RELEASE_REJECTED"))
                .andExpect(jsonPath("$.failures[?(@.key=='R-3')].reasons[0]").value("MAPPING_MISSING"));

        // 多余 → 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-X", "R-1", "1", "R-2", "0", "R-3", "0", "R-9", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='R-9')].reasons[0]")
                        .value("MAPPING_UNEXPECTED"));

        // 驳回项未用最新修订（用了原版本）→ 版本冲突
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-V", "R-1", "0", "R-2", "0", "R-3", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='R-1')].reasons[0]")
                        .value("VERSION_CONFLICT"));

        // 未驳回项版本变化 → 版本冲突
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-W", "R-1", "1", "R-2", "1", "R-3", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='R-2')].reasons[0]")
                        .value("VERSION_CONFLICT"));

        // 整批回滚：批次仍在复核状态、修订仍待放行、无新批次与血缘
        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_lineage", Integer.class));
    }

    @Test
    void reReleaseWithoutRevisionFailsRevisionMissing() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        String batchId = release("carol", "R-1");
        review("RV-1", batchId, "R-1", 0);

        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='R-1')].reasons[0]")
                        .value("REVISION_MISSING"));
    }

    @Test
    void reReleaseWithRevokedCertificateFails() throws Exception {
        String batchId = prepareReviewedBatch();
        long certId = jdbc.queryForObject(
                "SELECT certificate_id FROM measurement WHERE measurement_key = 'R-1' AND version = 0",
                Long.class);
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "1", "R-2", "0", "R-3", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='R-1')].reasons[0]")
                        .value("CERTIFICATE_REVOKED"));

        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
    }

    @Test
    void reReleaseWithOutOfRangeRevisionFails() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        String batchId = release("carol", "R-1");
        review("RV-1", batchId, "R-1", 0);
        revise("R-1", 0, "100");   // 越界：上限 9

        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='R-1')].reasons[0]").value("NOT_PASSED"));

        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void reReleaseIdempotencyReplayConflictAndFailureKeepsKey() throws Exception {
        String batchId = prepareReviewedBatch();

        // 失败不占键：缺漏映射失败后，同一 requestId 修正参数可成功
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "1", "R-2", "0")))
                .andExpect(status().isConflict());

        String body = mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "1", "R-2", "0", "R-3", "0")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newBatchId = com.jayway.jsonpath.JsonPath.read(body, "$.batchId");

        // 同参映射换序重放：返回原批次
        String replay = mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-3", "0", "R-1", "1", "R-2", "0")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(body, replay);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_batch", Integer.class));

        // 同键异参 → 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "1", "R-2", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        org.junit.jupiter.api.Assertions.assertEquals(newBatchId,
                com.jayway.jsonpath.JsonPath.read(replay, "$.batchId"));
    }

    @Test
    void reReleaseRequiresReviewedBatch() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        String batchId = release("carol", "R-1");

        // 生效中批次不可重新放行
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_UNDER_REVIEW"));

        // 批次不存在 → 404
        mvc.perform(post("/api/batches/{batchId}/re-release", "no-such-batch")
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-2", "R-1", "0")))
                .andExpect(status().isNotFound());
    }

    @Test
    void secondReviewCycleExtendsRevisionChain() throws Exception {
        String batchId = prepareReviewedBatch();

        // 第一次重新放行
        String body1 = mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-1", "R-1", "1", "R-2", "0", "R-3", "0")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String batch2 = com.jayway.jsonpath.JsonPath.read(body1, "$.batchId");

        // 第二周期：复核新批次驳回 R-1 的 v1，创建 v2，再次重新放行
        review("RV-2", batch2, "R-1", 1);
        revise("R-1", 1, "6");
        String body2 = mvc.perform(post("/api/batches/{batchId}/re-release", batch2)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mappingJson("RR-2", "R-1", "2", "R-2", "0", "R-3", "0")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String batch3 = com.jayway.jsonpath.JsonPath.read(body2, "$.batchId");

        // 修订链 v0 → v1 → v2
        mvc.perform(get("/api/measurements/{key}/revisions", "R-1"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[1].status").value("REJECTED"))
                .andExpect(jsonPath("$[2].version").value(2))
                .andExpect(jsonPath("$[2].status").value("RELEASED"))
                .andExpect(jsonPath("$[2].computedValue").value("6"));

        // 批次链：batch1 SUPERSEDED、batch2 SUPERSEDED、batch3 RELEASED
        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.successorBatchId").value(batch2));
        mvc.perform(get("/api/batches/{batchId}/diff", batch2))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.successorBatchId").value(batch3));
        mvc.perform(get("/api/batches/{batchId}/diff", batch3))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.sourceBatchId").value(batch2));

        // 当前可用为 v2 与两个原始版本
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.usable").value(true));
    }
}
