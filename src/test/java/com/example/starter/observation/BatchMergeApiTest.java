package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量离线合并与冲突簇裁决 API 测试：完整最终计数、坐标边界、冲突簇登记/整批回滚、裁决推进与幂等。
 * 真实 H2 内存库（MODE=MySQL）与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchMergeApiTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");
    private static final String FROM = "2026-10-01T00:00:00Z";
    private static final String TO = "2026-11-01T00:00:00Z";
    private static final String AT = "2026-10-10T08:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM merge_cluster");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("DELETE FROM permit_sample_key");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        jdbcTemplate.update("DELETE FROM permit_device_lock");
        Mockito.when(clock.instant()).thenReturn(NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void createPermit(String requestId, String permitId, String deviceId,
                              int gridMin, int gridMax, int max) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("permitId", permitId);
        body.put("deviceId", deviceId);
        body.put("permitVersion", "v1");
        body.put("gridMin", gridMin);
        body.put("gridMax", gridMax);
        body.put("validFromUtc", FROM);
        body.put("validToUtc", TO);
        body.put("maxObservations", max);
        mockMvc.perform(post("/api/sampling/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> item(String observationId, Integer baseVersion, String deviceId,
                                     int gridId, String observedAt, String location,
                                     String reading, String note) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("observationId", observationId);
        if (baseVersion != null) {
            map.put("baseVersion", baseVersion);
        }
        map.put("deviceId", deviceId);
        map.put("gridId", gridId);
        map.put("observedAtUtc", observedAt);
        map.put("location", location);
        map.put("reading", reading);
        map.put("note", note);
        return map;
    }

    private ResultActions batch(String requestId, List<Map<String, Object>> items) throws Exception {
        return mockMvc.perform(post("/api/sampling/batch-merges")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("requestId", requestId, "items", items))));
    }

    private int permitCount(String permitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT observed_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? 0 : count;
    }

    private int currentVersion(String observationId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM observation_current WHERE observation_id = ?",
                Integer.class, observationId);
        return version == null ? -1 : version;
    }

    private String statusOf(String observationId) {
        return jdbcTemplate.queryForObject(
                "SELECT observation_status FROM observation_current WHERE observation_id = ?",
                String.class, observationId);
    }

    // ---------- 主流程 ----------

    @Test
    void batchCreatesNewSamplesAndCountsOnce() throws Exception {
        createPermit("req-p1", "permit-1", "dev-1", 10, 20, 5);
        batch("req-b1", List.of(
                item("obs-1", null, "dev-1", 10, AT, "站点A", "1.0", "备注"),
                item("obs-2", null, "dev-1", 11, AT, "站点B", "2.0", "备注"),
                item("obs-3", null, "dev-1", 12, AT, "站点C", "3.0", "备注")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].created").value(true))
                .andExpect(jsonPath("$.items[0].observationStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.items[0].permitId").value("permit-1"))
                .andExpect(jsonPath("$.items[0].conflict").value(false))
                .andExpect(jsonPath("$.clusters").isEmpty());
        assertThat(permitCount("permit-1")).isEqualTo(3);
        assertThat(statusOf("obs-1")).isEqualTo("RECEIVED");
    }

    @Test
    void batchMergesExistingObservationAndMarksMerged() throws Exception {
        createPermit("req-p2", "permit-1", "dev-1", 10, 20, 5);
        batch("req-b2", List.of(item("obs-1", null, "dev-1", 10, AT, "站点A", "1.0", "旧备注")))
                .andExpect(status().isOk());
        // 离线基于 v1 改备注
        batch("req-b3", List.of(item("obs-1", 1, "dev-1", 10, AT, "站点A", "1.0", "新备注")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].created").value(false))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].observationStatus").value("MERGED"))
                .andExpect(jsonPath("$.items[0].conflict").value(false));
        assertThat(currentVersion("obs-1")).isEqualTo(2);
        assertThat(statusOf("obs-1")).isEqualTo("MERGED");
        // 合并不重复计数
        assertThat(permitCount("permit-1")).isEqualTo(1);
    }

    // ---------- 预校验失败整批回滚 ----------

    @Test
    void batchRollsBackEverythingWhenOneItemHasNoPermit() throws Exception {
        createPermit("req-p3", "permit-1", "dev-1", 10, 20, 5);
        batch("req-b4", List.of(
                        item("obs-1", null, "dev-1", 10, AT, "站点A", "1.0", "备注"),
                        item("obs-2", null, "dev-1", 99, AT, "站点B", "2.0", "备注")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 整批回滚：无观测、无计数
        Integer obsRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM observation_current", Integer.class);
        assertThat(obsRows).isZero();
        assertThat(permitCount("permit-1")).isZero();
    }

    @Test
    void batchFinalCountAcrossWholeBatchIsCheckedBeforeApply() throws Exception {
        // 许可容量 2：本批 3 条新观测，按完整最终计数 3 > 2 在应用前拒绝
        createPermit("req-p4", "permit-1", "dev-1", 10, 20, 2);
        batch("req-b5", List.of(
                        item("obs-1", null, "dev-1", 10, AT, "A", "1.0", "n"),
                        item("obs-2", null, "dev-1", 11, AT, "B", "2.0", "n"),
                        item("obs-3", null, "dev-1", 12, AT, "C", "3.0", "n")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("LIMIT_EXCEEDED"));
        assertThat(permitCount("permit-1")).isZero();
        Integer obsRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM observation_current", Integer.class);
        assertThat(obsRows).isZero();
    }

    @Test
    void batchGridBoundaryBeyondPermitIsRejected() throws Exception {
        createPermit("req-p5", "permit-1", "dev-1", 10, 20, 10);
        // 单项网格 21 越界
        batch("req-b6", List.of(item("obs-1", null, "dev-1", 21, AT, "A", "1.0", "n")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        assertThat(permitCount("permit-1")).isZero();
    }

    @Test
    void batchRejectsWhenItemPermitCoordinatesMismatchReceivedObservation() throws Exception {
        createPermit("req-p6", "permit-1", "dev-1", 10, 20, 10);
        batch("req-b7", List.of(item("obs-1", null, "dev-1", 10, AT, "A", "1.0", "n")))
                .andExpect(status().isOk());
        // 合并项声明的网格与接收时不一致
        batch("req-b8", List.of(item("obs-1", 1, "dev-1", 11, AT, "A", "1.0", "n")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_MISMATCH"));
        assertThat(currentVersion("obs-1")).isEqualTo(1);
    }

    @Test
    void batchMergeConflictRegistersPendingClusterWithoutChangingContent() throws Exception {
        createPermit("req-p7", "permit-1", "dev-1", 10, 20, 10);
        // 服务端先把 obs-1 地点改为 站点B
        batch("req-b9", List.of(item("obs-1", null, "dev-1", 10, AT, "站点A", "1.0", "备注")))
                .andExpect(status().isOk());
        batch("req-b10", List.of(item("obs-1", 1, "dev-1", 10, AT, "站点B", "1.0", "备注")))
                .andExpect(status().isOk());
        // 离线端基于 v1 把地点改为 站点C：冲突，登记 PENDING 簇，内容保持 站点B/v2
        batch("req-b11", List.of(item("obs-1", 1, "dev-1", 10, AT, "站点C", "1.0", "离线备注")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].conflict").value(true))
                .andExpect(jsonPath("$.items[0].clusterId").value("req-b11:obs-1"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.clusters[0].status").value("PENDING"))
                .andExpect(jsonPath("$.clusters[0].conflictFields[0]").value("location"));

        mockMvc.perform(get("/api/sampling/clusters/req-b11:obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.observationId").value("obs-1"));
        assertThat(currentVersion("obs-1")).isEqualTo(2);
        // 此前站点B已由另一请求合并（MERGED）；冲突登记只保持内容/版本不变，不回退业务状态
        assertThat(statusOf("obs-1")).isEqualTo("MERGED");

        // 存在 PENDING 簇时再次批量合并同一观测：预校验拒绝
        batch("req-b12", List.of(item("obs-1", 2, "dev-1", 10, AT, "站点B", "1.0", "备注")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("CLUSTER_PENDING"));
    }

    @Test
    void clusterResolutionAppliesCandidateAndMarksResolved() throws Exception {
        createPermit("req-p8", "permit-1", "dev-1", 10, 20, 10);
        batch("req-c0", List.of(item("obs-1", null, "dev-1", 10, AT, "站点A", "1.0", "备注")))
                .andExpect(status().isOk());
        batch("req-c1", List.of(item("obs-1", 1, "dev-1", 10, AT, "站点B", "1.0", "备注")))
                .andExpect(status().isOk());
        batch("req-c2", List.of(item("obs-1", 1, "dev-1", 10, AT, "站点C", "1.0", "离线备注")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusters[0].clusterId").value("req-c2:obs-1"));

        Map<String, Object> resolve = new LinkedHashMap<>();
        resolve.put("requestId", "req-c3");
        resolve.put("resolutionId", "res-1");
        resolve.put("baseVersion", 1);
        resolve.put("expectedCurrentVersion", 2);
        resolve.put("location", "站点C");
        resolve.put("reading", "1.0");
        resolve.put("note", "离线备注");
        resolve.put("selections", Map.of("location", "CANDIDATE"));
        resolve.put("operator", "op-1");
        mockMvc.perform(post("/api/sampling/clusters/{id}/resolve", "req-c2:obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolve)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cluster.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution.version").value(3))
                .andExpect(jsonPath("$.resolution.location").value("站点C"))
                .andExpect(jsonPath("$.resolution.note").value("离线备注"));

        assertThat(currentVersion("obs-1")).isEqualTo(3);
        assertThat(statusOf("obs-1")).isEqualTo("MERGED");
        // 已裁决簇不重算：再次裁决返回已存在的 RESOLVED 结果
        mockMvc.perform(post("/api/sampling/clusters/{id}/resolve", "req-c2:obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolve)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cluster.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution.resolutionId").value("res-1"));
        assertThat(currentVersion("obs-1")).isEqualTo(3);
    }

    @Test
    void batchRequestIdIsIdempotentAndReplaysClusters() throws Exception {
        createPermit("req-p9", "permit-1", "dev-1", 10, 20, 10);
        List<Map<String, Object>> items = List.of(
                item("obs-1", null, "dev-1", 10, AT, "站点A", "1.0", "备注"),
                item("obs-2", null, "dev-1", 11, AT, "站点B", "2.0", "备注"));
        batch("req-b13", items).andExpect(status().isOk());
        // 同 requestId 同参重放：不再创建/扣计数
        batch("req-b13", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
        assertThat(permitCount("permit-1")).isEqualTo(2);
        Integer obsRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM observation_current", Integer.class);
        assertThat(obsRows).isEqualTo(2);
        // 同 requestId 改参：409
        batch("req-b13", List.of(item("obs-9", null, "dev-1", 10, AT, "X", "9.0", "n")))
                .andExpect(status().isConflict());
    }
}
