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
 * 放行后复核测试：原子驳回与版本快照冻结、复核人不能为原放行人、
 * reviewKey 幂等（同参换序重放、异参 409、失败不占键）、驳回项校验与批次差异只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewApiTest {

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

    private void createCert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
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

    @Test
    void reviewRejectsSubsetAtomicallyAndFreezesSnapshot() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        submit("R-2", "INS-1", "2", "bob");
        submit("R-3", "INS-1", "3", "alice");
        String batchId = release("carol", "R-1", "R-2", "R-3");

        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-1","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"bad reading"}]}
                                """.formatted(batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewKey").value("RV-1"))
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.reviewer").value("dave"))
                .andExpect(jsonPath("$.rejected.length()").value(1))
                .andExpect(jsonPath("$.rejected[0].measurementKey").value("R-1"))
                .andExpect(jsonPath("$.rejected[0].version").value(0))
                .andExpect(jsonPath("$.rejected[0].reason").value("bad reading"));

        // 被驳回测量置 REJECTED；未驳回项保持内容与 RELEASED 状态但不再对外可用
        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.usable").value(false));
        mvc.perform(get("/api/measurements/{key}", "R-2"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.computedValue").value("2"))
                .andExpect(jsonPath("$.usable").value(false));
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(0));

        // 整批版本快照冻结：3 个位置，1 个驳回
        Integer snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_snapshot WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(3, snapshotCount);
        Integer rejectedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_snapshot WHERE batch_id = ? AND rejected = TRUE",
                Integer.class, batchId);
        assertEquals(1, rejectedCount);

        // 批次差异只读查询
        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.reviewKey").value("RV-1"))
                .andExpect(jsonPath("$.reviewer").value("dave"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.measurementKey=='R-1')].rejected").value(true))
                .andExpect(jsonPath("$.items[?(@.measurementKey=='R-1')].reason")
                        .value(org.hamcrest.Matchers.contains("bad reading")))
                .andExpect(jsonPath("$.items[?(@.measurementKey=='R-2')].rejected").value(false));
    }

    @Test
    void reviewerCannotBeOriginalReleaser() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        String batchId = release("carol", "R-1");

        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-X","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"self review"}]}
                                """.formatted(batchId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACTOR"));

        // 失败不占键：换复核人后同一 reviewKey 可成功
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-X","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"self review"}]}
                                """.formatted(batchId)))
                .andExpect(status().isOk());
    }

    @Test
    void reviewKeyReplaySameParamsReorderedAndConflictOnDifferentParams() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        submit("R-2", "INS-1", "2", "bob");
        String batchId = release("carol", "R-1", "R-2");

        String first = mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-1","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"r1"},
                                          {"measurementKey":"R-2","version":0,"reason":"r2"}]}
                                """.formatted(batchId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 同参映射换序重放：返回原结果，不产生新复核记录
        String replay = mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-1","batchId":"%s",
                                 "items":[{"measurementKey":"R-2","version":0,"reason":"r2"},
                                          {"measurementKey":"R-1","version":0,"reason":"r1"}]}
                                """.formatted(batchId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        Integer reviewCount = jdbc.queryForObject("SELECT COUNT(*) FROM review_record", Integer.class);
        assertEquals(1, reviewCount);

        // 同键异参 → 409
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-1","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"changed"}]}
                                """.formatted(batchId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void failedReviewDoesNotConsumeReviewKey() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        String batchId = release("carol", "R-1");

        // 版本不匹配 → 409 VERSION_CONFLICT，整批复核失败
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-1","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":5,"reason":"bad"}]}
                                """.formatted(batchId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_REJECTED"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("VERSION_CONFLICT"));

        // 批次与测量未被部分修改
        mvc.perform(get("/api/measurements/{key}", "R-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));
        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.reviewKey").doesNotExist());

        // 失败不占键：修正参数后同一 reviewKey 成功
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-1","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"bad"}]}
                                """.formatted(batchId)))
                .andExpect(status().isOk());
    }

    @Test
    void reviewValidationErrors() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        String batchId = release("carol", "R-1");

        // 批次不存在 → 404
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-N","batchId":"no-such-batch",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"x"}]}"""))
                .andExpect(status().isNotFound());

        // 空驳回集合 → 400
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-E","batchId":"%s","items":[]}
                                """.formatted(batchId)))
                .andExpect(status().isBadRequest());

        // 重复测量键 → 400
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-D","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"a"},
                                          {"measurementKey":"R-1","version":0,"reason":"b"}]}
                                """.formatted(batchId)))
                .andExpect(status().isBadRequest());

        // 驳回原因为空 → 400
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-B","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":" "}]}
                                """.formatted(batchId)))
                .andExpect(status().isBadRequest());

        // 测量不在批次内 → 409
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-O","batchId":"%s",
                                 "items":[{"measurementKey":"R-OTHER","version":0,"reason":"x"}]}
                                """.formatted(batchId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("MEASUREMENT_NOT_IN_BATCH"));

        // 成功复核后批次不可再次复核 → 409
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-OK","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"x"}]}
                                """.formatted(batchId)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RV-AGAIN","batchId":"%s",
                                 "items":[{"measurementKey":"R-1","version":0,"reason":"x"}]}
                                """.formatted(batchId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_ACTIVE"));
    }

    @Test
    void diffOfUnreviewedBatchAndUnknownBatch() throws Exception {
        createCert("INS-1");
        submit("R-1", "INS-1", "1", "alice");
        String batchId = release("carol", "R-1");

        // 未复核批次：差异项均为未驳回，无复核信息
        mvc.perform(get("/api/batches/{batchId}/diff", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.reviewKey").doesNotExist())
                .andExpect(jsonPath("$.successorBatchId").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].measurementKey").value("R-1"))
                .andExpect(jsonPath("$.items[0].rejected").value(false));

        mvc.perform(get("/api/batches/{batchId}/diff", "no-such-batch"))
                .andExpect(status().isNotFound());
    }
}
