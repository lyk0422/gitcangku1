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
 * 墓碑显式恢复 API 测试：恢复主流程、版本/代次隔离、失败分支回滚、requestId 幂等与恢复历史查询。
 * 使用真实 H2 内存库（MODE=MySQL）与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestoreApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-23T08:30:00Z");

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
        jdbcTemplate.update("DELETE FROM restore_history");
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

    private ResultActions getRestores(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/restores", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int restoreCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM restore_history WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 恢复主流程 ----------

    @Test
    void restoreCopiesSourceContentAndAdvancesVersionAndGeneration() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注1").andExpect(status().isCreated());
        merge("req-m1", "obs-1", 1, "站点B", "2.5", "备注2").andExpect(status().isOk());
        delete("req-d1", "obs-1", 2).andExpect(status().isOk());

        restore("req-r1", "obs-1", 3, 1, "误删，需要恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.reading").value("1.0"))
                .andExpect(jsonPath("$.note").value("备注1"));

        // 当前已是恢复后的活记录，版本不回退、代次加一
        getCurrent("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点A"));
        // 版本查询显示代次：恢复版本为代次 2，旧版本仍为代次 1
        getVersion("obs-1", 4)
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.location").value("站点A"));
        getVersion("obs-1", 2)
                .andExpect(jsonPath("$.generation").value(1));
        // 原墓碑快照与历史不被改写
        getVersion("obs-1", 3)
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.location").doesNotExist());
        assertThat(versionCount("obs-1")).isEqualTo(4);

        // 恢复历史记录前后版本、来源、原因与 UTC 时刻
        getRestores("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].previousVersion").value(3))
                .andExpect(jsonPath("$[0].newVersion").value(4))
                .andExpect(jsonPath("$[0].sourceVersion").value(1))
                .andExpect(jsonPath("$[0].previousGeneration").value(1))
                .andExpect(jsonPath("$[0].newGeneration").value(2))
                .andExpect(jsonPath("$[0].reason").value("误删，需要恢复"))
                .andExpect(jsonPath("$[0].requestId").value("req-r1"))
                .andExpect(jsonPath("$[0].restoredAtUtc").value("2026-09-23T08:30:00Z"));
        assertThat(restoreCount("obs-1")).isEqualTo(1);
    }

    @Test
    void restoreSourceMayBeAcrossGenerations() throws Exception {
        create("req-c2", "obs-2", "站点A", "1.0", "备注1").andExpect(status().isCreated());
        merge("req-m2", "obs-2", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        delete("req-d2", "obs-2", 2).andExpect(status().isOk());
        // 第一次恢复：来源代次 1 -> 代次 2，版本 4
        restore("req-r2", "obs-2", 3, 2, "第一次恢复").andExpect(status().isOk());
        // 恢复后继续写入代次 2 的新版本
        merge("req-m3", "obs-2", 4, "站点C", "3.0", "备注3").andExpect(status().isOk());
        delete("req-d3", "obs-2", 5).andExpect(status().isOk());
        // 第二次恢复：来源版本 4 属于代次 2（跨代次允许）-> 代次 3，墓碑版本 6 + 1 = 7
        restore("req-r3", "obs-2", 6, 4, "第二次恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.generation").value(3))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("2.0"))
                .andExpect(jsonPath("$.note").value("备注2"));
        getRestores("obs-2")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].previousGeneration").value(2))
                .andExpect(jsonPath("$[1].newGeneration").value(3));
    }

    // ---------- 失败分支 ----------

    @Test
    void restoreOnLiveObservationReturns409() throws Exception {
        create("req-c3", "obs-3", "站点A", "1.0", "备注").andExpect(status().isCreated());
        restore("req-r4", "obs-3", 1, 1, "还没删")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        assertThat(restoreCount("obs-3")).isZero();
        assertThat(versionCount("obs-3")).isEqualTo(1);
    }

    @Test
    void restoreWithMismatchedExpectedVersionReturns409() throws Exception {
        create("req-c4", "obs-4", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-d4", "obs-4", 1).andExpect(status().isOk());
        // 当前墓碑版本为 2：期望版本 7 不匹配，返回 409 与当前版本
        restore("req-r5", "obs-4", 7, 1, "版本不对")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("obs-4")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.generation").value(1));
        assertThat(restoreCount("obs-4")).isZero();
        assertThat(versionCount("obs-4")).isEqualTo(2);
    }

    @Test
    void restoreWithMissingSourceReturns404() throws Exception {
        create("req-c5", "obs-5", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-d5", "obs-5", 1).andExpect(status().isOk());
        restore("req-r6", "obs-5", 2, 9, "来源不存在")
                .andExpect(status().isNotFound());
        assertThat(restoreCount("obs-5")).isZero();
        assertThat(versionCount("obs-5")).isEqualTo(2);
    }

    @Test
    void restoreWithTombstoneSourceReturns422() throws Exception {
        create("req-c6", "obs-6", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-d6", "obs-6", 1).andExpect(status().isOk());
        restore("req-r7", "obs-6", 2, 2, "来源是墓碑")
                .andExpect(status().isUnprocessableEntity());
        getCurrent("obs-6")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.generation").value(1));
        assertThat(restoreCount("obs-6")).isZero();
        assertThat(versionCount("obs-6")).isEqualTo(2);
    }

    @Test
    void restoreWithBlankReasonReturns400() throws Exception {
        create("req-c7", "obs-7", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-d7", "obs-7", 1).andExpect(status().isOk());
        restore("req-r8", "obs-7", 2, 1, "   ")
                .andExpect(status().isBadRequest());
        assertThat(restoreCount("obs-7")).isZero();
    }

    @Test
    void restoreOnMissingObservationReturns404() throws Exception {
        restore("req-r9", "obs-x", 1, 1, "记录不存在")
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreHistoryOnMissingObservationReturns404() throws Exception {
        getRestores("obs-x").andExpect(status().isNotFound());
    }

    // ---------- 合并代次隔离 ----------

    @Test
    void mergeWithStaleGenerationBaseReturns409() throws Exception {
        create("req-g1", "obs-8", "站点A", "1.0", "备注1").andExpect(status().isCreated());
        merge("req-g2", "obs-8", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        delete("req-g3", "obs-8", 2).andExpect(status().isOk());
        // 恢复到版本 1 的内容：新版本 4，代次 2
        restore("req-g4", "obs-8", 3, 1, "恢复").andExpect(status().isOk());

        // 删除前基于版本 2（代次 1）的离线合并不得灌入恢复后的记录
        merge("req-g5", "obs-8", 2, "站点B", "2.0", "删除前离线备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(4));
        getCurrent("obs-8")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.note").value("备注1"));
        assertThat(versionCount("obs-8")).isEqualTo(4);

        // 客户端重新读取新基线（版本 4）后合并成功，版本前进到 5，代次仍为 2
        merge("req-g6", "obs-8", 4, "站点D", "1.0", "备注1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.location").value("站点D"));
    }

    @Test
    void resolveRejectsStaleGenerationBaseButAcceptsNewGenerationBase() throws Exception {
        create("req-s1", "obs-9", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-s2", "obs-9", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        delete("req-s3", "obs-9", 2).andExpect(status().isOk());
        // 恢复回版本 1 内容：版本 4、代次 2、地点站点A
        restore("req-s4", "obs-9", 3, 1, "恢复").andExpect(status().isOk());

        // 旧代次基线（版本 2）的 resolve 一律 409，即使 expectedCurrentVersion 正确
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("requestId", "req-s5");
        stale.put("resolutionId", "res-stale");
        stale.put("baseVersion", 2);
        stale.put("expectedCurrentVersion", 4);
        stale.put("location", "站点Z");
        stale.put("reading", "1.0");
        stale.put("note", "备注");
        stale.put("selections", Map.of("location", "CANDIDATE"));
        stale.put("operator", "op1");
        resolve("obs-9", stale).andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(4));

        // 制造当代次真实冲突：另一合并把当前推进到版本 5（地点站点C），离线端基于版本 4 改为站点D
        merge("req-s6", "obs-9", 4, "站点C", "1.0", "备注").andExpect(status().isOk());
        merge("req-s7", "obs-9", 4, "站点D", "1.0", "备注")
                .andExpect(status().isConflict());

        // 基于新基线（版本 4，代次 2）的显式解决成功，生成版本 6，代次仍为 2
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("requestId", "req-s8");
        fresh.put("resolutionId", "res-fresh");
        fresh.put("baseVersion", 4);
        fresh.put("expectedCurrentVersion", 5);
        fresh.put("location", "站点D");
        fresh.put("reading", "1.0");
        fresh.put("note", "备注");
        fresh.put("selections", Map.of("location", "CANDIDATE"));
        fresh.put("operator", "op1");
        resolve("obs-9", fresh)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.baseVersion").value(4))
                .andExpect(jsonPath("$.location").value("站点D"));
        getCurrent("obs-9")
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.generation").value(2));
    }

    @Test
    void normalMergeAndDeleteDoNotResurrectTombstone() throws Exception {
        create("req-t1", "obs-10", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-t2", "obs-10", 1).andExpect(status().isOk());
        merge("req-t3", "obs-10", 1, "站点B", "2.0", "备注")
                .andExpect(status().isGone());
        delete("req-t4", "obs-10", 2)
                .andExpect(status().isGone());
        getCurrent("obs-10")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.generation").value(1));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRestoreRequestIdReplaysOriginalResult() throws Exception {
        create("req-i1", "obs-11", "站点A", "1.0", "备注1").andExpect(status().isCreated());
        delete("req-i2", "obs-11", 1).andExpect(status().isOk());
        restore("req-i3", "obs-11", 2, 1, "恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 同键同参重放：返回首次恢复快照，不再生成新版本/历史
        restore("req-i3", "obs-11", 2, 1, "恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.generation").value(2));
        assertThat(versionCount("obs-11")).isEqualTo(3);
        assertThat(restoreCount("obs-11")).isEqualTo(1);
    }

    @Test
    void sameRestoreRequestIdWithDifferentParamsReturns409() throws Exception {
        create("req-i4", "obs-12", "站点A", "1.0", "备注1").andExpect(status().isCreated());
        merge("req-i5", "obs-12", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        delete("req-i6", "obs-12", 2).andExpect(status().isOk());
        restore("req-i7", "obs-12", 3, 1, "恢复").andExpect(status().isOk());
        // 同键改参（换来源版本）-> 409
        restore("req-i7", "obs-12", 3, 2, "恢复")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-12")).isEqualTo(4);
        assertThat(restoreCount("obs-12")).isEqualTo(1);
    }

    @Test
    void failedRestoreDoesNotOccupyRequestId() throws Exception {
        create("req-f1", "obs-13", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 记录尚活：恢复失败（409），不占键
        restore("req-f2", "obs-13", 1, 1, "提前恢复").andExpect(status().isConflict());
        delete("req-f3", "obs-13", 1).andExpect(status().isOk());
        // 同一 requestId 以合法参数重试成功
        restore("req-f2", "obs-13", 2, 1, "正式恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.generation").value(2));
        assertThat(restoreCount("obs-13")).isEqualTo(1);
    }

    @Test
    void replayRestoreAfterReDeleteReturnsOriginalWithoutRestoringAgain() throws Exception {
        create("req-p1", "obs-14", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-p2", "obs-14", 1).andExpect(status().isOk());
        // 第一次恢复：墓碑版本 2 -> 活版本 3，代次 2
        restore("req-p3", "obs-14", 2, 1, "恢复").andExpect(status().isOk());
        // 恢复后再次删除：版本 4 墓碑，代次不变（仍为 2）
        delete("req-p4", "obs-14", 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.deleted").value(true));
        // 重放原恢复请求：返回首次结果（版本 3），不再恢复，当前仍为版本 4 墓碑
        restore("req-p3", "obs-14", 2, 1, "恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(false));
        getCurrent("obs-14")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(restoreCount("obs-14")).isEqualTo(1);
        // 新的恢复请求按新墓碑版本恢复：版本 5，代次 3
        restore("req-p5", "obs-14", 4, 1, "再次恢复")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.generation").value(3));
        assertThat(restoreCount("obs-14")).isEqualTo(2);
    }

    @Test
    void oldSuccessfulMergeReplayKeepsOriginalSnapshotAcrossRestore() throws Exception {
        create("req-o1", "obs-15", "站点A", "1.0", "备注").andExpect(status().isCreated());
        // 删除前成功的合并：版本 2，站点B
        merge("req-o2", "obs-15", 1, "站点B", "2.0", "备注").andExpect(status().isOk());
        delete("req-o3", "obs-15", 2).andExpect(status().isOk());
        restore("req-o4", "obs-15", 3, 1, "恢复").andExpect(status().isOk());
        // 旧 merge requestId 同参重放：仍返回首次成功快照（版本 2），代次限制只作用于新操作
        merge("req-o2", "obs-15", 1, "站点B", "2.0", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        // 当前记录不被重放改变
        getCurrent("obs-15")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.location").value("站点A"));
        assertThat(versionCount("obs-15")).isEqualTo(4);
    }
}
