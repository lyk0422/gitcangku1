package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测采样许可 API 测试：许可范围与交集冲突、提前结束、撤销复核标记与快照、
 * 计数查询、幂等重放与失败不占键（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermitApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM permit_review_snapshot");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM permit_device_lock");
    }

    private ResultActions createPermit(String requestId, String permitId, String deviceId,
                                       int xMin, int xMax, int yMin, int yMax,
                                       String validFrom, String validTo,
                                       int maxObservations, int permitVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("permitId", permitId);
        body.put("deviceId", deviceId);
        body.put("gridXMin", xMin);
        body.put("gridXMax", xMax);
        body.put("gridYMin", yMin);
        body.put("gridYMax", yMax);
        body.put("validFromUtc", validFrom);
        body.put("validToUtc", validTo);
        body.put("maxObservations", maxObservations);
        body.put("permitVersion", permitVersion);
        return mockMvc.perform(post("/api/permits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions endPermit(String requestId, String permitId, String endAtUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("endAtUtc", endAtUtc);
        return mockMvc.perform(post("/api/permits/{id}/end", permitId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions revokePermit(String requestId, String permitId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        return mockMvc.perform(post("/api/permits/{id}/revoke", permitId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getPermit(String permitId) throws Exception {
        return mockMvc.perform(get("/api/permits/{id}", permitId));
    }

    private ResultActions submit(String requestId, String observationId, String deviceId,
                                 int gridX, int gridY, String collectedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("deviceId", deviceId);
        body.put("gridX", gridX);
        body.put("gridY", gridY);
        body.put("collectedAtUtc", collectedAt);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String location) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "备注");
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private int usedCount(String permitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? -1 : count;
    }

    // ---------- 许可创建与交集冲突 ----------

    @Test
    void createPermitReturnsCountsAndPersists() throws Exception {
        createPermit("req-p1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 100, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.deviceId").value("DEV-1"))
                .andExpect(jsonPath("$.maxObservations").value(100))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(100))
                .andExpect(jsonPath("$.permitVersion").value(3))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        getPermit("permit-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(100))
                .andExpect(jsonPath("$.validFromUtc").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.validToUtc").value("2026-02-01T00:00:00Z"));
    }

    @Test
    void createPermitOverlappingInGridAndTimeReturns409() throws Exception {
        createPermit("req-p2", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 网格相交且时间相交：409 PERMIT_OVERLAP
        createPermit("req-p3", "permit-2", "DEV-1", 5, 15, 5, 15,
                "2026-02-01T00:00:00Z", "2026-04-01T00:00:00Z", 100, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_OVERLAP"));
        // 失败的许可未落库
        getPermit("permit-2").andExpect(status().isNotFound());
    }

    @Test
    void createPermitTouchingTimeBoundaryIsAllowed() throws Exception {
        createPermit("req-p4", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 左闭右开：新许可从上一许可结束时刻开始，时间交集为空，允许创建
        createPermit("req-p5", "permit-2", "DEV-1", 0, 10, 0, 10,
                "2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
    }

    @Test
    void createPermitDisjointGridOrDifferentDeviceIsAllowed() throws Exception {
        createPermit("req-p6", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 网格不相交：允许
        createPermit("req-p7", "permit-2", "DEV-1", 20, 30, 0, 10,
                "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 不同设备：网格与时间均重叠也允许
        createPermit("req-p8", "permit-3", "DEV-2", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-03-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
    }

    @Test
    void createPermitWithInvalidWindowOrGridReturns400() throws Exception {
        createPermit("req-p9", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z", 100, 1)
                .andExpect(status().isBadRequest());
        createPermit("req-p10", "permit-1", "DEV-1", 10, 0, 0, 10,
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 100, 1)
                .andExpect(status().isBadRequest());
        getPermit("permit-1").andExpect(status().isNotFound());
    }

    @Test
    void createPermitSameRequestIdReplaysAndDifferentParamsConflict() throws Exception {
        createPermit("req-p11", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 同键同参重放：返回原结果，不重复创建
        createPermit("req-p11", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-1"));
        // 同键异参：409
        createPermit("req-p11", "permit-9", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", 100, 1)
                .andExpect(status().isConflict());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_permit", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    // ---------- 提前结束 ----------

    @Test
    void endPermitEarlyOnlyAffectsLaterSamples() throws Exception {
        createPermit("req-e1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 结束前采样成功
        submit("req-e2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated());

        endPermit("req-e3", "permit-1", "2026-06-01T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.validToUtc").value("2026-06-01T00:00:00Z"));

        // 结束之后采集时刻的提交被拒绝；结束前的采集时刻仍命中（左闭右开）
        submit("req-e4", "obs-2", "DEV-1", 5, 5, "2026-07-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));
        submit("req-e5", "obs-3", "DEV-1", 5, 5, "2026-05-01T00:00:00Z")
                .andExpect(status().isCreated());

        // 已接收观测不删除
        getCurrent("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"));
        assertThat(usedCount("permit-1")).isEqualTo(2);
    }

    @Test
    void endPermitWithInvalidEndTimeReturns400() throws Exception {
        createPermit("req-e6", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 不在原有效期内部：400
        endPermit("req-e7", "permit-1", "2027-06-01T00:00:00Z")
                .andExpect(status().isBadRequest());
        endPermit("req-e8", "permit-1", "2025-06-01T00:00:00Z")
                .andExpect(status().isBadRequest());
        getPermit("permit-1").andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- 撤销与复核标记 ----------

    @Test
    void revokePermitMarksUnmergedObservationsAndWritesSnapshot() throws Exception {
        createPermit("req-r1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100, 7)
                .andExpect(status().isCreated());
        submit("req-r2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated());
        submit("req-r3", "obs-2", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated());
        // obs-2 经历合并（版本前进到 2），不属于未合并观测
        merge("req-r4", "obs-2", 1, "站点B").andExpect(status().isOk());

        revokePermit("req-r5", "permit-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 未合并的 obs-1 标记 PERMIT_REVIEW；已合并的 obs-2 不标记
        getCurrent("obs-1")
                .andExpect(jsonPath("$.reviewStatus").value("PERMIT_REVIEW"))
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.permitVersion").value(7));
        getCurrent("obs-2")
                .andExpect(jsonPath("$.reviewStatus").value("NONE"));

        // 复核快照只包含 obs-1，记录撤销时的许可版本与观测版本
        mockMvc.perform(get("/api/permits/{id}/review-snapshots", "permit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$[0].permitId").value("permit-1"))
                .andExpect(jsonPath("$[0].permitVersion").value(7))
                .andExpect(jsonPath("$[0].observationVersion").value(1));

        // 撤销后提交不再命中
        submit("req-r6", "obs-3", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));
    }

    @Test
    void revokePermitDoesNotRecomputeManuallyResolvedObservations() throws Exception {
        createPermit("req-s1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        submit("req-s2", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated());
        // 制造冲突并人工裁决：服务端前进到 v2，离线端基于 v1 冲突后解决
        merge("req-s3", "obs-1", 1, "站点B").andExpect(status().isOk());
        Map<String, Object> resolveBody = new LinkedHashMap<>();
        resolveBody.put("requestId", "req-s4");
        resolveBody.put("resolutionId", "res-1");
        resolveBody.put("baseVersion", 1);
        resolveBody.put("expectedCurrentVersion", 2);
        resolveBody.put("location", "站点C");
        resolveBody.put("reading", "1.0");
        resolveBody.put("note", "备注");
        resolveBody.put("selections", Map.of("location", "CANDIDATE"));
        resolveBody.put("operator", "operator-a");
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveBody)))
                .andExpect(status().isOk());

        revokePermit("req-s5", "permit-1").andExpect(status().isOk());

        // 已人工裁决的观测不重算、不标记复核；内容保持裁决结果
        getCurrent("obs-1")
                .andExpect(jsonPath("$.reviewStatus").value("NONE"))
                .andExpect(jsonPath("$.location").value("站点C"));
        mockMvc.perform(get("/api/permits/{id}/review-snapshots", "permit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void revokePermitTwiceReturns409ButSameRequestReplays() throws Exception {
        createPermit("req-t1", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        revokePermit("req-t2", "permit-1").andExpect(status().isOk());
        // 同键重放：返回原成功结果
        revokePermit("req-t2", "permit-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        // 不同请求重复撤销：409
        revokePermit("req-t3", "permit-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_ALREADY_REVOKED"));
        // 已撤销许可上提前结束：409
        endPermit("req-t4", "permit-1", "2026-06-01T00:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_ALREADY_REVOKED"));
    }

    // ---------- 查询与失败语义 ----------

    @Test
    void getMissingPermitReturns404() throws Exception {
        getPermit("permit-x").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/permits/{id}/review-snapshots", "permit-x"))
                .andExpect(status().isNotFound());
        endPermit("req-u1", "permit-x", "2026-06-01T00:00:00Z")
                .andExpect(status().isNotFound());
        revokePermit("req-u2", "permit-x").andExpect(status().isNotFound());
    }

    @Test
    void failedSubmitDoesNotOccupyKeyAndIsNotRetroactivelySucceeded() throws Exception {
        // 无许可时提交失败：422，且不占键、不留观测
        submit("req-v1", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));
        getCurrent("obs-1").andExpect(status().isNotFound());
        Integer logRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-v1'", Integer.class);
        assertThat(logRows).isZero();

        // 之后创建许可：过去的拒绝不会被追溯改写，但同一 permitKey 可作为新请求成功
        createPermit("req-v2", "permit-1", "DEV-1", 0, 10, 0, 10,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        submit("req-v1", "obs-1", "DEV-1", 5, 5, "2026-03-01T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-1"));
        assertThat(usedCount("permit-1")).isEqualTo(1);
    }
}
