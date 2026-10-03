package com.example.starter;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 授粉窗口 API 集成测试：真实 H2（MODE=MySQL）库上验证成功、错误与幂等场景。
 * 合成花序 2026-10-04T10:00:00Z 开放、24 小时窗口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseCleanup.class)
class PollinationApiTest {

    private static final String OPEN = "2026-10-04T10:00:00Z";
    private static final String IN_WINDOW = "2026-10-05T09:59:00Z";
    private static final String WINDOW_END = "2026-10-05T10:00:00Z";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM pollination_confirmation");
        jdbc.update("DELETE FROM pollination_operation");
        jdbc.update("DELETE FROM inflorescence");
        jdbc.update("DELETE FROM pollen_batch");
    }

    private long createInflorescence(String code, String firstOpenAt) throws Exception {
        String body = om.writeValueAsString(Map.of("code", code, "firstOpenAt", firstOpenAt));
        String json = mvc.perform(post("/api/inflorescences").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private void createBatch(String batchNo) throws Exception {
        mvc.perform(post("/api/pollen-batches").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("batchNo", batchNo))))
                .andExpect(status().isCreated());
    }

    private ResultActions pollinate(long id, String requestId, String batchNo, String technician,
                                    String executedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("pollenBatchNo", batchNo);
        body.put("technician", technician);
        body.put("executedAt", executedAt);
        return mvc.perform(post("/api/inflorescences/{id}/pollinations", id)
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private long pollinateOk(long id, String requestId, String batchNo, String executedAt) throws Exception {
        String json = pollinate(id, requestId, batchNo, "tech-a", executedAt)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asLong();
    }

    private ResultActions confirm(long operationId, String requestId, String result, String confirmedAt)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("result", result);
        body.put("technician", "tech-b");
        body.put("confirmedAt", confirmedAt);
        return mvc.perform(post("/api/pollinations/{operationId}/confirmation", operationId)
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    private ResultActions revoke(long operationId, String revokedAt) throws Exception {
        return mvc.perform(post("/api/pollinations/{operationId}/revoke", operationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("revokedAt", revokedAt))));
    }

    private ResultActions removeInflorescence(long id, String removedAt) throws Exception {
        return mvc.perform(post("/api/inflorescences/{id}/removal", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("removedAt", removedAt))));
    }
    @Test
    void successFlowAndTimeline() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T1", OPEN);
        long op = pollinateOk(inf, "req-t1-op", "PB-1", IN_WINDOW);

        confirm(op, "req-t1-conf", "SUCCESS", "2026-10-05T10:30:00Z").andExpect(status().isCreated());

        mvc.perform(get("/api/inflorescences/{id}/timeline", inf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("INF-T1"))
                .andExpect(jsonPath("$.window.firstOpenAt").value(OPEN))
                .andExpect(jsonPath("$.window.closeAt").value(WINDOW_END))
                .andExpect(jsonPath("$.window.windowHours").value(24))
                .andExpect(jsonPath("$.confirmedPollenBatchNo").value("PB-1"))
                .andExpect(jsonPath("$.operations", hasSize(1)))
                .andExpect(jsonPath("$.operations[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.operations[0].pollenBatchNo").value("PB-1"))
                .andExpect(jsonPath("$.operations[0].confirmation.result").value("SUCCESS"));
    }

    @Test
    void windowBoundaryRejected() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T2", OPEN);

        // 窗口前：09:59:59 首日拒绝
        pollinate(inf, "req-t2-a", "PB-1", "tech-a", "2026-10-04T09:59:59Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_NOT_STARTED"));
        // 窗口终点次日 10:00 拒绝
        pollinate(inf, "req-t2-b", "PB-1", "tech-a", WINDOW_END)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_EXPIRED"));
        // 失败操作不占花序：次日 09:59 仍可授粉
        pollinate(inf, "req-t2-c", "PB-1", "tech-a", IN_WINDOW)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));
    }

    @Test
    void removedInflorescenceRejected() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T3", OPEN);
        removeInflorescence(inf, "2026-10-04T12:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.removedAt").value("2026-10-04T12:00:00Z"));

        pollinate(inf, "req-t3-a", "PB-1", "tech-a", IN_WINDOW)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INFLORESCENCE_REMOVED"));
    }

    @Test
    void disabledAndUnknownBatchRejected() throws Exception {
        createBatch("PB-OLD");
        mvc.perform(post("/api/pollen-batches/PB-OLD/deactivation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        long inf = createInflorescence("INF-T4", OPEN);

        pollinate(inf, "req-t4-a", "PB-OLD", "tech-a", IN_WINDOW)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POLLEN_BATCH_DISABLED"));
        pollinate(inf, "req-t4-b", "PB-MISSING", "tech-a", IN_WINDOW)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POLLEN_BATCH_NOT_FOUND"));
    }

    @Test
    void duplicateInflorescenceCodeConflict() throws Exception {
        createInflorescence("INF-DUP", OPEN);
        mvc.perform(post("/api/inflorescences").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "INF-DUP", "firstOpenAt", OPEN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INFLORESCENCE_CODE_EXISTS"));
    }
    @Test
    void duplicateEffectiveOperationConflict() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T5", OPEN);
        long op = pollinateOk(inf, "req-t5-a", "PB-1", IN_WINDOW);

        pollinate(inf, "req-t5-b", "PB-1", "tech-b", IN_WINDOW)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_OPERATION_EXISTS"));

        // 失败操作不占花序：花序仍只被第一个操作占据
        mvc.perform(get("/api/inflorescences/{id}/timeline", inf))
                .andExpect(jsonPath("$.operations", hasSize(1)))
                .andExpect(jsonPath("$.operations[0].id").value(op));
    }

    @Test
    void pollinateIdempotentReplayAndMismatch() throws Exception {
        createBatch("PB-1");
        createBatch("PB-2");
        long inf = createInflorescence("INF-T6", OPEN);
        long op = pollinateOk(inf, "req-t6", "PB-1", IN_WINDOW);

        // 同参重放：返回同一记录，不产生新操作
        String replay = pollinate(inf, "req-t6", "PB-1", "tech-a", IN_WINDOW)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode replayed = om.readTree(replay);
        org.junit.jupiter.api.Assertions.assertEquals(op, replayed.get("id").asLong());
        mvc.perform(get("/api/inflorescences/{id}/timeline", inf))
                .andExpect(jsonPath("$.operations", hasSize(1)));

        // 异参重放：409
        pollinate(inf, "req-t6", "PB-2", "tech-a", IN_WINDOW)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_MISMATCH"));
    }

    @Test
    void confirmSuccessFixesBatchAndSurvivesRemoval() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T7", OPEN);
        long op = pollinateOk(inf, "req-t7-op", "PB-1", IN_WINDOW);
        confirm(op, "req-t7-conf", "SUCCESS", "2026-10-05T11:00:00Z").andExpect(status().isCreated());

        // 成功结果固化批号后，摘除花序也不能抹掉历史
        removeInflorescence(inf, "2026-10-05T12:00:00Z").andExpect(status().isOk());
        mvc.perform(get("/api/inflorescences/{id}/timeline", inf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedAt").value("2026-10-05T12:00:00Z"))
                .andExpect(jsonPath("$.confirmedPollenBatchNo").value("PB-1"))
                .andExpect(jsonPath("$.confirmedAt").value("2026-10-05T11:00:00Z"))
                .andExpect(jsonPath("$.operations[0].confirmation.result").value("SUCCESS"));
    }

    @Test
    void confirmAfter72HoursRejected() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T8", OPEN);
        long op = pollinateOk(inf, "req-t8-op", "PB-1", "2026-10-04T12:00:00Z");

        // 操作后 72 小时整：允许
        long op2boundary = op;
        confirm(op2boundary, "req-t8-conf", "SUCCESS", "2026-10-07T12:00:00Z")
                .andExpect(status().isCreated());

        // 另一花序：超过 72 小时拒绝
        long inf2 = createInflorescence("INF-T8B", OPEN);
        long op2 = pollinateOk(inf2, "req-t8b-op", "PB-1", "2026-10-04T12:00:00Z");
        confirm(op2, "req-t8b-conf", "SUCCESS", "2026-10-07T12:00:01Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONFIRM_WINDOW_EXPIRED"));
    }

    @Test
    void confirmIdempotencyAndSecondConfirmConflict() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T9", OPEN);
        long op = pollinateOk(inf, "req-t9-op", "PB-1", IN_WINDOW);
        String first = confirm(op, "req-t9-conf", "SUCCESS", "2026-10-05T10:30:00Z")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long confId = om.readTree(first).get("id").asLong();

        // 同参重放返回同一确认
        String replay = confirm(op, "req-t9-conf", "SUCCESS", "2026-10-05T10:30:00Z")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(confId, om.readTree(replay).get("id").asLong());

        // 同键异参 409
        confirm(op, "req-t9-conf", "FAILED", "2026-10-05T10:30:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_MISMATCH"));
        // 同一操作换键再确认 409
        confirm(op, "req-t9-conf2", "SUCCESS", "2026-10-05T10:31:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_ALREADY_CONFIRMED"));
    }
    @Test
    void revokeThenRepollinateWithinWindow() throws Exception {
        createBatch("PB-1");
        createBatch("PB-2");
        long inf = createInflorescence("INF-T10", OPEN);
        long op = pollinateOk(inf, "req-t10-op", "PB-1", IN_WINDOW);

        revoke(op, "2026-10-05T09:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 重复撤销幂等
        revoke(op, "2026-10-05T09:00:00Z").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 窗口未过期可重新授粉
        pollinateOk(inf, "req-t10-op2", "PB-2", IN_WINDOW);
        mvc.perform(get("/api/inflorescences/{id}/timeline", inf))
                .andExpect(jsonPath("$.operations", hasSize(2)))
                .andExpect(jsonPath("$.operations[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.operations[1].status").value("EFFECTIVE"));
    }

    @Test
    void revokeAfterWindowExpiredCannotRepollinate() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T11", OPEN);
        long op = pollinateOk(inf, "req-t11-op", "PB-1", "2026-10-04T23:00:00Z");
        revoke(op, "2026-10-05T10:30:00Z").andExpect(status().isOk());

        // 撤销后窗口已过期，重做被拒绝
        pollinate(inf, "req-t11-op2", "PB-1", "tech-a", WINDOW_END)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_EXPIRED"));
    }

    @Test
    void revokeConfirmedRejectedAndConfirmRevokedRejected() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T12", OPEN);
        long op = pollinateOk(inf, "req-t12-op", "PB-1", IN_WINDOW);
        confirm(op, "req-t12-conf", "SUCCESS", "2026-10-05T10:30:00Z").andExpect(status().isCreated());

        revoke(op, "2026-10-05T11:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPERATION_ALREADY_CONFIRMED"));

        long inf2 = createInflorescence("INF-T12B", OPEN);
        long op2 = pollinateOk(inf2, "req-t12b-op", "PB-1", IN_WINDOW);
        revoke(op2, "2026-10-05T09:00:00Z").andExpect(status().isOk());
        confirm(op2, "req-t12b-conf", "SUCCESS", "2026-10-05T10:30:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPERATION_REVOKED"));
    }

    @Test
    void failedConfirmationFreesInflorescence() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T13", OPEN);
        long op = pollinateOk(inf, "req-t13-op", "PB-1", IN_WINDOW);
        confirm(op, "req-t13-conf", "FAILED", "2026-10-05T10:30:00Z").andExpect(status().isCreated());

        // 失败结果不占花序：窗口内可重新授粉
        pollinateOk(inf, "req-t13-op2", "PB-1", IN_WINDOW);
        mvc.perform(get("/api/inflorescences/{id}/timeline", inf))
                .andExpect(jsonPath("$.confirmedPollenBatchNo", nullValue()))
                .andExpect(jsonPath("$.operations", hasSize(2)));
    }

    @Test
    void confirmOnRemovedInflorescenceRejected() throws Exception {
        createBatch("PB-1");
        long inf = createInflorescence("INF-T14", OPEN);
        long op = pollinateOk(inf, "req-t14-op", "PB-1", IN_WINDOW);
        removeInflorescence(inf, "2026-10-05T10:00:00Z").andExpect(status().isOk());

        confirm(op, "req-t14-conf", "SUCCESS", "2026-10-05T10:30:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INFLORESCENCE_REMOVED"));
    }

    @Test
    void timelineNotFound() throws Exception {
        mvc.perform(get("/api/inflorescences/{id}/timeline", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INFLORESCENCE_NOT_FOUND"));
    }
}