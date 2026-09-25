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
 * 偏移变更触发的顺序重建测试：矫正后时刻重算、全局合并顺序重排、不可变重排记录、
 * 原始本地时刻与既有冲突解决记录不被改写、失败回滚（真实 H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceObservationRebuildTest {

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
        jdbcTemplate.update("DELETE FROM device_offset");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    private ResultActions registerOffset(String deviceId, String requestId,
                                         String effectiveFromUtc, int offsetSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("effectiveFromUtc", effectiveFromUtc);
        body.put("offsetSeconds", offsetSeconds);
        return mockMvc.perform(post("/api/devices/{deviceId}/offsets", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions modifyOffset(String deviceId, String requestId,
                                       String effectiveFromUtc, int offsetSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("effectiveFromUtc", effectiveFromUtc);
        body.put("offsetSeconds", offsetSeconds);
        return mockMvc.perform(put("/api/devices/{deviceId}/offsets", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submit(String requestId, String observationId, String deviceId,
                                 String deviceLocalTime, String location) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("deviceId", deviceId);
        body.put("deviceLocalTime", deviceLocalTime);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "备注" + location);
        return mockMvc.perform(post("/api/device-observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getState(String observationId) throws Exception {
        return mockMvc.perform(get("/api/device-observations/{id}", observationId));
    }

    private ResultActions listVersions(String observationId) throws Exception {
        return mockMvc.perform(get("/api/device-observations/{id}/versions", observationId));
    }

    private ResultActions listReorders(String observationId) throws Exception {
        return mockMvc.perform(get("/api/reorders").param("observationId", observationId));
    }

    private int reorderCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM observation_reorder", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 修改偏移触发重建 ----------

    @Test
    void modifyOffsetRebuildsCorrectedTimesAndFlipsWinner() throws Exception {
        registerOffset("dev-a", "req-r0", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        registerOffset("dev-b", "req-r1", "2026-09-25T00:00:00Z", 0).andExpect(status().isCreated());
        // v1 矫正后 10:00:10，v2 矫正后 10:00:05：v1 胜出
        submit("req-r2", "obs-1", "dev-a", "2026-09-25T10:00:00", "站点A").andExpect(status().isCreated());
        submit("req-r3", "obs-1", "dev-b", "2026-09-25T10:00:05", "站点B").andExpect(status().isCreated());
        getState("obs-1").andExpect(jsonPath("$.currentVersion").value(1));

        // 偏移 +10 → +1：v1 矫正后变为 10:00:01，胜者翻转为 v2
        modifyOffset("dev-a", "req-r4", "2026-09-25T00:00:00Z", 1).andExpect(status().isOk());

        getState("obs-1")
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        // 矫正后时刻被重算，原始本地时刻不被改写，合并顺序被重排
        listVersions("obs-1")
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].deviceLocalTime").value("2026-09-25T10:00:00"))
                .andExpect(jsonPath("$[0].correctedAtUtc").value("2026-09-25T10:00:01Z"))
                .andExpect(jsonPath("$[0].mergeSeq").value(1))
                .andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].deviceLocalTime").value("2026-09-25T10:00:05"))
                .andExpect(jsonPath("$[1].correctedAtUtc").value("2026-09-25T10:00:05Z"))
                .andExpect(jsonPath("$[1].mergeSeq").value(2))
                .andExpect(jsonPath("$[1].current").value(true));

        // 不可变重排记录：固化设备、偏移变更、受影响观测与新旧顺序
        listReorders("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reorderId").value("req-r4#obs-1"))
                .andExpect(jsonPath("$[0].requestId").value("req-r4"))
                .andExpect(jsonPath("$[0].deviceId").value("dev-a"))
                .andExpect(jsonPath("$[0].effectiveFromUtc").value("2026-09-25T00:00:00Z"))
                .andExpect(jsonPath("$[0].oldOffsetSeconds").value(10))
                .andExpect(jsonPath("$[0].newOffsetSeconds").value(1))
                .andExpect(jsonPath("$[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$[0].oldWinnerVersion").value(1))
                .andExpect(jsonPath("$[0].newWinnerVersion").value(2))
                .andExpect(jsonPath("$[0].oldOrder").value(org.hamcrest.Matchers.contains(2, 1)))
                .andExpect(jsonPath("$[0].newOrder").value(org.hamcrest.Matchers.contains(1, 2)));
    }

    @Test
    void registerOffsetSplittingIntervalRebuildsAndWritesReorder() throws Exception {
        registerOffset("dev-c", "req-n0", "2026-09-25T00:00:00Z", 100).andExpect(status().isCreated());
        registerOffset("dev-d", "req-n1", "2026-09-25T00:00:00Z", 0).andExpect(status().isCreated());
        // v1 矫正后 10:01:40，v2 矫正后 10:01:00：v1 胜出
        submit("req-n2", "obs-2", "dev-c", "2026-09-25T10:00:00", "站点A").andExpect(status().isCreated());
        submit("req-n3", "obs-2", "dev-d", "2026-09-25T10:01:00", "站点B").andExpect(status().isCreated());
        getState("obs-2").andExpect(jsonPath("$.currentVersion").value(1));

        // 新增偏移记录切分区间：10:00:00 落入新记录（+5），v1 矫正后变为 10:00:05，胜者翻转为 v2
        registerOffset("dev-c", "req-n4", "2026-09-25T09:00:00Z", 5).andExpect(status().isCreated());

        getState("obs-2")
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        listVersions("obs-2")
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].correctedAtUtc").value("2026-09-25T10:00:05Z"));
        // 新增偏移记录的重排记录：变更前偏移秒数为 null
        listReorders("obs-2")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reorderId").value("req-n4#obs-2"))
                .andExpect(jsonPath("$[0].oldOffsetSeconds").doesNotExist())
                .andExpect(jsonPath("$[0].newOffsetSeconds").value(5))
                .andExpect(jsonPath("$[0].oldWinnerVersion").value(1))
                .andExpect(jsonPath("$[0].newWinnerVersion").value(2));
    }

    @Test
    void rebuildWithoutWinnerChangeWritesNoReorder() throws Exception {
        registerOffset("dev-e", "req-x0", "2026-09-25T00:00:00Z", 100).andExpect(status().isCreated());
        submit("req-x1", "obs-3", "dev-e", "2026-09-25T10:00:00", "站点A").andExpect(status().isCreated());
        submit("req-x2", "obs-3", "dev-e", "2026-09-25T10:01:00", "站点B").andExpect(status().isCreated());
        getState("obs-3").andExpect(jsonPath("$.currentVersion").value(2));

        // 偏移等值平移：矫正后时刻重算但相对顺序与胜者不变
        modifyOffset("dev-e", "req-x3", "2026-09-25T00:00:00Z", 50).andExpect(status().isOk());

        getState("obs-3").andExpect(jsonPath("$.currentVersion").value(2));
        listVersions("obs-3")
                .andExpect(jsonPath("$[0].correctedAtUtc").value("2026-09-25T10:00:50Z"))
                .andExpect(jsonPath("$[1].correctedAtUtc").value("2026-09-25T10:01:50Z"));
        assertThat(reorderCount()).isZero();
    }

    @Test
    void rebuildLeavesConflictResolutionRecordsUntouched() throws Exception {
        // 既有三方合并域：制造一次人工冲突解决
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("requestId", "req-c0");
        create.put("observationId", "obs-r1");
        create.put("location", "站点A");
        create.put("reading", "1.0");
        create.put("note", "备注");
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated());
        Map<String, Object> merge = new LinkedHashMap<>();
        merge.put("requestId", "req-c1");
        merge.put("baseVersion", 1);
        merge.put("location", "站点B");
        merge.put("reading", "1.0");
        merge.put("note", "备注");
        mockMvc.perform(post("/api/observations/obs-r1/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(merge)))
                .andExpect(status().isOk());
        Map<String, Object> resolve = new LinkedHashMap<>();
        resolve.put("requestId", "req-c2");
        resolve.put("resolutionId", "res-r1");
        resolve.put("baseVersion", 1);
        resolve.put("expectedCurrentVersion", 2);
        resolve.put("location", "站点C");
        resolve.put("reading", "1.0");
        resolve.put("note", "备注");
        resolve.put("selections", Map.of("location", "CANDIDATE"));
        resolve.put("operator", "tester");
        mockMvc.perform(post("/api/observations/obs-r1/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolve)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        // 偏移变更触发重建（含胜者翻转）
        registerOffset("dev-a", "req-c3", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        registerOffset("dev-b", "req-c4", "2026-09-25T00:00:00Z", 0).andExpect(status().isCreated());
        submit("req-c5", "obs-1", "dev-a", "2026-09-25T10:00:00", "站点甲").andExpect(status().isCreated());
        submit("req-c6", "obs-1", "dev-b", "2026-09-25T10:00:05", "站点乙").andExpect(status().isCreated());
        modifyOffset("dev-a", "req-c7", "2026-09-25T00:00:00Z", 1).andExpect(status().isOk());
        assertThat(reorderCount()).isEqualTo(1);

        // 人工冲突解决结论不被重建覆盖：解决记录与观测当前内容保持不变
        mockMvc.perform(get("/api/observations/resolutions/res-r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionId").value("res-r1"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.selections.location").value("CANDIDATE"));
        mockMvc.perform(get("/api/observations/obs-r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"));
    }

    // ---------- 失败与回滚 ----------

    @Test
    void failedOffsetChangeRollsBackAndLeavesNoTrace() throws Exception {
        registerOffset("dev-f", "req-z0", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        submit("req-z1", "obs-4", "dev-f", "2026-09-25T10:00:00", "站点A").andExpect(status().isCreated());

        // 越界偏移秒数：参数校验 400，不产生任何变更
        modifyOffset("dev-f", "req-z2", "2026-09-25T00:00:00Z", 86401).andExpect(status().isBadRequest());
        // 记录不存在：404，不产生任何变更
        modifyOffset("dev-f", "req-z3", "2026-09-25T09:00:00Z", 20).andExpect(status().isNotFound());
        // 重叠登记：409，不产生任何变更
        registerOffset("dev-f", "req-z4", "2026-09-25T00:00:00Z", 99).andExpect(status().isConflict());

        // 状态保持：矫正后时刻、偏移记录不变，无重排记录，失败请求不占键
        getState("obs-4").andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T10:00:10Z"));
        assertThat(reorderCount()).isZero();
        for (String requestId : new String[]{"req-z2", "req-z3", "req-z4"}) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
            assertThat(rows).isZero();
        }
        // 失败键可复用：同一 requestId 换合法参数后正常执行并触发重建
        modifyOffset("dev-f", "req-z3", "2026-09-25T00:00:00Z", 20).andExpect(status().isOk());
        getState("obs-4").andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T10:00:20Z"));
    }
}
