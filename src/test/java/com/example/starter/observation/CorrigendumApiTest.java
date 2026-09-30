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
 * 更正附录 API 测试（真实 H2 内存库，MODE=MySQL）：主流程（已解决簇 10→11、V0 仍为 10）、
 * 422/409/400/410/404 拒绝分支、requestId 幂等、快照只读与旧版本不漂移。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrigendumApiTest {

    private static final Instant FIXED_V1_TIME = Instant.parse("2026-09-30T08:00:00Z");
    private static final Instant FIXED_V2_TIME = Instant.parse("2026-09-30T09:30:00Z");

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
        jdbcTemplate.update("DELETE FROM observation_corrigendum");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_V1_TIME);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
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
                                  String location, String note, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", "10");
        body.put("note", note);
        body.put("selections", Map.of("location", "CANDIDATE"));
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions submitCorrigendum(String requestId, String observationId,
                                            int expectedPreviousVersion,
                                            List<Map<String, String>> changes, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedPreviousVersion", expectedPreviousVersion);
        body.put("changes", changes);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/corrigenda", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<Map<String, String>> changes(String field, String oldValue, String newValue) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", field);
        entry.put("oldValue", oldValue);
        entry.put("newValue", newValue);
        return List.of(entry);
    }

    private ResultActions deleteObservation(String requestId, String observationId, int expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int corrigendumCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_corrigendum WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    /**
     * 构造已解决簇 C：v1=(站点A,10,备注) → v2 服务端改地点为站点B → 离线站点C 冲突 →
     * 裁决选 CANDIDATE 生成 v3=(站点C,10,备注)。裁决后读数原值为 10。
     */
    private void setupResolvedClusterC(String observationId) throws Exception {
        create("req-create-" + observationId, observationId, "站点A", "10", "备注")
                .andExpect(status().isCreated());
        merge("req-server-" + observationId, observationId, 1, "站点B", "10", "备注")
                .andExpect(status().isOk());
        merge("req-offline-fail-" + observationId, observationId, 1, "站点C", "10", "备注")
                .andExpect(status().isConflict());
        resolve("req-resolve-" + observationId, "res-" + observationId, observationId,
                1, 2, "站点C", "备注", "reviewer-a").andExpect(status().isOk());
    }

    // ---------- 主流程：题干场景 ----------

    @Test
    void resolvedClusterCorrectionV1ChangesTenToElevenAndV0StaysTen() throws Exception {
        setupResolvedClusterC("obs-c");

        submitCorrigendum("req-corr-1", "obs-c", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(1))
                .andExpect(jsonPath("$.previousVersion").value(0))
                .andExpect(jsonPath("$.snapshotVersion").value(3))
                .andExpect(jsonPath("$.operator").value("recorder-a"))
                .andExpect(jsonPath("$.submittedAtUtc").value("2026-09-30T08:00:00Z"))
                .andExpect(jsonPath("$.changes[0].field").value("reading"))
                .andExpect(jsonPath("$.changes[0].oldValue").value("10"))
                .andExpect(jsonPath("$.changes[0].newValue").value("11"))
                .andExpect(jsonPath("$.reading").value("11"));

        // 读取 V0 仍为原始值 10，不随附录漂移
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-c", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrigendumVersion").value(0))
                .andExpect(jsonPath("$.snapshotVersion").value(3))
                .andExpect(jsonPath("$.operator").doesNotExist())
                .andExpect(jsonPath("$.changes.length()").value(0))
                .andExpect(jsonPath("$.reading").value("10"))
                .andExpect(jsonPath("$.location").value("站点C"));
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-c", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("11"))
                .andExpect(jsonPath("$.location").value("站点C"));

        // 版本链串联原始快照与每次更正的操作者、时刻及差异
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedCluster").value(true))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].corrigendumVersion").value(0))
                .andExpect(jsonPath("$.versions[0].reading").value("10"))
                .andExpect(jsonPath("$.versions[1].corrigendumVersion").value(1))
                .andExpect(jsonPath("$.versions[1].operator").value("recorder-a"))
                .andExpect(jsonPath("$.versions[1].submittedAtUtc").value("2026-09-30T08:00:00Z"))
                .andExpect(jsonPath("$.versions[1].changes[0].oldValue").value("10"))
                .andExpect(jsonPath("$.versions[1].changes[0].newValue").value("11"))
                .andExpect(jsonPath("$.versions[1].reading").value("11"));
        assertThat(corrigendumCount("obs-c")).isEqualTo(1);
    }

    // ---------- 422：未解决簇 / 绕过裁决 ----------

    @Test
    void unresolvedClusterSubmitReturns422AndVersionIsNotConsumed() throws Exception {
        create("req-u1", "obs-u", "站点A", "10", "备注").andExpect(status().isCreated());

        // 未解决簇提交同样附录：422
        submitCorrigendum("req-u2", "obs-u", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isUnprocessableEntity());
        assertThat(corrigendumCount("obs-u")).isZero();
        assertThat(requestLogCount("req-u2")).isZero();

        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-u"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedCluster").value(false))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].corrigendumVersion").value(0));

        // 冲突裁决解决后，同一 requestId 与参数可正常提交，证明失败未占键也未占版本号
        merge("req-u3", "obs-u", 1, "站点B", "10", "备注").andExpect(status().isOk());
        merge("req-u4", "obs-u", 1, "站点C", "10", "备注").andExpect(status().isConflict());
        resolve("req-u5", "res-u", "obs-u", 1, 2, "站点C", "备注", "reviewer-a")
                .andExpect(status().isOk());
        submitCorrigendum("req-u2", "obs-u", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(1));
        assertThat(corrigendumCount("obs-u")).isEqualTo(1);
    }

    @Test
    void conflictedButUnresolvedClusterSubmitReturns422() throws Exception {
        create("req-cu1", "obs-cu", "站点A", "10", "备注").andExpect(status().isCreated());
        merge("req-cu2", "obs-cu", 1, "站点B", "10", "备注").andExpect(status().isOk());
        // 存在未解决冲突（普通合并收到 409）但没有裁决记录
        merge("req-cu3", "obs-cu", 1, "站点C", "10", "备注").andExpect(status().isConflict());

        submitCorrigendum("req-cu4", "obs-cu", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isUnprocessableEntity());
        assertThat(corrigendumCount("obs-cu")).isZero();
    }

    @Test
    void oldValueMismatchReturns422AndDoesNotBypassResolution() throws Exception {
        setupResolvedClusterC("obs-m");

        // 旧值 9 与裁决后有效值 10 不一致：拒绝，防止借附录覆盖裁决
        submitCorrigendum("req-m1", "obs-m", 0, changes("reading", "9", "11"), "recorder-a")
                .andExpect(status().isUnprocessableEntity());
        assertThat(corrigendumCount("obs-m")).isZero();
        assertThat(requestLogCount("req-m1")).isZero();

        // 同一 requestId 用正确旧值重试成功，版本仍从 V1 开始
        submitCorrigendum("req-m1", "obs-m", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(1));

        // 第二份附录的旧值必须是 V1 有效值 11；仍写 10 属于试图绕过中间版本，422
        submitCorrigendum("req-m2", "obs-m", 1, changes("reading", "10", "12"), "recorder-a")
                .andExpect(status().isUnprocessableEntity());
        assertThat(corrigendumCount("obs-m")).isEqualTo(1);
        assertThat(requestLogCount("req-m2")).isZero();

        submitCorrigendum("req-m2", "obs-m", 1, changes("reading", "11", "12"), "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(2))
                .andExpect(jsonPath("$.reading").value("12"));
    }

    @Test
    void numericOldValueAcceptedWithDifferentDecimalForm() throws Exception {
        setupResolvedClusterC("obs-n");
        // 旧值 10.000 与有效值 10 数值相等：接受
        submitCorrigendum("req-n1", "obs-n", 0, changes("reading", "10.000", "11"), "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(1));
    }

    // ---------- 409：版本冲突与幂等换字段 ----------

    @Test
    void concurrentPretendersOnSamePreviousVersionSecondGets409() throws Exception {
        setupResolvedClusterC("obs-p");
        submitCorrigendum("req-p1", "obs-p", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(1));
        // 仍以前置版本 0 争用：版本冲突，并回传服务端最新附录版本
        submitCorrigendum("req-p2", "obs-p", 0, changes("note", "备注", "迟到备注"), "recorder-b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        assertThat(corrigendumCount("obs-p")).isEqualTo(1);
        assertThat(requestLogCount("req-p2")).isZero();

        // 基于最新版本 1 重试成功
        submitCorrigendum("req-p2", "obs-p", 1, changes("note", "备注", "迟到备注"), "recorder-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(2));
    }

    @Test
    void sameRequestIdReplaysFirstVersionAndChangedFieldsReturn409() throws Exception {
        setupResolvedClusterC("obs-i");
        for (int i = 0; i < 2; i++) {
            submitCorrigendum("req-i1", "obs-i", 0, changes("reading", "10", "11"), "recorder-a")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.corrigendumVersion").value(1))
                    .andExpect(jsonPath("$.reading").value("11"));
        }
        assertThat(corrigendumCount("obs-i")).isEqualTo(1);
        assertThat(requestLogCount("req-i1")).isEqualTo(1);

        // 同键换字段（异参）：409
        submitCorrigendum("req-i1", "obs-i", 0, changes("note", "备注", "换字段"), "recorder-a")
                .andExpect(status().isConflict());
        // 同键改新值：409
        submitCorrigendum("req-i1", "obs-i", 0, changes("reading", "10", "12"), "recorder-a")
                .andExpect(status().isConflict());
        assertThat(corrigendumCount("obs-i")).isEqualTo(1);
    }

    // ---------- 400：删除字段、非法值 ----------

    @Test
    void blankNewValueAsFieldDeletionReturns400() throws Exception {
        setupResolvedClusterC("obs-d");
        submitCorrigendum("req-d1", "obs-d", 0, changes("note", "备注", "  "), "recorder-a")
                .andExpect(status().isBadRequest());
        assertThat(corrigendumCount("obs-d")).isZero();
        assertThat(requestLogCount("req-d1")).isZero();
    }

    @Test
    void invalidReadingAndDuplicateAndUnknownFieldsReturn400() throws Exception {
        setupResolvedClusterC("obs-b");
        // 四位小数
        submitCorrigendum("req-b1", "obs-b", 0, changes("reading", "10", "11.1234"), "recorder-a")
                .andExpect(status().isBadRequest());
        // 非数字
        submitCorrigendum("req-b2", "obs-b", 0, changes("reading", "10", "abc"), "recorder-a")
                .andExpect(status().isBadRequest());
        // 非法字段名
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-b3");
        body.put("expectedPreviousVersion", 0);
        body.put("changes", List.of(Map.of("field", "unknown", "oldValue", "x", "newValue", "y")));
        body.put("operator", "recorder-a");
        mockMvc.perform(post("/api/observations/{id}/corrigenda", "obs-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        // 重复字段
        body.put("requestId", "req-b4");
        body.put("changes", List.of(
                Map.of("field", "reading", "oldValue", "10", "newValue", "11"),
                Map.of("field", "reading", "oldValue", "11", "newValue", "12")));
        mockMvc.perform(post("/api/observations/{id}/corrigenda", "obs-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        // 空差异列表
        body.put("requestId", "req-b5");
        body.put("changes", List.of());
        mockMvc.perform(post("/api/observations/{id}/corrigenda", "obs-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(corrigendumCount("obs-b")).isZero();
    }

    // ---------- 410 / 404 ----------

    @Test
    void deletedObservationRejectsCorrigendumWith410() throws Exception {
        setupResolvedClusterC("obs-t");
        deleteObservation("req-t1", "obs-t", 3).andExpect(status().isOk());
        submitCorrigendum("req-t2", "obs-t", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isGone());
        assertThat(corrigendumCount("obs-t")).isZero();
    }

    @Test
    void missingObservationAndVersionReturn404() throws Exception {
        submitCorrigendum("req-x1", "obs-x", 0, changes("reading", "10", "11"), "op")
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-x"))
                .andExpect(status().isNotFound());

        setupResolvedClusterC("obs-v");
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-v", 0))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-v", 1))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-v", 9))
                .andExpect(status().isNotFound());
    }

    // ---------- 快照只读、旧版本不漂移 ----------

    @Test
    void snapshotsRemainReadOnlyAndOldVersionsDoNotDrift() throws Exception {
        setupResolvedClusterC("obs-s");
        submitCorrigendum("req-s1", "obs-s", 0, changes("reading", "10", "11"), "recorder-a")
                .andExpect(status().isCreated());

        // 既有观测快照与当前行不被附录覆盖：当前观测读数仍是裁决快照值 10、版本仍是 3
        mockMvc.perform(get("/api/observations/{id}", "obs-s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("10"));
        String snapshotReading = jdbcTemplate.queryForObject(
                "SELECT reading FROM observation_version WHERE observation_id = 'obs-s' AND version = 3",
                String.class);
        assertThat(snapshotReading).isEqualTo("10");
        Integer snapRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-s'", Integer.class);
        assertThat(snapRows).isEqualTo(3);

        // 附录之后继续走普通合并到 v4（改备注），附录链锚定的原始快照不受影响
        merge("req-s2", "obs-s", 3, "站点C", "10", "合并备注").andExpect(status().isOk());

        Mockito.when(clock.instant()).thenReturn(FIXED_V2_TIME);
        // V2 基于附录 V1 的有效值（备注仍是锚定快照的“备注”），而非观测当前 v4 的“合并备注”
        submitCorrigendum("req-s3", "obs-s", 1, changes("note", "备注", "附录备注"), "recorder-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.corrigendumVersion").value(2))
                .andExpect(jsonPath("$.snapshotVersion").value(3))
                .andExpect(jsonPath("$.note").value("附录备注"))
                .andExpect(jsonPath("$.reading").value("11"))
                .andExpect(jsonPath("$.location").value("站点C"));

        // 旧版本查询始终返回当时值，不随新附录漂移
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-s", 0))
                .andExpect(jsonPath("$.reading").value("10"))
                .andExpect(jsonPath("$.note").value("备注"))
                .andExpect(jsonPath("$.snapshotVersion").value(3));
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-s", 1))
                .andExpect(jsonPath("$.reading").value("11"))
                .andExpect(jsonPath("$.note").value("备注"));
        mockMvc.perform(get("/api/observations/{id}/corrigenda/versions/{version}", "obs-s", 2))
                .andExpect(jsonPath("$.reading").value("11"))
                .andExpect(jsonPath("$.note").value("附录备注"))
                .andExpect(jsonPath("$.submittedAtUtc").value("2026-09-30T09:30:00Z"));

        // 版本链完整串联：V0 快照 + V1/V2 的操作者、时刻、差异
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-s"))
                .andExpect(jsonPath("$.versions.length()").value(3))
                .andExpect(jsonPath("$.versions[1].operator").value("recorder-a"))
                .andExpect(jsonPath("$.versions[1].changes[0].newValue").value("11"))
                .andExpect(jsonPath("$.versions[2].operator").value("recorder-b"))
                .andExpect(jsonPath("$.versions[2].previousVersion").doesNotExist())
                .andExpect(jsonPath("$.versions[2].changes[0].field").value("note"));
        // 观测侧仍是 v4，附录不改快照版本
        mockMvc.perform(get("/api/observations/{id}", "obs-s"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.note").value("合并备注"));
    }
}
