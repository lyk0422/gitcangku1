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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冲突显式解决 API 测试：冲突重算、选择校验、无变化解决、双重幂等、版本/删除竞争前置条件与历史查询。
 * 使用真实 H2 内存库与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResolutionApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-22T10:15:30Z");

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
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
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

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int resolutionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    /**
     * 构造标准冲突场景：v1=(站点A,1.0,备注)，服务端合并到 v2=(站点B,...)，离线端基于 v1 改站点C 普通合并收到 409。
     */
    private void setupLocationConflict(String observationId) throws Exception {
        create("req-create-" + observationId, observationId, "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-server-" + observationId, observationId, 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-offline-fail-" + observationId, observationId, 1, "站点C", "1.0", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));
    }

    // ---------- 主流程 ----------

    @Test
    void resolveCandidateCreatesVersionAndImmutableRecord() throws Exception {
        setupLocationConflict("obs-1");

        // 非冲突字段备注由服务端自动合并为候选值；冲突字段地点人工选择 CANDIDATE
        resolve("req-res-1", "res-1", "obs-1", 1, 2,
                "站点C", "1.0", "离线备注", Map.of("location", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionId").value("res-1"))
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.requestId").value("req-res-1"))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.previousVersion").value(2))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.selections.location").value("CANDIDATE"))
                .andExpect(jsonPath("$.operator").value("operator-a"))
                .andExpect(jsonPath("$.resolvedAtUtc").value("2026-09-22T10:15:30Z"))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.note").value("离线备注"));

        mockMvc.perform(get("/api/observations/{id}", "obs-1"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.note").value("离线备注"));
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.candidateReading").doesNotExist())
                .andExpect(jsonPath("$.reading").value("1.0"));
        mockMvc.perform(get("/api/observations/{id}/resolutions", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resolutionId").value("res-1"));
        assertThat(versionCount("obs-1")).isEqualTo(3);
        assertThat(resolutionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void resolveCurrentWithoutContentChangeKeepsVersionButSavesRecord() throws Exception {
        setupLocationConflict("obs-2");

        resolve("req-res-2", "res-2", "obs-2", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CURRENT"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.previousVersion").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.location").value("站点B"));

        // 不增加观测版本，但保存指向当前版本的解决记录
        mockMvc.perform(get("/api/observations/{id}", "obs-2"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false));
        assertThat(versionCount("obs-2")).isEqualTo(2);
        assertThat(resolutionCount("obs-2")).isEqualTo(1);
    }

    // ---------- 服务端重算冲突 ----------

    @Test
    void resolveRecalculatesConflictsAndRejectsStaleSelectionList() throws Exception {
        // v1：站点A/备注；v2 服务端同时改地点为站点B、备注为服务端备注
        create("req-rc-0", "obs-3", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-rc-1", "obs-3", 1, "站点B", "1.0", "服务端备注").andExpect(status().isOk());
        // 离线端基于 v1 改站点C/离线备注：地点、备注双冲突
        merge("req-rc-2", "obs-3", 1, "站点C", "1.0", "离线备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.conflictFields[1]").value("note"));
        // 服务端继续前进到 v3：地点改为站点C（与离线端相同），备注保持服务端备注
        merge("req-rc-3", "obs-3", 2, "站点C", "1.0", "服务端备注").andExpect(status().isOk());

        // 客户端仍按上次看到的双冲突提交：地点已不再冲突，多选非冲突字段，400 且不写入
        resolve("req-rc-4", "res-3-bad", "obs-3", 1, 3,
                "站点C", "1.0", "离线备注",
                Map.of("location", "CANDIDATE", "note", "CANDIDATE"), "op")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-3")).isEqualTo(3);
        assertThat(resolutionCount("obs-3")).isZero();
        assertThat(requestLogCount("req-rc-4")).isZero();

        // 按服务端重算结果只选择备注：地点自动取相同值站点C，备注接受候选，生成 v4
        resolve("req-rc-5", "res-3", "obs-3", 1, 3,
                "站点C", "1.0", "离线备注", Map.of("note", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.conflictFields[0]").value("note"))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.note").value("离线备注"));
        assertThat(versionCount("obs-3")).isEqualTo(4);
    }

    @Test
    void resolveWithNoConflictsAutoMergesWithEmptySelections() throws Exception {
        setupLocationConflict("obs-4");

        // 候选地点已与当前站点B相同（自动接受），备注服务端未改则接受候选：无冲突，选择必须为空
        resolve("req-rn-1", "res-4", "obs-4", 1, 2,
                "站点B", "1.0", "离线备注", Map.of(), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.conflictFields.length()").value(0))
                .andExpect(jsonPath("$.selections.length()").value(0))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.note").value("离线备注"));

        // 无冲突却携带选择：多选非冲突字段，400
        resolve("req-rn-2", "res-4-x", "obs-4", 1, 3,
                "站点B", "1.0", "再次备注", Map.of("location", "CURRENT"), "op")
                .andExpect(status().isBadRequest());
    }

    // ---------- 选择校验 ----------

    @Test
    void resolveInvalidSelectionsReturn400AndWriteNothing() throws Exception {
        setupLocationConflict("obs-5");

        // 遗漏冲突字段
        resolve("req-rv-1", "res-5a", "obs-5", 1, 2,
                "站点C", "1.0", "备注", Map.of(), "op")
                .andExpect(status().isBadRequest());
        // 选择 BASE 不允许
        resolve("req-rv-2", "res-5b", "obs-5", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "BASE"), "op")
                .andExpect(status().isBadRequest());
        // 非法选择值
        resolve("req-rv-3", "res-5c", "obs-5", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "HEAD"), "op")
                .andExpect(status().isBadRequest());
        // 非法字段名（多选非冲突字段）
        resolve("req-rv-4", "res-5d", "obs-5", 1, 2,
                "站点C", "1.0", "备注",
                Map.of("location", "CURRENT", "unknown", "CURRENT"), "op")
                .andExpect(status().isBadRequest());

        assertThat(versionCount("obs-5")).isEqualTo(2);
        assertThat(resolutionCount("obs-5")).isZero();
        mockMvc.perform(get("/api/observations/{id}", "obs-5"))
                .andExpect(jsonPath("$.location").value("站点B"));
    }

    @Test
    void resolveMissingSelectionsFieldReturns400() throws Exception {
        setupLocationConflict("obs-6");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-rv-5");
        body.put("resolutionId", "res-6");
        body.put("baseVersion", 1);
        body.put("expectedCurrentVersion", 2);
        body.put("location", "站点C");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("operator", "op");
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(resolutionCount("obs-6")).isZero();
    }

    // ---------- 版本/基线/墓碑 ----------

    @Test
    void resolveExpectedCurrentVersionMismatchReturns409() throws Exception {
        setupLocationConflict("obs-7");
        resolve("req-rm-1", "res-7", "obs-7", 1, 9,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(versionCount("obs-7")).isEqualTo(2);
        assertThat(resolutionCount("obs-7")).isZero();
        assertThat(requestLogCount("req-rm-1")).isZero();
    }

    @Test
    void resolveMissingBaseVersionReturns404() throws Exception {
        setupLocationConflict("obs-8");
        resolve("req-rb-1", "res-8", "obs-8", 9, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isNotFound());
        assertThat(versionCount("obs-8")).isEqualTo(2);
        assertThat(resolutionCount("obs-8")).isZero();
    }

    @Test
    void resolveOnMissingObservationReturns404() throws Exception {
        resolve("req-rb-2", "res-x0", "obs-x", 1, 1,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveOnDeletedObservationReturns410ButReplayStillSucceeds() throws Exception {
        setupLocationConflict("obs-9");
        resolve("req-rd-1", "res-9", "obs-9", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        delete("req-rd-2", "obs-9", 3).andExpect(status().isOk());

        // 墓碑上的新解决请求：410，不复活
        resolve("req-rd-3", "res-9-new", "obs-9", 1, 4,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isGone());
        // 原 requestId 同参重放：返回原成功结果，墓碑不受影响
        resolve("req-rd-1", "res-9", "obs-9", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.resolutionId").value("res-9"));
        mockMvc.perform(get("/api/observations/{id}", "obs-9"))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(4));
        assertThat(resolutionCount("obs-9")).isEqualTo(1);
    }

    // ---------- resolutionId 与 requestId 双重幂等 ----------

    @Test
    void sameResolutionAndRequestReplaysOriginalResult() throws Exception {
        setupLocationConflict("obs-10");
        for (int i = 0; i < 2; i++) {
            resolve("req-rr-1", "res-10", "obs-10", 1, 2,
                    "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.contentChanged").value(true));
        }
        assertThat(versionCount("obs-10")).isEqualTo(3);
        assertThat(resolutionCount("obs-10")).isEqualTo(1);
        assertThat(requestLogCount("req-rr-1")).isEqualTo(1);
    }

    @Test
    void sameResolutionIdWithNewRequestAndSameParamsReplays() throws Exception {
        setupLocationConflict("obs-11");
        resolve("req-rr-2", "res-11", "obs-11", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 新 requestId、相同参数、相同 resolutionId：返回原结果，不生成新版本或第二张解决记录
        resolve("req-rr-3", "res-11", "obs-11", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.requestId").value("req-rr-2"));
        assertThat(versionCount("obs-11")).isEqualTo(3);
        assertThat(resolutionCount("obs-11")).isEqualTo(1);
        assertThat(requestLogCount("req-rr-2")).isEqualTo(1);
        assertThat(requestLogCount("req-rr-3")).isEqualTo(1);
    }

    @Test
    void resolutionIdReusedWithChangedParamsReturns409() throws Exception {
        setupLocationConflict("obs-12");
        resolve("req-ru-1", "res-12", "obs-12", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk());
        // 同一 resolutionId 改选择（改参）：409
        resolve("req-ru-2", "res-12", "obs-12", 1, 3,
                "站点C", "1.0", "备注", Map.of("location", "CURRENT"), "op")
                .andExpect(status().isConflict());
        // 同一 resolutionId 改候选值：409
        resolve("req-ru-3", "res-12", "obs-12", 1, 3,
                "站点D", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isConflict());
        // 同一 resolutionId 改操作者：409
        resolve("req-ru-4", "res-12", "obs-12", 1, 3,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "other")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-12")).isEqualTo(3);
        assertThat(resolutionCount("obs-12")).isEqualTo(1);
    }

    @Test
    void resolutionIdReusedOnAnotherObservationReturns409() throws Exception {
        setupLocationConflict("obs-13");
        resolve("req-ro-1", "res-shared", "obs-13", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk());
        create("req-ro-2", "obs-14", "站点A", "1.0", "备注").andExpect(status().isCreated());
        resolve("req-ro-3", "res-shared", "obs-14", 1, 1,
                "站点C", "1.0", "备注", Map.of(), "op")
                .andExpect(status().isConflict());
        assertThat(resolutionCount("obs-14")).isZero();
    }

    @Test
    void resolveRequestIdReusedWithDifferentParamsReturns409() throws Exception {
        setupLocationConflict("obs-15");
        resolve("req-ri-1", "res-15a", "obs-15", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk());
        resolve("req-ri-1", "res-15b", "obs-15", 1, 3,
                "站点C", "1.0", "备注", Map.of("location", "CURRENT"), "op")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-15")).isEqualTo(3);
        assertThat(resolutionCount("obs-15")).isEqualTo(1);
    }

    @Test
    void failedResolveDoesNotOccupyRequestIdOrResolutionId() throws Exception {
        setupLocationConflict("obs-16");
        // 业务校验失败（遗漏选择，400）：requestId 与 resolutionId 均不占键
        resolve("req-rf-1", "res-16", "obs-16", 1, 2,
                "站点C", "1.0", "备注", Map.of(), "op")
                .andExpect(status().isBadRequest());
        assertThat(requestLogCount("req-rf-1")).isZero();
        // 同一 requestId 与 resolutionId 换上合法参数后成功
        resolve("req-rf-1", "res-16", "obs-16", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        assertThat(requestLogCount("req-rf-1")).isEqualTo(1);
        assertThat(resolutionCount("obs-16")).isEqualTo(1);
    }

    // ---------- 读数冲突与历史不可变 ----------

    @Test
    void resolveReadingConflictComparedNumerically() throws Exception {
        create("req-rn2-0", "obs-17", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-rn2-1", "obs-17", 1, "站点A", "2.5", "备注").andExpect(status().isOk());
        // 离线端基于 v1 把读数改为 1.5：与服务端 2.5 冲突
        merge("req-rn2-2", "obs-17", 1, "站点A", "1.5", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"));
        resolve("req-rn2-3", "res-17", "obs-17", 1, 2,
                "站点A", "1.5", "备注", Map.of("reading", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("1.5"));

        // 候选读数 1.50 与当前 1.5 数值相等：两边改为相同值，无冲突自动接受，结果与当前相同不加版本
        resolve("req-rn2-4", "res-17b", "obs-17", 1, 3,
                "站点A", "1.50", "备注", Map.of(), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.reading").value("1.5"));
    }

    @Test
    void resolutionHistoryIsOrderedAndRecordsRemainImmutable() throws Exception {
        create("req-rh-0", "obs-18", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-rh-1", "obs-18", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 第一次解决：站点B/C 冲突选 CANDIDATE，备注自动合并为离线备注 → v3
        resolve("req-rh-2", "res-18a", "obs-18", 1, 2,
                "站点C", "1.0", "离线备注", Map.of("location", "CANDIDATE"), "op1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 服务端 v4 改地点为站点D；离线端基于 v3 改地点为站点E
        merge("req-rh-3", "obs-18", 3, "站点D", "1.0", "离线备注").andExpect(status().isOk());
        resolve("req-rh-4", "res-18b", "obs-18", 3, 4,
                "站点E", "1.0", "离线备注", Map.of("location", "CANDIDATE"), "op2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5));

        // 历史按解决时刻先后返回；每条记录不可变，始终指向解决时的版本
        mockMvc.perform(get("/api/observations/{id}/resolutions", "obs-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].resolutionId").value("res-18a"))
                .andExpect(jsonPath("$[0].version").value(3))
                .andExpect(jsonPath("$[0].location").value("站点C"))
                .andExpect(jsonPath("$[0].note").value("离线备注"))
                .andExpect(jsonPath("$[0].operator").value("op1"))
                .andExpect(jsonPath("$[1].resolutionId").value("res-18b"))
                .andExpect(jsonPath("$[1].version").value(5))
                .andExpect(jsonPath("$[1].location").value("站点E"));
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-18a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousVersion").value(2))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.location").value("站点C"));
        mockMvc.perform(get("/api/observations/{id}", "obs-18"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.location").value("站点E"));
    }

    @Test
    void getMissingResolutionReturns404AndEmptyHistoryForUnknownObservation() throws Exception {
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/resolutions", "obs-missing"))
                .andExpect(status().isNotFound());
    }
}
