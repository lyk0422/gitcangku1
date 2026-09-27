package com.example.starter.sampling;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测采样许可与单次提交 API 测试：许可范围、交集冲突、唯一命中、计数上限、
 * 提前结束、撤销复核与拒绝原因（真实 H2 内存库，MySQL 兼容模式；时钟可控）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SamplingPermitApiTest {

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-10-01T00:00:00Z";
    private static final String T = "2026-09-15T00:00:00Z";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-28T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sampling_observation_snapshot");
        jdbcTemplate.update("DELETE FROM sampling_rejection");
        jdbcTemplate.update("DELETE FROM sampling_observation");
        jdbcTemplate.update("DELETE FROM sampling_conflict_cluster");
        jdbcTemplate.update("DELETE FROM sampling_merge_batch");
        jdbcTemplate.update("DELETE FROM sampling_permit");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
    }

    private ResultActions createPermit(String permitId, String deviceId,
                                       int minX, int maxX, int minY, int maxY,
                                       String validFrom, String validTo,
                                       int maxObservations, int permitVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("permitId", permitId);
        body.put("deviceId", deviceId);
        body.put("gridMinX", minX);
        body.put("gridMaxX", maxX);
        body.put("gridMinY", minY);
        body.put("gridMaxY", maxY);
        body.put("validFromUtc", validFrom);
        body.put("validToUtc", validTo);
        body.put("maxObservations", maxObservations);
        body.put("permitVersion", permitVersion);
        return mockMvc.perform(post("/api/sampling/permits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submit(String deviceId, int permitVersion, int gridX, int gridY,
                                 String collectedAt, String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", deviceId);
        body.put("permitVersion", permitVersion);
        body.put("gridX", gridX);
        body.put("gridY", gridY);
        body.put("collectedAtUtc", collectedAt);
        body.put("content", content);
        return mockMvc.perform(post("/api/sampling/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String submitAndGetKey(String deviceId, int permitVersion, int gridX, int gridY,
                                   String collectedAt, String content) throws Exception {
        MvcResult result = submit(deviceId, permitVersion, gridX, gridY, collectedAt, content)
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("permitKey").asText();
    }

    private ResultActions endPermit(String permitId, String endedAt) throws Exception {
        return mockMvc.perform(post("/api/sampling/permits/{id}/end", permitId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("endedAtUtc", endedAt))));
    }

    private ResultActions revokePermit(String permitId) throws Exception {
        return mockMvc.perform(post("/api/sampling/permits/{id}/revoke", permitId));
    }

    private ResultActions getPermit(String permitId) throws Exception {
        return mockMvc.perform(get("/api/sampling/permits/{id}", permitId));
    }

    private ResultActions getObservation(String permitKey) throws Exception {
        return mockMvc.perform(get("/api/sampling/observations/{key}", permitKey));
    }

    private ResultActions listPermitObservations(String permitId) throws Exception {
        return mockMvc.perform(get("/api/sampling/permits/{id}/observations", permitId));
    }

    private ResultActions listRejections(String deviceId) throws Exception {
        return mockMvc.perform(get("/api/sampling/rejections").param("deviceId", deviceId));
    }

    private int permitUsedCount(String permitId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT used_count FROM sampling_permit WHERE permit_id = ?", Integer.class, permitId);
        return count == null ? -1 : count;
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sampling_observation", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 许可创建与交集冲突 ----------

    @Test
    void createPermitReturnsCreatedWithCounts() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 100, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.gridMinX").value(0))
                .andExpect(jsonPath("$.gridMaxX").value(10))
                .andExpect(jsonPath("$.validFromUtc").value(FROM))
                .andExpect(jsonPath("$.validToUtc").value(TO))
                .andExpect(jsonPath("$.maxObservations").value(100))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(100))
                .andExpect(jsonPath("$.permitVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        getPermit("permit-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.remainingCount").value(100));
    }

    @Test
    void createPermitWithInvalidWindowOrGridReturns400() throws Exception {
        // 起点不早于终点
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, TO, FROM, 100, 1)
                .andExpect(status().isBadRequest());
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, FROM, 100, 1)
                .andExpect(status().isBadRequest());
        // 网格下界大于上界
        createPermit("permit-1", "dev-1", 11, 10, 0, 10, FROM, TO, 100, 1)
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sampling_permit", Integer.class)).isZero();
    }

    @Test
    void duplicatePermitIdReturns409() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 100, 1)
                .andExpect(status().isCreated());
        createPermit("permit-1", "dev-2", 20, 30, 20, 30, FROM, TO, 50, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_EXISTS"));
    }

    @Test
    void overlappingPermitInGridAndTimeReturns409() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 100, 1)
                .andExpect(status().isCreated());
        // 网格与时间均相交
        createPermit("permit-2", "dev-1", 5, 15, 5, 15, FROM, TO, 100, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_OVERLAP"));
        // 时间相交但网格不相交：允许
        createPermit("permit-3", "dev-1", 20, 30, 20, 30, FROM, TO, 100, 1)
                .andExpect(status().isCreated());
        // 网格相交但时间不相交（左闭右开，端点相接不算相交）：允许
        createPermit("permit-4", "dev-1", 5, 15, 5, 15, TO, "2026-11-01T00:00:00Z", 100, 1)
                .andExpect(status().isCreated());
        // 网格与时间均相交但设备不同：允许
        createPermit("permit-5", "dev-2", 5, 15, 5, 15, FROM, TO, 100, 1)
                .andExpect(status().isCreated());
    }

    // ---------- 提交观测 ----------

    @Test
    void submitSuccessHitsUniquePermitAndDecrementsQuota() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 2, 7)
                .andExpect(status().isCreated());

        String key = submitAndGetKey("dev-1", 7, 5, 5, T, "温度 23.5");
        getPermit("permit-1")
                .andExpect(jsonPath("$.usedCount").value(1))
                .andExpect(jsonPath("$.remainingCount").value(1));

        // 观测许可版本与复核标记可查
        getObservation(key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permitKey").value(key))
                .andExpect(jsonPath("$.permitId").value("permit-1"))
                .andExpect(jsonPath("$.permitVersion").value(7))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.reviewFlag").value(false))
                .andExpect(jsonPath("$.content").value("温度 23.5"));
    }

    @Test
    void submitWithoutPermitReturns422AndRecordsRejection() throws Exception {
        submit("dev-1", 1, 5, 5, T, "无许可")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));

        // 拒绝原因可查询，拒绝时刻来自可控时钟
        listRejections("dev-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scope").value("SINGLE"))
                .andExpect(jsonPath("$[0].reason").value("NO_PERMIT"))
                .andExpect(jsonPath("$[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$[0].gridX").value(5))
                .andExpect(jsonPath("$[0].rejectedAtUtc").value("2026-09-28T08:00:00Z"));
        assertThat(observationCount()).isZero();
    }

    @Test
    void submitOutOfBoundsGridReturns422() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1)
                .andExpect(status().isCreated());
        submit("dev-1", 1, -1, 5, T, "越界")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("GRID_OUT_OF_BOUNDS"));
        submit("dev-1", 1, 5, 1_000_000, T, "越界")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("GRID_OUT_OF_BOUNDS"));
        assertThat(observationCount()).isZero();
        assertThat(permitUsedCount("permit-1")).isZero();
    }

    @Test
    void submitWithMismatchedPermitVersionReturns422() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 3)
                .andExpect(status().isCreated());
        submit("dev-1", 2, 5, 5, T, "版本不一致")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_VERSION_MISMATCH"));
        listRejections("dev-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("PERMIT_VERSION_MISMATCH"));
        assertThat(permitUsedCount("permit-1")).isZero();
    }

    @Test
    void submitBeyondLimitReturns422AndKeepsCount() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 2, 1)
                .andExpect(status().isCreated());
        submit("dev-1", 1, 1, 1, T, "第一条").andExpect(status().isCreated());
        submit("dev-1", 1, 2, 2, T, "第二条").andExpect(status().isCreated());
        // 第三条超限：422，计数不变，拒绝原因可区分
        submit("dev-1", 1, 3, 3, T, "第三条")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_LIMIT_EXCEEDED"));
        getPermit("permit-1")
                .andExpect(jsonPath("$.usedCount").value(2))
                .andExpect(jsonPath("$.remainingCount").value(0));
        listRejections("dev-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("PERMIT_LIMIT_EXCEEDED"));
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void submitOutsideValidityWindowReturns422() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1)
                .andExpect(status().isCreated());
        // 起点之前与终点（右开）均不命中
        submit("dev-1", 1, 5, 5, "2026-08-31T23:59:59Z", "过早")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        submit("dev-1", 1, 5, 5, TO, "恰为终点")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 起点（左闭）命中
        submit("dev-1", 1, 5, 5, FROM, "恰为起点").andExpect(status().isCreated());
    }

    @Test
    void multipleHitPermitsReturn422NotUnique() throws Exception {
        // 直接写入两个相交的有效许可（绕过创建校验），验证命中不唯一分支
        insertPermitDirectly("permit-a", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1);
        insertPermitDirectly("permit-b", "dev-1", 5, 15, 5, 15, FROM, TO, 10, 1);
        submit("dev-1", 1, 7, 7, T, "命中两个许可")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_UNIQUE"));
        listRejections("dev-1")
                .andExpect(jsonPath("$[0].reason").value("PERMIT_NOT_UNIQUE"));
        assertThat(observationCount()).isZero();
    }

    private void insertPermitDirectly(String permitId, String deviceId, int minX, int maxX, int minY, int maxY,
                                      String validFrom, String validTo, int maxObservations, int permitVersion) {
        jdbcTemplate.update(
                "INSERT INTO sampling_permit (permit_id, device_id, grid_min_x, grid_max_x, grid_min_y, grid_max_y, "
                        + "valid_from_utc, valid_to_utc, max_observations, used_count, permit_version, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'ACTIVE', CURRENT_TIMESTAMP)",
                permitId, deviceId, minX, maxX, minY, maxY,
                Timestamp.from(Instant.parse(validFrom)), Timestamp.from(Instant.parse(validTo)),
                maxObservations, permitVersion);
    }

    // ---------- 幂等与不可追溯 ----------

    @Test
    void sameKeyReplayReturnsOriginalResultWithoutDoubleCount() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1)
                .andExpect(status().isCreated());
        String key = submitAndGetKey("dev-1", 1, 5, 5, T, "同一观测");
        // 同键重放：返回原结果，不重复计数
        submit("dev-1", 1, 5, 5, T, "同一观测")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.permitKey").value(key))
                .andExpect(jsonPath("$.usedCount").value(1));
        assertThat(observationCount()).isEqualTo(1);
        assertThat(permitUsedCount("permit-1")).isEqualTo(1);
    }

    @Test
    void failedSubmitDoesNotOccupyKeyAndNewPermitIsNotRetroactive() throws Exception {
        // 无许可时被拒绝
        submit("dev-1", 1, 5, 5, T, "迟到的许可")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 之后创建许可：历史拒绝记录不回溯改写
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1)
                .andExpect(status().isCreated());
        listRejections("dev-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("NO_PERMIT"));
        // 失败不占键：同一请求重新评估后成功
        submit("dev-1", 1, 5, 5, T, "迟到的许可")
                .andExpect(status().isCreated());
        assertThat(permitUsedCount("permit-1")).isEqualTo(1);
        // 拒绝记录仍保留（新许可不追溯使过去拒绝请求成功）
        listRejections("dev-1")
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---------- 许可提前结束 ----------

    @Test
    void endPermitEarlyOnlyAffectsLaterSampling() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1)
                .andExpect(status().isCreated());
        String key = submitAndGetKey("dev-1", 1, 5, 5, "2026-09-10T00:00:00Z", "结束前采集");

        endPermit("permit-1", "2026-09-20T00:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.validToUtc").value("2026-09-20T00:00:00Z"));

        // 已接收观测不删除、状态不变
        getObservation(key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.reviewFlag").value(false));
        // 结束之后采集的提交不再命中
        submit("dev-1", 1, 5, 5, "2026-09-25T00:00:00Z", "结束后采集")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 结束之前采集的迟到提交仍可命中（提前结束只影响之后采样）
        submit("dev-1", 1, 6, 6, "2026-09-12T00:00:00Z", "迟到的结束前采集")
                .andExpect(status().isCreated());
        assertThat(permitUsedCount("permit-1")).isEqualTo(2);
    }

    @Test
    void endPermitValidatesWindowAndStatus() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1)
                .andExpect(status().isCreated());
        // 新终点不在原区间内：400
        endPermit("permit-1", TO).andExpect(status().isBadRequest());
        endPermit("permit-1", FROM).andExpect(status().isBadRequest());
        endPermit("permit-1", "2026-11-01T00:00:00Z").andExpect(status().isBadRequest());
        // 许可不存在：404
        endPermit("permit-x", "2026-09-20T00:00:00Z").andExpect(status().isNotFound());
        // 正常结束后再次结束：409
        endPermit("permit-1", "2026-09-20T00:00:00Z").andExpect(status().isOk());
        endPermit("permit-1", "2026-09-15T00:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_ACTIVE"));
    }

    // ---------- 许可撤销与复核标记 ----------

    @Test
    void revokeMarksUnmergedObservationsForReviewWithSnapshots() throws Exception {
        createPermit("permit-1", "dev-1", 0, 10, 0, 10, FROM, TO, 10, 1)
                .andExpect(status().isCreated());
        String key1 = submitAndGetKey("dev-1", 1, 1, 1, T, "观测一");
        String key2 = submitAndGetKey("dev-1", 1, 2, 2, T, "观测二");

        revokePermit("permit-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // 未合并观测标记 PERMIT_REVIEW，复核标记可查
        getObservation(key1)
                .andExpect(jsonPath("$.status").value("PERMIT_REVIEW"))
                .andExpect(jsonPath("$.reviewFlag").value(true));
        getObservation(key2)
                .andExpect(jsonPath("$.status").value("PERMIT_REVIEW"))
                .andExpect(jsonPath("$.reviewFlag").value(true));
        listPermitObservations("permit-1")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reviewFlag").value(true))
                .andExpect(jsonPath("$[1].reviewFlag").value(true));
        // 每条标记均写入状态快照
        Integer snapshots = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sampling_observation_snapshot WHERE reason = 'PERMIT_REVOKED'", Integer.class);
        assertThat(snapshots).isEqualTo(2);
        // 撤销后新提交不再命中
        submit("dev-1", 1, 3, 3, T, "撤销后采集")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("NO_PERMIT"));
        // 重复撤销：409
        revokePermit("permit-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PERMIT_NOT_ACTIVE"));
        // 许可不存在：404
        revokePermit("permit-x").andExpect(status().isNotFound());
    }

    @Test
    void getMissingPermitOrObservationReturns404() throws Exception {
        getPermit("permit-x").andExpect(status().isNotFound());
        getObservation("no-such-key").andExpect(status().isNotFound());
        listPermitObservations("permit-x").andExpect(status().isNotFound());
    }
}
