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
 * 按时刻一致视图与冻结快照 API 测试：历史时刻定位、墓碑语义、最近解决记录、
 * 快照固化与不可变、404/400 失败分支与幂等边界（真实 H2 内存库，固定 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsOfSnapshotApiTest {

    private static final Instant T1 = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-22T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-09-22T10:10:00Z");
    private static final Instant T4 = Instant.parse("2026-09-22T10:15:00Z");
    private static final Instant T5 = Instant.parse("2026-09-22T10:20:00Z");

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
        jdbcTemplate.update("DELETE FROM observation_snapshot_item");
        jdbcTemplate.update("DELETE FROM observation_snapshot");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version_commit");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(T5);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void at(Instant now) {
        Mockito.when(clock.instant()).thenReturn(now);
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

    private ResultActions resolve(String requestId, String observationId, String resolutionId,
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

    private ResultActions asOf(Instant asOfUtc, List<String> observationIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asOfUtc", asOfUtc.toString());
        body.put("observationIds", observationIds);
        return mockMvc.perform(post("/api/observations/asof")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createSnapshot(String requestId, String snapshotKey,
                                         Instant targetTimeUtc, List<String> observationIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("snapshotKey", snapshotKey);
        body.put("targetTimeUtc", targetTimeUtc.toString());
        body.put("observationIds", observationIds);
        return mockMvc.perform(post("/api/observations/snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getSnapshot(String snapshotKey) throws Exception {
        return mockMvc.perform(get("/api/observations/snapshots/{key}", snapshotKey));
    }

    private long tableCount(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    // ---------- 按时刻查询：历史定位 ----------

    @Test
    void asOfLocatesLastVersionAtEachHistoricalTime() throws Exception {
        at(T1);
        create("req-a1", "obs-a", "站点A", "1.0", "备注1").andExpect(status().isCreated());
        at(T2);
        merge("req-a2", "obs-a", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        at(T3);
        merge("req-a3", "obs-a", 2, "站点C", "3.0", "备注3").andExpect(status().isOk());

        // 写入全部完成后，服务端当前时刻前进到 T5，以下按历史时刻只读查询
        at(T5);

        // 该时刻尚未创建：ABSENT，不返回 version 与业务字段，不视为 404
        asOf(T1.minusSeconds(1), List.of("obs-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfUtc").value(T1.minusSeconds(1).toString()))
                .andExpect(jsonPath("$.entries[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.entries[0].state").value("ABSENT"))
                .andExpect(jsonPath("$.entries[0].version").doesNotExist())
                .andExpect(jsonPath("$.entries[0].location").doesNotExist());

        // 恰好等于提交时刻：该版本必须包含（committed_at <= 目标时刻）
        asOf(T1, List.of("obs-a"))
                .andExpect(jsonPath("$.entries[0].state").value("PRESENT"))
                .andExpect(jsonPath("$.entries[0].version").value(1))
                .andExpect(jsonPath("$.entries[0].location").value("站点A"));

        // 两个版本之间：仍是旧版本
        asOf(T2.minusMillis(1), List.of("obs-a"))
                .andExpect(jsonPath("$.entries[0].version").value(1))
                .andExpect(jsonPath("$.entries[0].location").value("站点A"));

        asOf(T2, List.of("obs-a"))
                .andExpect(jsonPath("$.entries[0].version").value(2))
                .andExpect(jsonPath("$.entries[0].reading").value("2.0"));

        asOf(T3, List.of("obs-a"))
                .andExpect(jsonPath("$.entries[0].version").value(3))
                .andExpect(jsonPath("$.entries[0].note").value("备注3"));

        asOf(T4, List.of("obs-a"))
                .andExpect(jsonPath("$.entries[0].version").value(3));
    }

    @Test
    void asOfReturnsEntriesSortedAndDeduplicated() throws Exception {
        at(T1);
        create("req-b1", "obs-b", "站点B", "1.0", "备注").andExpect(status().isCreated());
        create("req-c1", "obs-c", "站点C", "1.0", "备注").andExpect(status().isCreated());

        at(T5);
        asOf(T2, List.of("obs-c", "obs-b", "obs-b", "obs-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].observationId").value("obs-b"))
                .andExpect(jsonPath("$.entries[0].state").value("PRESENT"))
                .andExpect(jsonPath("$.entries[1].observationId").value("obs-c"))
                .andExpect(jsonPath("$.entries[2].observationId").value("obs-x"))
                .andExpect(jsonPath("$.entries[2].state").value("ABSENT"));
    }

    // ---------- 墓碑语义 ----------

    @Test
    void asOfTombstoneTakesEffectAtDeleteCommitTime() throws Exception {
        at(T1);
        create("req-d1", "obs-d", "站点A", "1.0", "备注").andExpect(status().isCreated());
        at(T2);
        merge("req-d2", "obs-d", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        at(T3);
        delete("req-d3", "obs-d", 2).andExpect(status().isOk());
        at(T5);

        // 删除前：返回删除前版本内容
        asOf(T3.minusMillis(1), List.of("obs-d"))
                .andExpect(jsonPath("$.entries[0].state").value("PRESENT"))
                .andExpect(jsonPath("$.entries[0].version").value(2))
                .andExpect(jsonPath("$.entries[0].location").value("站点B"));

        // 删除提交时刻及之后：DELETED，不暴露任何业务字段
        asOf(T3, List.of("obs-d"))
                .andExpect(jsonPath("$.entries[0].state").value("DELETED"))
                .andExpect(jsonPath("$.entries[0].version").value(3))
                .andExpect(jsonPath("$.entries[0].location").doesNotExist())
                .andExpect(jsonPath("$.entries[0].reading").doesNotExist())
                .andExpect(jsonPath("$.entries[0].note").doesNotExist());

        asOf(T4, List.of("obs-d"))
                .andExpect(jsonPath("$.entries[0].state").value("DELETED"))
                .andExpect(jsonPath("$.entries[0].version").value(3));

        // 更早的历史版本内容仍可按版本接口读取，但删除期间的按时刻视图不暴露它们
        getVersionViaApi("obs-d", 2)
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.deleted").value(false));
    }

    private ResultActions getVersionViaApi(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/versions/{v}", observationId, version));
    }

    @Test
    void asOfDuringDeletionDoesNotExposeLaterRestoredVersion() throws Exception {
        at(T1);
        create("req-e1", "obs-e", "站点A", "1.0", "备注").andExpect(status().isCreated());
        at(T2);
        merge("req-e2", "obs-e", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        at(T3);
        delete("req-e3", "obs-e", 2).andExpect(status().isOk());
        at(T5);

        // 模拟“删除提交之后”出现的恢复版本（当前系统无恢复写接口，直接以同构数据行构造 v4 正常版本）：
        // 删除期间（T3 ～ T4）的视图绝不能取到该更晚版本。
        jdbcTemplate.update(
                "INSERT INTO observation_version (observation_id, version, location, reading, note, deleted, created_at) "
                        + "VALUES ('obs-e', 4, '站点恢复', '9.0', '恢复备注', FALSE, CURRENT_TIMESTAMP)");
        jdbcTemplate.update(
                "INSERT INTO observation_version_commit (observation_id, version, committed_at_utc) "
                        + "VALUES ('obs-e', 4, ?)",
                java.sql.Timestamp.from(T5));

        asOf(T4, List.of("obs-e"))
                .andExpect(jsonPath("$.entries[0].state").value("DELETED"))
                .andExpect(jsonPath("$.entries[0].version").value(3))
                .andExpect(jsonPath("$.entries[0].location").doesNotExist());

        // 恢复版本提交之后，按时刻视图才进入恢复后的正常版本
        asOf(T5, List.of("obs-e"))
                .andExpect(jsonPath("$.entries[0].state").value("PRESENT"))
                .andExpect(jsonPath("$.entries[0].version").value(4))
                .andExpect(jsonPath("$.entries[0].location").value("站点恢复"));
    }

    // ---------- 最近冲突解决记录 ----------

    @Test
    void asOfReturnsLatestResolutionIdAsOfTime() throws Exception {
        at(T1);
        create("req-f1", "obs-f", "站点A", "1.0", "备注").andExpect(status().isCreated());
        at(T2);
        merge("req-f2", "obs-f", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 基于 v1 的候选把地点改为站点C，与服务端站点B冲突后人工选择 CANDIDATE
        at(T3);
        resolve("req-f3", "obs-f", "res-1", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op1")
                .andExpect(status().isOk());

        at(T5);
        asOf(T3.minusMillis(1), List.of("obs-f"))
                .andExpect(jsonPath("$.entries[0].lastResolutionId").doesNotExist());
        asOf(T3, List.of("obs-f"))
                .andExpect(jsonPath("$.entries[0].version").value(3))
                .andExpect(jsonPath("$.entries[0].lastResolutionId").value("res-1"));
        asOf(T4, List.of("obs-f"))
                .andExpect(jsonPath("$.entries[0].lastResolutionId").value("res-1"));
    }

    // ---------- 失败分支与只读 ----------

    @Test
    void asOfWithFutureTimeReturns400() throws Exception {
        at(T1);
        create("req-g1", "obs-g", "站点A", "1.0", "备注").andExpect(status().isCreated());
        asOf(T1.plusSeconds(1), List.of("obs-g")).andExpect(status().isBadRequest());
    }

    @Test
    void asOfWithEmptyOrOversizedIdSetReturns400() throws Exception {
        asOf(T5, List.of()).andExpect(status().isBadRequest());
        List<String> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "obs-" + i).toList();
        asOf(T5, tooMany).andExpect(status().isBadRequest());
    }

    @Test
    void asOfIsReadOnlyAndAdvancesNothing() throws Exception {
        at(T1);
        create("req-h1", "obs-h", "站点A", "1.0", "备注").andExpect(status().isCreated());
        at(T3);
        resolve("req-h2", "obs-h", "res-h", 1, 1,
                "站点A", "1.0", "备注", Map.of(), "op1").andExpect(status().isOk());

        long versionsBefore = tableCount("observation_version");
        long commitsBefore = tableCount("observation_version_commit");
        long resolutionsBefore = tableCount("conflict_resolution");
        long requestLogsBefore = tableCount("request_log");

        asOf(T2, List.of("obs-h", "obs-missing")).andExpect(status().isOk());

        assertThat(tableCount("observation_version")).isEqualTo(versionsBefore);
        assertThat(tableCount("observation_version_commit")).isEqualTo(commitsBefore);
        assertThat(tableCount("conflict_resolution")).isEqualTo(resolutionsBefore);
        assertThat(tableCount("request_log")).isEqualTo(requestLogsBefore);
    }

    // ---------- 冻结快照 ----------

    @Test
    void snapshotFreezesConsistentAsOfStateSortedById() throws Exception {
        at(T1);
        create("req-i1", "obs-i", "站点I", "1.0", "备注I").andExpect(status().isCreated());
        create("req-j1", "obs-j", "站点J", "1.0", "备注J").andExpect(status().isCreated());
        at(T2);
        merge("req-i2", "obs-i", 1, "站点I2", "2.0", "备注I2").andExpect(status().isOk());
        at(T3);
        delete("req-j2", "obs-j", 1).andExpect(status().isOk());
        // obs-k 在 T4 才创建：T2.5 时刻尚不存在，应固化为 ABSENT
        at(T4);
        create("req-k1", "obs-k", "站点K", "1.0", "备注K").andExpect(status().isCreated());

        Instant target = Instant.parse("2026-09-22T10:07:30Z");
        at(T5);
        // 乱序 + 重复 ID，响应必须按 observationId 升序去重
        createSnapshot("req-s1", "snap-1", target, List.of("obs-k", "obs-j", "obs-i", "obs-i"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotKey").value("snap-1"))
                .andExpect(jsonPath("$.targetTimeUtc").value(target.toString()))
                .andExpect(jsonPath("$.globalLatestVersion").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-i"))
                .andExpect(jsonPath("$.items[0].state").value("PRESENT"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].location").value("站点I2"))
                // obs-j 的墓碑在 T3 才提交，晚于目标时刻：此时仍是 v1 正常版本
                .andExpect(jsonPath("$.items[1].observationId").value("obs-j"))
                .andExpect(jsonPath("$.items[1].state").value("PRESENT"))
                .andExpect(jsonPath("$.items[1].version").value(1))
                .andExpect(jsonPath("$.items[1].location").value("站点J"))
                .andExpect(jsonPath("$.items[2].observationId").value("obs-k"))
                .andExpect(jsonPath("$.items[2].state").value("ABSENT"))
                .andExpect(jsonPath("$.items[2].version").doesNotExist());

        // 全局最新版本：目标时刻已提交版本总数 = obs-i v1/v2 + obs-j v1 = 3
        assertThat(tableCount("observation_snapshot")).isEqualTo(1);
        assertThat(tableCount("observation_snapshot_item")).isEqualTo(3);

        getSnapshot("snap-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.globalLatestVersion").value(3))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[2].state").value("ABSENT"));
    }

    @Test
    void snapshotWithNeverExistingIdReturns404AndSavesNothing() throws Exception {
        at(T1);
        create("req-m1", "obs-m", "站点A", "1.0", "备注").andExpect(status().isCreated());

        at(T5);
        createSnapshot("req-s2", "snap-2", T2, List.of("obs-m", "obs-never"))
                .andExpect(status().isNotFound());
        assertThat(tableCount("observation_snapshot")).isZero();
        assertThat(tableCount("observation_snapshot_item")).isZero();

        // 失败不占键：同一 requestId 与 snapshotKey 修正参数后可成功使用
        createSnapshot("req-s2", "snap-2", T2, List.of("obs-m"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void snapshotFutureTargetReturns400AndSavesNothing() throws Exception {
        at(T1);
        create("req-n1", "obs-n", "站点A", "1.0", "备注").andExpect(status().isCreated());
        createSnapshot("req-s3", "snap-3", T2, List.of("obs-n"))
                .andExpect(status().isBadRequest());
        assertThat(tableCount("observation_snapshot")).isZero();
    }

    @Test
    void snapshotRemainsImmutableAfterMergeResolveAndDelete() throws Exception {
        at(T1);
        create("req-o1", "obs-o", "站点A", "1.0", "备注").andExpect(status().isCreated());
        at(T2);
        createSnapshot("req-s4", "snap-4", T2, List.of("obs-o"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.globalLatestVersion").value(1))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].location").value("站点A"));

        // 快照之后的合并、冲突解决与删除都不得改写它
        at(T3);
        merge("req-o2", "obs-o", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        at(T4);
        resolve("req-o3", "obs-o", "res-o", 1, 2,
                "站点C", "2.0", "备注2", Map.of("location", "CANDIDATE"), "op1")
                .andExpect(status().isOk());
        at(T5);
        delete("req-o4", "obs-o", 3).andExpect(status().isOk());

        getSnapshot("snap-4")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTimeUtc").value(T2.toString()))
                .andExpect(jsonPath("$.globalLatestVersion").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].state").value("PRESENT"))
                .andExpect(jsonPath("$.items[0].location").value("站点A"))
                .andExpect(jsonPath("$.items[0].lastResolutionId").doesNotExist());

        // 同一时刻可存在多个独立不可变慢照（不同键），各自固化后互不影响
        at(T5);
        createSnapshot("req-s5", "snap-5", T2, List.of("obs-o"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].version").value(1));
        assertThat(tableCount("observation_snapshot")).isEqualTo(2);
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysSnapshotAndReorderingIsSameParams() throws Exception {
        at(T1);
        create("req-p1", "obs-p", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-q1", "obs-q", "站点B", "1.0", "备注").andExpect(status().isCreated());

        at(T5);
        createSnapshot("req-s6", "snap-6", T2, List.of("obs-p", "obs-q"))
                .andExpect(status().isCreated());
        // 同键同参重放：返回首次快照，不新增任何行
        createSnapshot("req-s6", "snap-6", T2, List.of("obs-p", "obs-q"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotKey").value("snap-6"))
                .andExpect(jsonPath("$.items.length()").value(2));
        // ID 集合换序（含去重）视为同参
        createSnapshot("req-s6", "snap-6", T2, List.of("obs-q", "obs-p", "obs-p"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].observationId").value("obs-p"));

        assertThat(tableCount("observation_snapshot")).isEqualTo(1);
        assertThat(tableCount("observation_snapshot_item")).isEqualTo(2);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        at(T1);
        create("req-r1", "obs-r", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-s0", "obs-s", "站点B", "1.0", "备注").andExpect(status().isCreated());

        at(T5);
        createSnapshot("req-s7", "snap-7", T2, List.of("obs-r")).andExpect(status().isCreated());

        // 不同目标时刻
        createSnapshot("req-s7", "snap-7", T3, List.of("obs-r")).andExpect(status().isConflict());
        // 不同 ID 集合
        createSnapshot("req-s7", "snap-7", T2, List.of("obs-r", "obs-s")).andExpect(status().isConflict());
        // 不同 snapshotKey
        createSnapshot("req-s7", "snap-other", T2, List.of("obs-r")).andExpect(status().isConflict());
        // 跨 requestId 复用 snapshotKey
        createSnapshot("req-s8", "snap-7", T2, List.of("obs-r")).andExpect(status().isConflict());

        assertThat(tableCount("observation_snapshot")).isEqualTo(1);
        assertThat(tableCount("observation_snapshot_item")).isEqualTo(1);

        // 原快照读取不受影响
        getSnapshot("snap-7")
                .andExpect(jsonPath("$.items[0].observationId").value("obs-r"));
    }

    @Test
    void getUnknownSnapshotReturns404() throws Exception {
        getSnapshot("snap-missing").andExpect(status().isNotFound());
    }
}
