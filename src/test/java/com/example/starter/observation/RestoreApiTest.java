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
 * 墓碑显式恢复与合并代次隔离 API 测试（真实 H2 内存库，MySQL 兼容模式）：
 * 主流程、回滚、失败分支、代次隔离、跨代次来源、恢复历史与 requestId 幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestoreApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_recovery");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
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

    private ResultActions delete(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions restore(String requestId, String observationId, int expectedVersion,
                                  int sourceVersion, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("sourceVersion", sourceVersion);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/observations/{id}/restore", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String observationId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/versions/{version}", observationId, version));
    }

    private ResultActions getRecoveries(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/recoveries", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int recoveryCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_recovery WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    /**
     * 构造 create v1 -> merge v2 -> delete v3（墓碑）的记录，内容在 v1/v2 不同。
     */
    private void seedDeletedAtV3(String observationId) throws Exception {
        create("req-create", observationId, "站点A", "1.0", "备注A").andExpect(status().isCreated());
        merge("req-merge", observationId, 1, "站点B", "2.0", "备注B").andExpect(status().isOk());
        delete("req-delete", observationId, 2).andExpect(status().isOk());
    }

    // ---------- 恢复主流程 ----------

    @Test
    void restoreCopiesSourceContentAndAdvancesVersionAndGeneration() throws Exception {
        seedDeletedAtV3("obs-1");

        restore("req-restore", "obs-1", 3, 1, "误删，需要恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.reading").value("1.0"))
                .andExpect(jsonPath("$.note").value("备注A"));

        // 当前已复活，版本不回退，代次为 2
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点A"));
        // 新快照、原墓碑、原历史均保留且不改写
        getVersion("obs-1", 4)
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.deleted").value(false));
        getVersion("obs-1", 3)
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.location").doesNotExist());
        getVersion("obs-1", 1)
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.location").value("站点A"));
        assertThat(versionCount("obs-1")).isEqualTo(4);
    }

    @Test
    void restoreSourceCanBeAnyNonTombstoneHistoricalVersion() throws Exception {
        seedDeletedAtV3("obs-2");
        // 来源取 v2：恢复内容应为 v2 的内容
        restore("req-restore", "obs-2", 3, 2, "按合并后版本恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("2.0"))
                .andExpect(jsonPath("$.note").value("备注B"));
    }

    @Test
    void restoreSourceMayCrossGenerations() throws Exception {
        // v1(A) -> v2(B) -> 墓碑v3 -> 恢复(源v2) v4 gen2(B) -> merge v5(C) -> 墓碑v6 -> 恢复(源v4 属gen2) v7 gen3
        seedDeletedAtV3("obs-3");
        restore("req-r1", "obs-3", 3, 2, "第一次恢复").andExpect(status().isOk());
        merge("req-m-after", "obs-3", 4, "站点C", "3.0", "备注C").andExpect(status().isOk());
        delete("req-d2", "obs-3", 5).andExpect(status().isOk());
        restore("req-r2", "obs-3", 6, 4, "跨代次来源恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.generation").value(3))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("2.0"))
                .andExpect(jsonPath("$.note").value("备注B"));
        getCurrent("obs-3")
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.generation").value(3));
        assertThat(recoveryCount("obs-3")).isEqualTo(2);
    }

    // ---------- 恢复历史查询 ----------

    @Test
    void recoveryHistoryRecordsBeforeAfterSourceReasonAndUtcTime() throws Exception {
        seedDeletedAtV3("obs-4");
        restore("req-restore", "obs-4", 3, 1, "恢复原因X").andExpect(status().isOk());

        getRecoveries("obs-4")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].observationId").value("obs-4"))
                .andExpect(jsonPath("$[0].previousVersion").value(3))
                .andExpect(jsonPath("$[0].recoveredVersion").value(4))
                .andExpect(jsonPath("$[0].sourceVersion").value(1))
                .andExpect(jsonPath("$[0].generationBefore").value(1))
                .andExpect(jsonPath("$[0].generationAfter").value(2))
                .andExpect(jsonPath("$[0].reason").value("恢复原因X"))
                .andExpect(jsonPath("$[0].requestId").value("req-restore"))
                .andExpect(jsonPath("$[0].recoveredAtUtc").isNotEmpty());
    }

    @Test
    void recoveryHistoryQueryIsReadOnly() throws Exception {
        seedDeletedAtV3("obs-5");
        restore("req-restore", "obs-5", 3, 1, "原因").andExpect(status().isOk());
        getRecoveries("obs-5").andExpect(status().isOk());
        getRecoveries("obs-5").andExpect(status().isOk());
        // 纯查询不写入：恢复历史仍只有一条
        assertThat(recoveryCount("obs-5")).isEqualTo(1);
        // 没有恢复历史的存活记录返回空列表而非 404
        create("req-c", "obs-5b", "站点A", "1.0", "备注").andExpect(status().isCreated());
        getRecoveries("obs-5b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        // 不存在的观测记录返回 404
        getRecoveries("obs-missing").andExpect(status().isNotFound());
    }

    // ---------- 失败分支 ----------

    @Test
    void restoreOnLiveObservationReturns409WithCurrentVersion() throws Exception {
        create("req-c", "obs-6", "站点A", "1.0", "备注").andExpect(status().isCreated());
        restore("req-r", "obs-6", 1, 1, "未删除却恢复")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getCurrent("obs-6")
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false));
        assertThat(versionCount("obs-6")).isEqualTo(1);
        assertThat(recoveryCount("obs-6")).isZero();
    }

    @Test
    void restoreWithMismatchedExpectedVersionReturns409() throws Exception {
        seedDeletedAtV3("obs-7");
        restore("req-r", "obs-7", 2, 1, "期望版本错误")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(3));
        getCurrent("obs-7").andExpect(jsonPath("$.deleted").value(true));
        assertThat(versionCount("obs-7")).isEqualTo(3);
        assertThat(recoveryCount("obs-7")).isZero();
    }

    @Test
    void restoreWithMissingSourceReturns404() throws Exception {
        seedDeletedAtV3("obs-8");
        restore("req-r", "obs-8", 3, 99, "来源不存在")
                .andExpect(status().isNotFound());
        getCurrent("obs-8").andExpect(jsonPath("$.deleted").value(true));
        assertThat(recoveryCount("obs-8")).isZero();
    }

    @Test
    void restoreWithTombstoneSourceReturns422AndRollsBack() throws Exception {
        seedDeletedAtV3("obs-9");
        restore("req-r-bad", "obs-9", 3, 3, "来源是墓碑")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.currentVersion").value(3));
        // 回滚：仍是原墓碑，无新版本，无恢复历史
        getCurrent("obs-9")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(versionCount("obs-9")).isEqualTo(3);
        assertThat(recoveryCount("obs-9")).isZero();
    }

    @Test
    void restoreOnMissingObservationReturns404() throws Exception {
        restore("req-r", "obs-x", 1, 1, "记录不存在")
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreWithBlankReasonReturns400() throws Exception {
        seedDeletedAtV3("obs-10");
        restore("req-r", "obs-10", 3, 1, "   ")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-10")).isEqualTo(3);
        assertThat(recoveryCount("obs-10")).isZero();
    }

    // ---------- 合并代次隔离 ----------

    @Test
    void mergeWithStaleGenerationBaseReturns409AndClientMustReread() throws Exception {
        seedDeletedAtV3("obs-11");
        restore("req-r", "obs-11", 3, 1, "恢复").andExpect(status().isOk());

        // 删除前的离线修改基于旧代次基线 v1：不得灌入恢复后的记录
        merge("req-m-stale", "obs-11", 1, "站点X", "9.0", "离线备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(4));
        // 同样拒绝旧代次的 v2 基线
        merge("req-m-stale2", "obs-11", 2, "站点X", "9.0", "离线备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(4));
        getCurrent("obs-11")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.note").value("备注A"));

        // 客户端重新读取新基线 v4 后合并成功，新版本属于代次 2
        merge("req-m-new", "obs-11", 4, "站点A", "1.0", "新代次备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.note").value("新代次备注"));
        getVersion("obs-11", 5).andExpect(jsonPath("$.generation").value(2));
    }

    @Test
    void resolveWithStaleGenerationBaseReturns409() throws Exception {
        seedDeletedAtV3("obs-12");
        restore("req-r", "obs-12", 3, 1, "恢复").andExpect(status().isOk());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-resolve-stale");
        body.put("resolutionId", "res-stale");
        body.put("baseVersion", 1);
        body.put("expectedCurrentVersion", 4);
        body.put("location", "站点X");
        body.put("reading", "9.0");
        body.put("note", "离线备注");
        body.put("selections", Map.of());
        body.put("operator", "op-1");
        resolve("obs-12", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(4));
        getCurrent("obs-12")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.location").value("站点A"));
    }

    @Test
    void normalMergeDeleteResolveDoNotResurrectTombstone() throws Exception {
        seedDeletedAtV3("obs-13");
        // 普通 merge/delete/resolve 在墓碑上仍被拒绝，不隐式复活
        merge("req-m", "obs-13", 1, "站点X", "9.0", "备注")
                .andExpect(status().isGone());
        delete("req-d", "obs-13", 3).andExpect(status().isGone());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-res");
        body.put("resolutionId", "res-1");
        body.put("baseVersion", 1);
        body.put("expectedCurrentVersion", 3);
        body.put("location", "站点X");
        body.put("reading", "9.0");
        body.put("note", "备注");
        body.put("selections", Map.of());
        body.put("operator", "op-1");
        resolve("obs-13", body).andExpect(status().isGone());
        getCurrent("obs-13")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(3));
    }

    // ---------- 恢复的幂等 ----------

    @Test
    void restoreSameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        seedDeletedAtV3("obs-14");
        restore("req-rr", "obs-14", 3, 1, "恢复").andExpect(status().isOk());
        restore("req-rr", "obs-14", 3, 1, "恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2));
        assertThat(versionCount("obs-14")).isEqualTo(4);
        assertThat(recoveryCount("obs-14")).isEqualTo(1);
    }

    @Test
    void restoreSameRequestIdDifferentParamsReturns409() throws Exception {
        seedDeletedAtV3("obs-15");
        restore("req-rd", "obs-15", 3, 1, "恢复").andExpect(status().isOk());
        // 已成功后改参（来源版本变化）→ 409
        restore("req-rd", "obs-15", 3, 2, "改成另一个来源")
                .andExpect(status().isConflict());
        getCurrent("obs-15")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.location").value("站点A"));
    }

    @Test
    void failedRestoreDoesNotOccupyRequestId() throws Exception {
        seedDeletedAtV3("obs-16");
        // 来源为墓碑：422 失败，回滚不占键
        restore("req-reuse", "obs-16", 3, 3, "先失败").andExpect(status().isUnprocessableEntity());
        // 同一 requestId 换合法参数后成功恢复
        restore("req-reuse", "obs-16", 3, 1, "后成功")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        assertThat(recoveryCount("obs-16")).isEqualTo(1);
    }

    @Test
    void replayRestoreAfterAnotherDeleteReturnsOriginalResultWithoutRestoringAgain() throws Exception {
        seedDeletedAtV3("obs-17");
        restore("req-r", "obs-17", 3, 1, "恢复").andExpect(status().isOk());
        // 恢复后再次删除
        delete("req-d2", "obs-17", 4).andExpect(status().isOk());
        getCurrent("obs-17")
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.generation").value(2));
        // 重放原恢复请求：返回首次成功结果（v4），不再次恢复
        restore("req-r", "obs-17", 3, 1, "恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.deleted").value(false));
        getCurrent("obs-17")
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(versionCount("obs-17")).isEqualTo(5);
        assertThat(recoveryCount("obs-17")).isEqualTo(1);
    }

    @Test
    void oldSuccessfulMergeReplayKeepsOriginalSemanticsAcrossGeneration() throws Exception {
        seedDeletedAtV3("obs-18");
        // 旧 merge 已成功（v2）；恢复代次推进后，同键同参重放仍返回原结果，代次限制只作用于新操作
        restore("req-r", "obs-18", 3, 1, "恢复").andExpect(status().isOk());
        merge("req-merge", "obs-18", 1, "站点B", "2.0", "备注B")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.generation").value(1));
    }
}
