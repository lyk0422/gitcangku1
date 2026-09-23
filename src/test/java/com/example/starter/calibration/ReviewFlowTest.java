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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 放行后复核主流程测试：复核驳回原子生效（批次 REVIEW_REQUIRED、被驳回测量 REJECTED、
 * 未驳回项内容保留但不再对外可用、整批版本快照冻结）、复核校验失败分支与幂等语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewFlowTest {

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

    private void createCert(String instrument, String a, String b) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated());
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

    @Test
    void reviewRejectsAtomicallyAndFreezesSnapshot() throws Exception {
        createCert("INS-1", "2", "1");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        submit("M-3", "INS-1", "3", "0", "9", "alice");
        String batchId = release("carol", "M-1", "M-2", "M-3");

        // 复核前全部可用
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(3));

        // 复核驳回位置 2（M-2），复核人 dave 非原放行人
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RK-1",
                                 "rejections":[{"position":2,"version":1,"reason":"读数异常"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.reviewKey").value("RK-1"))
                .andExpect(jsonPath("$.reviewer").value("dave"))
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.rejected[0]").value(2));

        // 批次状态与快照冻结
        mvc.perform(get("/api/batches/{batchId}", batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.positions.length()").value(3))
                .andExpect(jsonPath("$.positions[1].measurementKey").value("M-2"))
                .andExpect(jsonPath("$.positions[1].status").value("REJECTED"));
        String snapshot = jdbc.queryForObject(
                "SELECT snapshot FROM batch_review WHERE batch_id = ?", String.class, batchId);
        assertNotNull(snapshot);
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.contains("\"position\":2"));
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.contains("\"rejected\":true"));

        // 被驳回测量 REJECTED；未驳回项内容保留但不再对外可用
        mvc.perform(get("/api/measurements/{key}", "M-2"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.computedValue").value("5"));
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.computedValue").value("3"));
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));

        // 放行历史保留不回写
        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(3, releaseCount);
    }

    @Test
    void reviewRejectsAllPositions() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        String batchId = release("carol", "M-1", "M-2");

        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RK-ALL",
                                 "rejections":[{"position":1,"version":1,"reason":"r1"},
                                               {"position":2,"version":1,"reason":"r2"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected.length()").value(2));

        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(get("/api/measurements/{key}", "M-2"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void reviewFailuresReturnProperStatusAndRollback() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        String batchId = release("carol", "M-1", "M-2");

        // 批次不存在 → 404
        mvc.perform(post("/api/batches/{batchId}/review", "no-such-batch")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-X\",\"rejections\":[{\"position\":1,\"version\":1,\"reason\":\"r\"}]}"))
                .andExpect(status().isNotFound());

        // 复核人即原放行人 → 403
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-2\",\"rejections\":[{\"position\":1,\"version\":1,\"reason\":\"r\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REVIEWER_IS_RELEASER"));

        // 位置越界 → 409 整批失败
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RK-3",
                                 "rejections":[{"position":1,"version":1,"reason":"r"},
                                               {"position":9,"version":1,"reason":"r"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_FAILED"))
                .andExpect(jsonPath("$.failures[?(@.key=='position:9')].reasons[0]")
                        .value("POSITION_NOT_FOUND"));

        // 版本变化 → 409 整批失败
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-4\",\"rejections\":[{\"position\":1,\"version\":2,\"reason\":\"r\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1')].reasons[0]").value("VERSION_MISMATCH"));

        // 空驳回集合 → 400
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-5\",\"rejections\":[]}"))
                .andExpect(status().isBadRequest());

        // 重复位置 → 400
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"RK-6",
                                 "rejections":[{"position":1,"version":1,"reason":"r"},
                                               {"position":1,"version":1,"reason":"r2"}]}
                                """))
                .andExpect(status().isBadRequest());

        // 缺少原因 → 400
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-7\",\"rejections\":[{\"position\":1,\"version\":1}]}"))
                .andExpect(status().isBadRequest());

        // 全部失败均未改变状态，幂等键未占用
        mvc.perform(get("/api/batches/{batchId}", batchId))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        Integer idemCount = jdbc.queryForObject("SELECT COUNT(*) FROM request_idempotency", Integer.class);
        assertEquals(0, idemCount);
        Integer reviewCount = jdbc.queryForObject("SELECT COUNT(*) FROM batch_review", Integer.class);
        assertEquals(0, reviewCount);
    }

    @Test
    void reviewTwiceOnSameBatchRejected() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        String batchId = release("carol", "M-1");

        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-A\",\"rejections\":[{\"position\":1,\"version\":1,\"reason\":\"r\"}]}"))
                .andExpect(status().isOk());

        // 第二次复核（不同 reviewKey）→ 批次已非 RELEASED → 409
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-B\",\"rejections\":[{\"position\":1,\"version\":1,\"reason\":\"r\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_RELEASABLE"));
    }

    @Test
    void reviewIdempotencyReplayAndConflict() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2", "0", "9", "alice");
        String batchId = release("carol", "M-1", "M-2");

        String body = """
                {"reviewKey":"RK-IDEM",
                 "rejections":[{"position":1,"version":1,"reason":"r1"},
                               {"position":2,"version":1,"reason":"r2"}]}
                """;
        String first = mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 同参换序重放 → 返回首次结果
        String reordered = """
                {"reviewKey":"RK-IDEM",
                 "rejections":[{"position":2,"version":1,"reason":"r2"},
                               {"position":1,"version":1,"reason":"r1"}]}
                """;
        String replay = mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(reordered))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);

        // 异参（不同驳回集合）→ 409
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-IDEM\",\"rejections\":[{\"position\":1,\"version\":1,\"reason\":\"r1\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 异参（不同复核人）→ 409
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "erin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 重放未产生第二条复核记录
        Integer reviewCount = jdbc.queryForObject("SELECT COUNT(*) FROM batch_review", Integer.class);
        assertEquals(1, reviewCount);
    }

    @Test
    void failedReviewDoesNotOccupyKey() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        String batchId = release("carol", "M-1");

        // 先用错误版本提交（失败不占键）
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-RETRY\",\"rejections\":[{\"position\":1,\"version\":9,\"reason\":\"r\"}]}"))
                .andExpect(status().isConflict());

        // 同一 reviewKey 修正参数后成功
        mvc.perform(post("/api/batches/{batchId}/review", batchId)
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewKey\":\"RK-RETRY\",\"rejections\":[{\"position\":1,\"version\":1,\"reason\":\"r\"}]}"))
                .andExpect(status().isOk());
    }
}
