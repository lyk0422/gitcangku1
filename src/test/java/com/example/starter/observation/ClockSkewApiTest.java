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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测设备时钟偏移矫正与顺序重建 API 测试：偏移区间校验、矫正时刻换算、
 * 合并顺序与胜出判定、同事务重建、人工解决保留、幂等与查询（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClockSkewApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_reorder");
        jdbcTemplate.update("DELETE FROM device_observation");
        jdbcTemplate.update("DELETE FROM device_clock_offset");
        jdbcTemplate.update("DELETE FROM device_registry");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    // ---------- 请求辅助 ----------

    private ResultActions registerOffset(String requestId, String deviceId,
                                         String effectiveFromUtc, Integer offsetSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("effectiveFromUtc", effectiveFromUtc);
        body.put("offsetSeconds", offsetSeconds);
        return mockMvc.perform(post("/api/devices/{deviceId}/offsets", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions modifyOffset(String requestId, String deviceId,
                                       String effectiveFromUtc, Integer offsetSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("effectiveFromUtc", effectiveFromUtc);
        body.put("offsetSeconds", offsetSeconds);
        return mockMvc.perform(put("/api/devices/{deviceId}/offsets", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions listOffsets(String deviceId) throws Exception {
        return mockMvc.perform(get("/api/devices/{deviceId}/offsets", deviceId));
    }

    private ResultActions submit(String requestId, String observationId, String submissionId, String deviceId,
                                 String deviceLocalAt, String location, String reading, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("submissionId", submissionId);
        body.put("deviceId", deviceId);
        body.put("deviceLocalAt", deviceLocalAt);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/device-submissions", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions listSubmissions(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/device-submissions", observationId));
    }

    private ResultActions listReorders(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/reorders", observationId));
    }

    private ResultActions create(String requestId, String observationId,
                                 String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String requestId, String resolutionId, String observationId,
                                  int baseVersion, int expectedCurrentVersion,
                                  String location, String reading, String note,
                                  Map<String, String> selections, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("selections", selections);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private int submissionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_observation WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 偏移登记与区间校验 ----------

    @Test
    void registerOffsetAndListDetail() throws Exception {
        registerOffset("req-o1", "dev-1", "2026-09-25T00:00:00Z", 3600)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.effectiveFromUtc").value("2026-09-25T00:00:00Z"))
                .andExpect(jsonPath("$.offsetSeconds").value(3600))
                .andExpect(jsonPath("$.rebuiltSubmissions").value(0))
                .andExpect(jsonPath("$.reorders.length()").value(0));

        registerOffset("req-o2", "dev-1", "2026-09-26T00:00:00Z", -1800)
                .andExpect(status().isCreated());

        listOffsets("dev-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].effectiveFromUtc").value("2026-09-25T00:00:00Z"))
                .andExpect(jsonPath("$[0].offsetSeconds").value(3600))
                .andExpect(jsonPath("$[1].effectiveFromUtc").value("2026-09-26T00:00:00Z"))
                .andExpect(jsonPath("$[1].offsetSeconds").value(-1800));
    }

    @Test
    void registerOffsetOutOfRangeReturns400() throws Exception {
        registerOffset("req-b1", "dev-1", "2026-09-25T00:00:00Z", 86401)
                .andExpect(status().isBadRequest());
        registerOffset("req-b2", "dev-1", "2026-09-25T00:00:00Z", -86401)
                .andExpect(status().isBadRequest());
        registerOffset("req-b3", "dev-1", "2026-09-25T00:00:00Z", null)
                .andExpect(status().isBadRequest());
        // 边界值允许
        registerOffset("req-b4", "dev-1", "2026-09-25T00:00:00Z", 86400)
                .andExpect(status().isCreated());
        registerOffset("req-b5", "dev-1", "2026-09-26T00:00:00Z", -86400)
                .andExpect(status().isCreated());
        listOffsets("dev-1").andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void overlappingOffsetIntervalReturns409() throws Exception {
        registerOffset("req-v1", "dev-1", "2026-09-25T00:00:00Z", 100)
                .andExpect(status().isCreated());
        // 同一设备相同生效起始时刻：区间重叠，409，原记录不被覆盖
        registerOffset("req-v2", "dev-1", "2026-09-25T00:00:00Z", 200)
                .andExpect(status().isConflict());
        listOffsets("dev-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offsetSeconds").value(100));
        // 不同设备相同生效起始时刻互不干扰
        registerOffset("req-v3", "dev-2", "2026-09-25T00:00:00Z", 200)
                .andExpect(status().isCreated());
    }

    @Test
    void modifyOffsetSecondsAndMissingRecordReturns404() throws Exception {
        registerOffset("req-m1", "dev-1", "2026-09-25T00:00:00Z", 3600)
                .andExpect(status().isCreated());
        modifyOffset("req-m2", "dev-1", "2026-09-25T00:00:00Z", 7200)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsetSeconds").value(7200));
        listOffsets("dev-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offsetSeconds").value(7200));
        modifyOffset("req-m3", "dev-1", "2026-09-27T00:00:00Z", 0)
                .andExpect(status().isNotFound());
    }

    // ---------- 矫正时刻换算与保存 ----------

    @Test
    void submissionComputesCorrectedTimeAndPreservesLocalTime() throws Exception {
        registerOffset("req-s0", "dev-1", "2026-09-25T00:00:00Z", 3600)
                .andExpect(status().isCreated());

        // 命中偏移记录：矫正后时刻 = 本地时刻 + 3600 秒；首个提交创建观测记录
        submit("req-s1", "obs-1", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点一", "1.5", "首条")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T11:00:00Z"))
                .andExpect(jsonPath("$.offsetSeconds").value(3600))
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.version").value(1));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.location").value("站点一"))
                .andExpect(jsonPath("$.version").value(1));

        // 原始本地时刻与矫正后时刻同时保存，均可查询
        listSubmissions("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceLocalAt").value("2026-09-25T10:00:00"))
                .andExpect(jsonPath("$[0].correctedAtUtc").value("2026-09-25T11:00:00Z"));

        // 本地时刻早于偏移生效起始时刻：无命中，偏移为 0；矫正后时刻早于当前胜出者，不应用
        submit("req-s2", "obs-1", "sub-2", "dev-1", "2026-09-24T10:00:00", "站点零", "0.5", "偏移前")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-24T10:00:00Z"))
                .andExpect(jsonPath("$.offsetSeconds").value(0))
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.version").value(1));

        // 无偏移记录的设备：偏移为 0，矫正后时刻等于本地时刻
        submit("req-s3", "obs-1", "sub-3", "dev-9", "2026-09-25T12:00:00", "站点九", "2.5", "无偏移")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T12:00:00Z"))
                .andExpect(jsonPath("$.offsetSeconds").value(0))
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.version").value(2));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.location").value("站点九"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void winnerTieBreakByDeviceIdThenSubmissionId() throws Exception {
        // 矫正后时刻相同：设备标识字典序大者胜出
        submit("req-t1", "obs-2", "sub-a", "dev-b", "2026-09-25T10:00:00", "站点B", "1.0", "设备b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(true));
        submit("req-t2", "obs-2", "sub-b", "dev-a", "2026-09-25T10:00:00", "站点A", "2.0", "设备a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.version").value(1));
        getCurrent("obs-2").andExpect(jsonPath("$.location").value("站点B"));

        // 更晚矫正时刻胜出
        submit("req-t3", "obs-2", "sub-c", "dev-a", "2026-09-25T10:00:01", "站点C", "3.0", "更晚")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.version").value(2));
        // 矫正时刻与设备均相同：提交标识字典序大者胜出
        submit("req-t4", "obs-2", "sub-d", "dev-a", "2026-09-25T10:00:01", "站点D", "4.0", "同刻同机")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.version").value(3));
        getCurrent("obs-2").andExpect(jsonPath("$.location").value("站点D"));

        // 合并顺序稳定可复现：矫正时刻升序，设备标识升序，提交标识升序
        listSubmissions("obs-2")
                .andExpect(jsonPath("$[0].submissionId").value("sub-b"))
                .andExpect(jsonPath("$[1].submissionId").value("sub-a"))
                .andExpect(jsonPath("$[2].submissionId").value("sub-c"))
                .andExpect(jsonPath("$[3].submissionId").value("sub-d"));
    }

    // ---------- 顺序重建与重排记录 ----------

    @Test
    void rebuildAfterOffsetRegisterFlipsWinnerAndWritesReorder() throws Exception {
        submit("req-r1", "obs-3", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点一", "1.0", "先")
                .andExpect(status().isCreated());
        submit("req-r2", "obs-3", "sub-2", "dev-2", "2026-09-25T10:00:30", "站点二", "2.0", "后")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.version").value(2));
        getCurrent("obs-3").andExpect(jsonPath("$.location").value("站点二"));

        // 登记 dev-2 偏移 -3600：sub-2 矫正时刻变为 09:00:30，胜出翻转为 sub-1
        registerOffset("req-r3", "dev-2", "2026-09-25T00:00:00Z", -3600)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rebuiltSubmissions").value(1))
                .andExpect(jsonPath("$.reorders.length()").value(1))
                .andExpect(jsonPath("$.reorders[0].observationId").value("obs-3"))
                .andExpect(jsonPath("$.reorders[0].deviceId").value("dev-2"))
                .andExpect(jsonPath("$.reorders[0].oldOffsetSeconds").doesNotExist())
                .andExpect(jsonPath("$.reorders[0].newOffsetSeconds").value(-3600))
                .andExpect(jsonPath("$.reorders[0].previousSubmissionId").value("sub-2"))
                .andExpect(jsonPath("$.reorders[0].newSubmissionId").value("sub-1"))
                .andExpect(jsonPath("$.reorders[0].previousOrderKey").value("2026-09-25T10:00:30Z|dev-2|sub-2"))
                .andExpect(jsonPath("$.reorders[0].newOrderKey").value("2026-09-25T10:00:00Z|dev-1|sub-1"))
                .andExpect(jsonPath("$.reorders[0].previousVersion").value(2))
                .andExpect(jsonPath("$.reorders[0].newVersion").value(3));

        // 当前内容翻转为新胜出提交，版本前进
        getCurrent("obs-3")
                .andExpect(jsonPath("$.location").value("站点一"))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(get("/api/observations/{id}/versions/{v}", "obs-3", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点一"));

        // 重建改写矫正后时刻，但原始本地时刻不被改写
        listSubmissions("obs-3")
                .andExpect(jsonPath("$[0].submissionId").value("sub-2"))
                .andExpect(jsonPath("$[0].correctedAtUtc").value("2026-09-25T09:00:30Z"))
                .andExpect(jsonPath("$[0].deviceLocalAt").value("2026-09-25T10:00:30"))
                .andExpect(jsonPath("$[0].offsetSeconds").value(-3600))
                .andExpect(jsonPath("$[1].submissionId").value("sub-1"));

        // 重排记录可查询且不可变语义字段完整
        listReorders("obs-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].previousSubmissionId").value("sub-2"))
                .andExpect(jsonPath("$[0].newSubmissionId").value("sub-1"))
                .andExpect(jsonPath("$[0].requestId").value("req-r3"));
    }

    @Test
    void rebuildAfterOffsetModifyUpdatesCorrectionAndOrder() throws Exception {
        registerOffset("req-u1", "dev-1", "2026-09-25T00:00:00Z", 3600)
                .andExpect(status().isCreated());
        submit("req-u2", "obs-4", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点一", "1.0", "先")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T11:00:00Z"));
        submit("req-u3", "obs-4", "sub-2", "dev-2", "2026-09-25T10:30:00", "站点二", "2.0", "后")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.version").value(1));

        // 修改偏移秒数 3600 -> 0：sub-1 矫正时刻回退到 10:00，胜出翻转为 sub-2
        modifyOffset("req-u4", "dev-1", "2026-09-25T00:00:00Z", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rebuiltSubmissions").value(1))
                .andExpect(jsonPath("$.reorders.length()").value(1))
                .andExpect(jsonPath("$.reorders[0].oldOffsetSeconds").value(3600))
                .andExpect(jsonPath("$.reorders[0].newOffsetSeconds").value(0))
                .andExpect(jsonPath("$.reorders[0].previousSubmissionId").value("sub-1"))
                .andExpect(jsonPath("$.reorders[0].newSubmissionId").value("sub-2"))
                .andExpect(jsonPath("$.reorders[0].previousVersion").value(1))
                .andExpect(jsonPath("$.reorders[0].newVersion").value(2));
        getCurrent("obs-4")
                .andExpect(jsonPath("$.location").value("站点二"))
                .andExpect(jsonPath("$.version").value(2));
        listSubmissions("obs-4")
                .andExpect(jsonPath("$[0].submissionId").value("sub-1"))
                .andExpect(jsonPath("$[0].correctedAtUtc").value("2026-09-25T10:00:00Z"))
                .andExpect(jsonPath("$[1].submissionId").value("sub-2"));
    }

    @Test
    void manualResolutionIsPreservedDuringRebuild() throws Exception {
        // 构造人工冲突解决结论：v1 站点A -> v2 站点B，离线端基于 v1 提交站点C 冲突后人工选择候选
        create("req-p1", "obs-5", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-p2", "obs-5", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-p3", "obs-5", 1, "站点C", "1.0", "备注").andExpect(status().isConflict());
        resolve("req-p4", "res-1", "obs-5", 1, 2, "站点C", "1.0", "备注",
                Map.of("location", "CANDIDATE"), "tester")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 有人工解决结论的观测：设备提交仅记录，不覆盖当前内容
        submit("req-p5", "obs-5", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点X", "9.0", "提交一")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.version").value(3));
        submit("req-p6", "obs-5", "sub-2", "dev-2", "2026-09-25T11:00:00", "站点Y", "8.0", "提交二")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.version").value(3));

        // 重建使胜出提交翻转（sub-2 矫正时刻变为 09:00，sub-1 成为新胜出者），
        // 但人工解决结论优先保留：不改当前内容、不前进版本、不写重排记录
        registerOffset("req-p7", "dev-2", "2026-09-25T00:00:00Z", -7200)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rebuiltSubmissions").value(1))
                .andExpect(jsonPath("$.reorders.length()").value(0));
        getCurrent("obs-5")
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.version").value(3));
        listReorders("obs-5").andExpect(jsonPath("$.length()").value(0));
        // 矫正后时刻仍按新偏移重算
        listSubmissions("obs-5")
                .andExpect(jsonPath("$[0].submissionId").value("sub-2"))
                .andExpect(jsonPath("$[0].correctedAtUtc").value("2026-09-25T09:00:00Z"));
    }

    // ---------- 失败分支 ----------

    @Test
    void duplicateSubmissionIdReturns409() throws Exception {
        submit("req-x1", "obs-9", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点一", "1.0", "先")
                .andExpect(status().isCreated());
        submit("req-x2", "obs-9", "sub-1", "dev-2", "2026-09-25T11:00:00", "站点二", "2.0", "重")
                .andExpect(status().isConflict());
        assertThat(submissionCount("obs-9")).isEqualTo(1);
    }

    @Test
    void deletedObservationRejectsSubmissionWith410() throws Exception {
        create("req-x3", "obs-10", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-x4", "obs-10", 1).andExpect(status().isOk());
        submit("req-x5", "obs-10", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点X", "1.0", "删后")
                .andExpect(status().isGone());
        // 提交随事务回滚，不留记录
        listSubmissions("obs-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listEndpointsOnMissingObservationReturn404() throws Exception {
        listSubmissions("obs-x").andExpect(status().isNotFound());
        listReorders("obs-x").andExpect(status().isNotFound());
        // 无偏移记录的设备：返回空列表而非 404
        listOffsets("dev-x")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void submissionValidationReturns400() throws Exception {
        submit("req-y1", "obs-11", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点一", "1.2345", "四位小数")
                .andExpect(status().isBadRequest());
        submit("req-y2", "obs-11", "sub-1", "dev-1", null, "站点一", "1.0", "缺时刻")
                .andExpect(status().isBadRequest());
        submit("req-y3", "obs-11", "", "dev-1", "2026-09-25T10:00:00", "站点一", "1.0", "空标识")
                .andExpect(status().isBadRequest());
        assertThat(submissionCount("obs-11")).isZero();
    }

    // ---------- 幂等 ----------

    @Test
    void idempotentReplayAndFailedRequestDoesNotOccupyKey() throws Exception {
        registerOffset("req-i1", "dev-1", "2026-09-25T00:00:00Z", 100)
                .andExpect(status().isCreated());
        // 同键同参重放：返回首次结果，不重复登记
        registerOffset("req-i1", "dev-1", "2026-09-25T00:00:00Z", 100)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offsetSeconds").value(100));
        // 同键异参：409
        registerOffset("req-i1", "dev-1", "2026-09-25T00:00:00Z", 200)
                .andExpect(status().isConflict());
        listOffsets("dev-1").andExpect(jsonPath("$.length()").value(1));

        // 失败不占键：重叠 409 后同一 requestId 换合法参数可成功
        registerOffset("req-i2", "dev-1", "2026-09-25T00:00:00Z", 300)
                .andExpect(status().isConflict());
        registerOffset("req-i2", "dev-1", "2026-09-25T01:00:00Z", 300)
                .andExpect(status().isCreated());
        listOffsets("dev-1").andExpect(jsonPath("$.length()").value(2));

        // 提交幂等：同键同参重放首次结果，不重复落库
        submit("req-i3", "obs-6", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点一", "1.0", "提交")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T10:05:00Z"));
        submit("req-i3", "obs-6", "sub-1", "dev-1", "2026-09-25T10:00:00", "站点一", "1.0", "提交")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T10:05:00Z"));
        assertThat(submissionCount("obs-6")).isEqualTo(1);
        // 同键异参：409
        submit("req-i3", "obs-6", "sub-1", "dev-1", "2026-09-25T11:00:00", "站点一", "1.0", "提交")
                .andExpect(status().isConflict());

        // 失败的提交不占键：410 后同一 requestId 换合法目标可成功
        create("req-i4", "obs-7", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-i5", "obs-7", 1).andExpect(status().isOk());
        submit("req-i6", "obs-7", "sub-2", "dev-1", "2026-09-25T10:00:00", "站点X", "1.0", "删后")
                .andExpect(status().isGone());
        submit("req-i6", "obs-8", "sub-3", "dev-1", "2026-09-25T10:00:00", "站点八", "1.0", "换目标")
                .andExpect(status().isCreated());
    }
}
