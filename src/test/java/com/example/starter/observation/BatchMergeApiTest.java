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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量离线合并 API 测试：完整最终计数、坐标边界与冲突簇预校验，任一失败整批回滚；
 * 幂等重放不重复扣减（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchMergeApiTest {

    private static final String VALID_FROM = "2026-01-01T00:00:00Z";
    private static final String VALID_TO = "2026-12-31T00:00:00Z";
    private static final String COLLECTED_AT = "2026-03-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PermitService permitService;

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

    private void createPermit(String requestId, String permitId, String deviceId,
                              int xMin, int xMax, int maxObservations) {
        permitService.create(new CreatePermitRequest(requestId, permitId, deviceId,
                xMin, xMax, 0, 100, java.time.Instant.parse(VALID_FROM), java.time.Instant.parse(VALID_TO),
                maxObservations, 1));
    }

    private void submit(String requestId, String observationId, String deviceId, int gridX) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("deviceId", deviceId);
        body.put("gridX", gridX);
        body.put("gridY", 5);
        body.put("collectedAtUtc", COLLECTED_AT);
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> item(String observationId, int baseVersion, String deviceId,
                                     int gridX, String location) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("baseVersion", baseVersion);
        item.put("deviceId", deviceId);
        item.put("gridX", gridX);
        item.put("gridY", 5);
        item.put("collectedAtUtc", COLLECTED_AT);
        item.put("location", location);
        item.put("reading", "2.0");
        item.put("note", "批量备注");
        return item;
    }

    private ResultActions batchMerge(String requestId, List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/batch-merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int usedCount(String permitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? -1 : count;
    }

    private int currentVersion(String observationId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM observation_current WHERE observation_id = ?", Integer.class, observationId);
        return version == null ? -1 : version;
    }

    @Test
    void batchMergeAppliesAllItemsAndDecrementsPermits() throws Exception {
        createPermit("req-b1", "permit-1", "DEV-1", 0, 100, 100);
        submit("req-b2", "obs-1", "DEV-1", 5);
        submit("req-b3", "obs-2", "DEV-1", 6);
        int usedBefore = usedCount("permit-1");

        batchMerge("req-b4", List.of(
                        item("obs-1", 1, "DEV-1", 5, "站点B"),
                        item("obs-2", 1, "DEV-1", 6, "站点C")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.results[0].version").value(2))
                .andExpect(jsonPath("$.results[0].location").value("站点B"))
                .andExpect(jsonPath("$.results[1].observationId").value("obs-2"))
                .andExpect(jsonPath("$.results[1].version").value(2));

        assertThat(usedCount("permit-1")).isEqualTo(usedBefore + 2);
        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
    }

    @Test
    void batchMergeItemWithoutPermitRollsBackEverything() throws Exception {
        createPermit("req-c1", "permit-1", "DEV-1", 0, 100, 100);
        submit("req-c2", "obs-1", "DEV-1", 5);
        submit("req-c3", "obs-2", "DEV-1", 6);
        int usedBefore = usedCount("permit-1");

        // 第二项设备无许可：422，整批回滚
        batchMerge("req-c4", List.of(
                        item("obs-1", 1, "DEV-1", 5, "站点B"),
                        item("obs-2", 1, "DEV-9", 6, "站点C")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));

        assertThat(currentVersion("obs-1")).isEqualTo(1);
        assertThat(currentVersion("obs-2")).isEqualTo(1);
        assertThat(usedCount("permit-1")).isEqualTo(usedBefore);
        Integer logRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-c4'", Integer.class);
        assertThat(logRows).isZero();
    }

    @Test
    void batchMergeExceedingCumulativeLimitRollsBackEverything() throws Exception {
        // 许可上限只比已用多 1，批量两项命中同一许可：完整最终计数超限
        createPermit("req-d1", "permit-1", "DEV-1", 0, 100, 3);
        submit("req-d2", "obs-1", "DEV-1", 5);
        submit("req-d3", "obs-2", "DEV-1", 6);
        assertThat(usedCount("permit-1")).isEqualTo(2);

        batchMerge("req-d4", List.of(
                        item("obs-1", 1, "DEV-1", 5, "站点B"),
                        item("obs-2", 1, "DEV-1", 6, "站点C")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_LIMIT_EXCEEDED"));

        assertThat(currentVersion("obs-1")).isEqualTo(1);
        assertThat(currentVersion("obs-2")).isEqualTo(1);
        assertThat(usedCount("permit-1")).isEqualTo(2);
    }

    @Test
    void batchMergeItemOutsideGridBoundaryRollsBackEverything() throws Exception {
        createPermit("req-e1", "permit-1", "DEV-1", 0, 10, 100);
        submit("req-e2", "obs-1", "DEV-1", 5);
        submit("req-e3", "obs-2", "DEV-1", 6);
        int usedBefore = usedCount("permit-1");

        // 第二项网格坐标越出许可边界：422，整批回滚
        batchMerge("req-e4", List.of(
                        item("obs-1", 1, "DEV-1", 5, "站点B"),
                        item("obs-2", 1, "DEV-1", 50, "站点C")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_FOUND"));

        assertThat(currentVersion("obs-1")).isEqualTo(1);
        assertThat(usedCount("permit-1")).isEqualTo(usedBefore);
    }

    @Test
    void batchMergeConflictClusterRollsBackEverything() throws Exception {
        createPermit("req-f1", "permit-1", "DEV-1", 0, 100, 100);
        submit("req-f2", "obs-1", "DEV-1", 5);
        submit("req-f3", "obs-2", "DEV-1", 6);
        // 服务端先把 obs-2 的地点合并为站点X（版本 2）
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-f4");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点X");
        mergeBody.put("reading", "1.0");
        mergeBody.put("note", "备注");
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());
        int usedBefore = usedCount("permit-1");

        // 批量第二项基于 v1 改站点Y：与当前站点X 冲突，整批 409 回滚
        batchMerge("req-f5", List.of(
                        item("obs-1", 1, "DEV-1", 5, "站点B"),
                        item("obs-2", 1, "DEV-1", 6, "站点Y")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        assertThat(currentVersion("obs-1")).isEqualTo(1);
        assertThat(currentVersion("obs-2")).isEqualTo(2);
        assertThat(usedCount("permit-1")).isEqualTo(usedBefore);
    }

    @Test
    void batchMergeMissingObservationReturns404AndRollsBack() throws Exception {
        createPermit("req-g1", "permit-1", "DEV-1", 0, 100, 100);
        submit("req-g2", "obs-1", "DEV-1", 5);
        int usedBefore = usedCount("permit-1");

        batchMerge("req-g3", List.of(
                        item("obs-1", 1, "DEV-1", 5, "站点B"),
                        item("obs-x", 1, "DEV-1", 6, "站点C")))
                .andExpect(status().isNotFound());

        assertThat(currentVersion("obs-1")).isEqualTo(1);
        assertThat(usedCount("permit-1")).isEqualTo(usedBefore);
    }

    @Test
    void batchMergeSameRequestIdReplaysWithoutDoubleCount() throws Exception {
        createPermit("req-h1", "permit-1", "DEV-1", 0, 100, 100);
        submit("req-h2", "obs-1", "DEV-1", 5);
        int usedBefore = usedCount("permit-1");
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("obs-1", 1, "DEV-1", 5, "站点B"));

        batchMerge("req-h3", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].version").value(2));
        // 同键同参重放：返回原结果，不重复扣减、不产生新版本
        batchMerge("req-h3", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].version").value(2));
        assertThat(usedCount("permit-1")).isEqualTo(usedBefore + 1);
        assertThat(currentVersion("obs-1")).isEqualTo(2);
        // 同键异参：409
        List<Map<String, Object>> different = new ArrayList<>();
        different.add(item("obs-1", 1, "DEV-1", 5, "站点Z"));
        batchMerge("req-h3", different).andExpect(status().isConflict());
    }

    @Test
    void batchMergeDuplicateObservationIdReturns400() throws Exception {
        createPermit("req-i1", "permit-1", "DEV-1", 0, 100, 100);
        submit("req-i2", "obs-1", "DEV-1", 5);
        batchMerge("req-i3", List.of(
                        item("obs-1", 1, "DEV-1", 5, "站点B"),
                        item("obs-1", 1, "DEV-1", 5, "站点C")))
                .andExpect(status().isBadRequest());
        assertThat(currentVersion("obs-1")).isEqualTo(1);
    }
}
