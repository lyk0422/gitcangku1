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
 * 后继修订与重新放行主流程测试：单一后继修订（保留证书与原始输入、仅改测量值与说明、版本从 1 开始）、
 * 重新放行精确映射整体成功、逐项血缘与批次差异、旧批次旧结果不可变，以及缺漏/重复/证书失效/值越界/版本变化整批失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevisionRereleaseTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM request_idempotency");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM review_item");
        jdbc.update("DELETE FROM batch_review");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String reading, String lower,
                        String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private String release(String actor, String... keys) throws Exception {
        StringBuilder keyList = new StringBuilder();
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                keyList.append(',');
            }
            keyList.append('"').append(keys[i]).append('"');
        }
        String body = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + keyList + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.batchId");
    }

    private void review(String batchId, String reviewKey, String actor, int... positions) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"position\":").append(positions[i])
                    .append(",\"version\":1,\"reason\":\"reason-").append(positions[i]).append("\"}");
        }
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"" + reviewKey + "\",\"rejections\":[" + items + "]}"))
                .andExpect(status().isOk());
    }

    private String revise(String key, String reading, String note, String actor) throws Exception {
        String body = mvc.perform(post("/api/revisions")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","reading":"%s","note":"%s"}
                                """.formatted(key, reading, note)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.measurementKey");
    }

    @Test
    void revisionKeepsCertificateAndOriginalInputsAndRestartsVersion() throws Exception {
        createCert("INS-1", "2", "1");
        submit("M-1", "INS-1", "1", "0", "9", "alice"); // computed 3
        String batchId = release("carol", "M-1");
        review(batchId, "RK-1", "dave", 1);

        // 非原提交人不能修订 → 403
        mvc.perform(post("/api/revisions").header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurementKey\":\"M-1\",\"reading\":\"4\",\"note\":\"n\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ORIGINAL_SUBMITTER"));

        // 非 REJECTED 不能修订 → 409
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        mvc.perform(post("/api/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurementKey\":\"M-2\",\"reading\":\"4\",\"note\":\"n\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REJECTED"));

        // 原提交人修订：读数 4 → 2×4+1=9；版本从 1 开始，保留证书与原始输入
        mvc.perform(post("/api/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurementKey\":\"M-1\",\"reading\":\"4\",\"note\":\"修正读数\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementKey").value("M-1#r1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revisionOfKey").value("M-1"))
                .andExpect(jsonPath("$.rootKey").value("M-1"))
                .andExpect(jsonPath("$.reading").value("4"))
                .andExpect(jsonPath("$.computedValue").value("9"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.certificateId").value(
                        jdbc.queryForObject("SELECT certificate_id FROM measurement WHERE measurement_key='M-1'",
                                Long.class).intValue()))
                .andExpect(jsonPath("$.note").value("修正读数"));

        // 单一后继：再次为同一被驳回测量创建修订 → 409
        mvc.perform(post("/api/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measurementKey\":\"M-1\",\"reading\":\"3\",\"note\":\"n2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_EXISTS"));

        // 修订链查询
        mvc.perform(get("/api/measurements/{key}/revisions", "M-1#r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootKey").value("M-1"))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].measurementKey").value("M-1"))
                .andExpect(jsonPath("$.versions[0].version").value(1))
                .andExpect(jsonPath("$.versions[0].reading").value("1"))
                .andExpect(jsonPath("$.versions[1].measurementKey").value("M-1#r1"))
                .andExpect(jsonPath("$.versions[1].version").value(1))
                .andExpect(jsonPath("$.versions[1].reading").value("4"))
                .andExpect(jsonPath("$.versions[1].note").value("修正读数"));
    }

    @Test
    void fullReReleaseBuildsLineageAndDiffAndKeepsOldBatchImmutable() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        submit("M-3", "INS-1", "3", "0", "9", "alice");
        String batchId = release("carol", "M-1", "M-2", "M-3");
        review(batchId, "RK-1", "dave", 2);
        revise("M-2", "8", "fixed", "alice"); // M-2#r1 computed 8

        String mapping = """
                [{"position":1,"measurementKey":"M-1","version":1},
                 {"position":2,"measurementKey":"M-2#r1","version":1},
                 {"position":3,"measurementKey":"M-3","version":1}]
                """;
        String body = mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-1\",\"items\":" + mapping + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newBatchId = com.jayway.jsonpath.JsonPath.read(body, "$.newBatchId");
        org.junit.jupiter.api.Assertions.assertNotNull(newBatchId);

        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/measurements/{key}", "M-2#r1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));
        // 未驳回项复用原测量行，在新批次中重新可用
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));

        // 旧批次保持 REVIEW_REQUIRED 不可变
        mvc.perform(get("/api/batches/{batchId}", batchId))
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"));
        mvc.perform(get("/api/batches/{batchId}", newBatchId))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.positions.length()").value(3));

        // 逐项血缘：位置 2 为修订
        Integer revisedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE new_batch_id = ? AND revised = TRUE",
                Integer.class, newBatchId);
        assertEquals(1, revisedCount);
        Long usedId = jdbc.queryForObject(
                "SELECT used_measurement_id FROM batch_lineage WHERE new_batch_id = ? AND position = 1",
                Long.class, newBatchId);
        Long sourceId = jdbc.queryForObject(
                "SELECT source_measurement_id FROM batch_lineage WHERE new_batch_id = ? AND position = 1",
                Long.class, newBatchId);
        assertEquals(sourceId, usedId, "未驳回位置必须复用原测量行");

        // 批次差异只读
        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newBatchId").value(newBatchId))
                .andExpect(jsonPath("$.positions[?(@.position==2)].revised").value(true))
                .andExpect(jsonPath("$.positions[?(@.position==2)].usedKey").value("M-2#r1"))
                .andExpect(jsonPath("$.positions[?(@.position==2)].sourceKey").value("M-2"))
                .andExpect(jsonPath("$.positions[?(@.position==1)].revised").value(false))
                .andExpect(jsonPath("$.positions[?(@.position==2)].usedComputed").value("8"));

        // 旧批次不可重复重新放行 → 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-2\",\"items\":" + mapping + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_ALREADY_RERELEASED"));
    }

    @Test
    void reReleaseFailuresRejectWholeBatch() throws Exception {
        long certId = createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        String batchId = release("carol", "M-1", "M-2");
        review(batchId, "RK-1", "dave", 1);
        revise("M-1", "1", "fixed", "alice"); // M-1#r1，仍合格

        // 缺漏位置（缺 position 2）→ 400（结构重复/缺漏由精确映射校验）；这里缺漏走 409 POSITION_MISSING
        // 用两项但缺一项：服务端按原批次全部位置校验
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-MISS",
                                 "items":[{"position":1,"measurementKey":"M-1#r1","version":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RERELEASE_FAILED"))
                .andExpect(jsonPath("$.failures[?(@.key=='position:2')].reasons[0]")
                        .value("POSITION_MISSING"));

        // 重复位置 → 400
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-DUP",
                                 "items":[{"position":1,"measurementKey":"M-1#r1","version":1},
                                          {"position":1,"measurementKey":"M-2","version":1}]}
                                """))
                .andExpect(status().isBadRequest());

        // 驳回项未用最新修订（错用原测量）→ 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-ORIG",
                                 "items":[{"position":1,"measurementKey":"M-1","version":1},
                                          {"position":2,"measurementKey":"M-2","version":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1')].reasons[0]")
                        .value("LATEST_REVISION_REQUIRED"));

        // 未驳回项错用别的键 → 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-WRONG",
                                 "items":[{"position":1,"measurementKey":"M-1#r1","version":1},
                                          {"position":2,"measurementKey":"M-1","version":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1')].reasons[0]")
                        .value("ORIGINAL_MEASUREMENT_REQUIRED"));

        // 版本变化 → 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-VER",
                                 "items":[{"position":1,"measurementKey":"M-1#r1","version":7},
                                          {"position":2,"measurementKey":"M-2","version":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1#r1')].reasons[0]")
                        .value("VERSION_MISMATCH"));

        // 值越界：撤销证书 → CERTIFICATE_REVOKED；再构造一条越界修订
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-REVOKED",
                                 "items":[{"position":1,"measurementKey":"M-1#r1","version":1},
                                          {"position":2,"measurementKey":"M-2","version":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1#r1')].reasons[0]")
                        .value("CERTIFICATE_REVOKED"));

        // 全部失败均整批回滚：无新批次、无血缘、requestId 未占用
        Integer batchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_batch", Integer.class);
        assertEquals(1, batchCount, "失败不得生成新批次");
        Integer lineageCount = jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class);
        assertEquals(0, lineageCount);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_idempotency WHERE kind = 'RERELEASE'", Integer.class);
        assertEquals(0, idemCount, "失败不占用 requestId");
    }

    @Test
    void outOfRangeRevisionValueRejectsReRelease() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        String batchId = release("carol", "M-1");
        review(batchId, "RK-1", "dave", 1);
        // 修订读数 100 → computed 100 超出上限 9 → passed=false
        revise("M-1", "100", "bad fix", "alice");

        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-OOR",
                                 "items":[{"position":1,"measurementKey":"M-1#r1","version":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1#r1')].reasons[0]")
                        .value("NOT_PASSED"));
    }

    @Test
    void reReleaseIdempotencyReplayAndConflict() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        String batchId = release("carol", "M-1", "M-2");
        review(batchId, "RK-1", "dave", 1);
        revise("M-1", "1", "fixed", "alice");

        String firstBody = """
                {"requestId":"REQ-IDEM",
                 "items":[{"position":1,"measurementKey":"M-1#r1","version":1},
                          {"position":2,"measurementKey":"M-2","version":1}]}
                """;
        String first = mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newBatchId = com.jayway.jsonpath.JsonPath.read(first, "$.newBatchId");

        // 同参映射换序重放 → 返回首次结果（同一新批次）
        String reordered = """
                {"requestId":"REQ-IDEM",
                 "items":[{"position":2,"measurementKey":"M-2","version":1},
                          {"position":1,"measurementKey":"M-1#r1","version":1}]}
                """;
        String replay = mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(newBatchId, com.jayway.jsonpath.JsonPath.read(replay, "$.newBatchId"));

        // 异参 → 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-IDEM",
                                 "items":[{"position":1,"measurementKey":"M-1#r1","version":1},
                                          {"position":2,"measurementKey":"M-2","version":2}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 重放不产生额外批次与血缘
        Integer batchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_batch WHERE released_by = 'frank'", Integer.class);
        assertEquals(1, batchCount);
        Integer lineageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE new_batch_id = ?", Integer.class, newBatchId);
        assertEquals(2, lineageCount);
    }

    @Test
    void reReleaseOnlyAfterReviewRequired() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        String batchId = release("carol", "M-1");
        // 未复核（RELEASED）→ 409
        mvc.perform(post("/api/batches/{batchId}/re-release", batchId)
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-EARLY",
                                 "items":[{"position":1,"measurementKey":"M-1","version":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_REVIEW_REQUIRED"));

        // 不存在批次 → 404
        mvc.perform(post("/api/batches/{batchId}/re-release", "nope")
                        .header("X-Actor-Id", "frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-404",
                                 "items":[{"position":1,"measurementKey":"M-1","version":1}]}
                                """))
                .andExpect(status().isNotFound());
    }
}
