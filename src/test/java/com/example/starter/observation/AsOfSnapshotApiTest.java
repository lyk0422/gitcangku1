package com.example.starter.observation;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按时刻一致视图与冻结快照 API 测试：历史时刻定位、ABSENT、墓碑生效时刻、最近冲突解决标识、
 * 快照不可变、整次 404 不保存部分快照、同键同参重放/集合换序/异参 409、失败不占键。
 * 真实 H2 内存库（MODE=MySQL）与可控 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsOfSnapshotApiTest {

    private static final Instant T0 = Instant.parse("2026-09-20T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_snapshot_item");
        jdbcTemplate.update("DELETE FROM observation_snapshot");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        jdbcTemplate.update("UPDATE global_revision SET revision = 0 WHERE id = 1");
        now.set(T0);
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void tick(String isoUtc) {
        now.set(Instant.parse(isoUtc));
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

    private ResultActions asOf(String isoUtc, List<String> observationIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("asOfUtc", isoUtc);
        body.put("observationIds", observationIds);
        return mockMvc.perform(post("/api/observations/as-of")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createSnapshot(String requestId, String snapshotKey,
                                         String isoUtc, List<String> observationIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("snapshotKey", snapshotKey);
        body.put("targetTimeUtc", isoUtc);
        body.put("observationIds", observationIds);
        return mockMvc.perform(post("/api/observations/snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getSnapshot(String snapshotKey) throws Exception {
        return mockMvc.perform(get("/api/observations/snapshots/{key}", snapshotKey));
    }

    private JsonNode readJson(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private int tableCount(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    // ---------- 按时刻查询：历史定位与 ABSENT ----------

    @Test
    void asOfLocatesLastVersionAtHistoricalInstants() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");
        merge("req-m", "obs-1", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        tick("2026-09-20T03:00:00Z");

        // 时刻在创建之前：ABSENT，不是 404
        asOf("2026-09-19T00:00:00Z", List.of("obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfUtc").value("2026-09-19T00:00:00Z"))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.items[0].state").value("ABSENT"))
                .andExpect(jsonPath("$.items[0].version").doesNotExist())
                .andExpect(jsonPath("$.items[0].deleted").value(false))
                .andExpect(jsonPath("$.items[0].location").doesNotExist());

        // 时刻在 v1 与 v2 之间：定位 v1
        asOf("2026-09-20T00:30:00Z", List.of("obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].location").value("站点A"))
                .andExpect(jsonPath("$.items[0].reading").value("1.0"));

        // 时刻在 v2 之后：定位 v2
        asOf("2026-09-20T02:00:00Z", List.of("obs-1"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].location").value("站点B"))
                .andExpect(jsonPath("$.items[0].reading").value("2.0"));

        // 从未存在的记录按 ABSENT 返回，不视为 404
        asOf("2026-09-20T02:00:00Z", List.of("obs-never"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].state").value("ABSENT"));
    }

    @Test
    void asOfFutureInstantReturns400() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T00:00:00Z");
        asOf("2026-09-20T00:00:01Z", List.of("obs-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void asOfValidatesIdSetSize() throws Exception {
        asOf("2026-09-20T00:00:00Z", List.of())
                .andExpect(status().isBadRequest());
        java.util.List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("obs-" + i);
        }
        asOf("2026-09-20T00:00:00Z", tooMany)
                .andExpect(status().isBadRequest());
    }

    @Test
    void asOfReturnsItemsSortedByIdAndDeduplicates() throws Exception {
        create("req-c1", "obs-c", "站点C", "1.0", "备注").andExpect(status().isCreated());
        create("req-c2", "obs-a", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-c3", "obs-b", "站点B", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");

        JsonNode body = readJson(asOf("2026-09-20T00:30:00Z",
                List.of("obs-c", "obs-a", "obs-a", "obs-b", "obs-c")));
        assertThat(body.get("items")).hasSize(3);
        assertThat(body.get("items").get(0).get("observationId").asText()).isEqualTo("obs-a");
        assertThat(body.get("items").get(1).get("observationId").asText()).isEqualTo("obs-b");
        assertThat(body.get("items").get(2).get("observationId").asText()).isEqualTo("obs-c");
    }

    @Test
    void asOfIsReadOnlyAndDoesNotAdvanceVersions() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");
        long revisionBefore = jdbcTemplate.queryForObject(
                "SELECT revision FROM global_revision WHERE id = 1", Long.class);

        asOf("2026-09-20T00:30:00Z", List.of("obs-1", "obs-x")).andExpect(status().isOk());
        asOf("2026-09-20T00:30:00Z", List.of("obs-1")).andExpect(status().isOk());

        long revisionAfter = jdbcTemplate.queryForObject(
                "SELECT revision FROM global_revision WHERE id = 1", Long.class);
        assertThat(revisionAfter).isEqualTo(revisionBefore);
        assertThat(tableCount("observation_version", "observation_id", "obs-1")).isEqualTo(1);
        assertThat(tableCount("conflict_resolution", "observation_id", "obs-1")).isZero();
    }

    // ---------- 墓碑时刻语义 ----------

    @Test
    void asOfTombstoneTakesEffectAtDeleteCommitInstant() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");
        merge("req-m", "obs-1", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());
        tick("2026-09-20T02:00:00Z");
        delete("req-d", "obs-1", 2).andExpect(status().isOk());
        tick("2026-09-20T03:00:00Z");

        // 删除前一刻：仍可见 v2 业务内容
        asOf("2026-09-20T01:30:00Z", List.of("obs-1"))
                .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].location").value("站点B"));

        // 删除提交时刻及之后：TOMBSTONE，只返回状态与版本，不暴露内容
        asOf("2026-09-20T02:00:00Z", List.of("obs-1"))
                .andExpect(jsonPath("$.items[0].state").value("TOMBSTONE"))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].deleted").value(true))
                .andExpect(jsonPath("$.items[0].location").doesNotExist())
                .andExpect(jsonPath("$.items[0].reading").doesNotExist())
                .andExpect(jsonPath("$.items[0].note").doesNotExist());

        // 删除期间再合并被拒，不可能出现恢复版本
        merge("req-m2", "obs-1", 2, "站点D", "3.0", "恢复备注")
                .andExpect(status().isGone());
        asOf("2026-09-20T02:30:00Z", List.of("obs-1"))
                .andExpect(jsonPath("$.items[0].state").value("TOMBSTONE"))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].location").doesNotExist());
    }

    // ---------- 最近冲突解决标识 ----------

    @Test
    void asOfReturnsLatestResolutionIdAtOrBeforeInstant() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-server", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-offline", "obs-1", 1, "站点C", "1.0", "备注").andExpect(status().isConflict());
        tick("2026-09-20T03:00:00Z");
        resolve("req-r1", "res-1", "obs-1", 1, 2,
                "站点C", "1.0", "备注", Map.of("location", "CANDIDATE"), "op-1")
                .andExpect(status().isOk());
        tick("2026-09-20T05:00:00Z");
        merge("req-after", "obs-1", 3, "站点D", "1.0", "备注").andExpect(status().isOk());
        tick("2026-09-20T06:00:00Z");
        resolve("req-r2", "res-2", "obs-1", 3, 4,
                "站点E", "1.0", "备注", Map.of("location", "CANDIDATE"), "op-2")
                .andExpect(status().isOk());
        tick("2026-09-20T08:00:00Z");

        // 解决之前：无标识
        asOf("2026-09-20T02:00:00Z", List.of("obs-1"))
                .andExpect(jsonPath("$.items[0].lastResolutionId").doesNotExist());
        // 第一次解决之后、第二次之前：指向 res-1
        asOf("2026-09-20T04:00:00Z", List.of("obs-1"))
                .andExpect(jsonPath("$.items[0].lastResolutionId").value("res-1"));
        // 第二次解决之后：指向最近的 res-2
        asOf("2026-09-20T07:00:00Z", List.of("obs-1"))
                .andExpect(jsonPath("$.items[0].lastResolutionId").value("res-2"));
    }

    // ---------- 冻结快照主流程 ----------

    @Test
    void createSnapshotFreezesPerItemContentAndGlobalRevision() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");
        create("req-c2", "obs-2", "地点X", "9.5", "备注X").andExpect(status().isCreated());
        tick("2026-09-20T02:00:00Z");

        createSnapshot("req-s1", "snap-1", "2026-09-20T01:30:00Z", List.of("obs-2", "obs-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotKey").value("snap-1"))
                .andExpect(jsonPath("$.targetTimeUtc").value("2026-09-20T01:30:00Z"))
                .andExpect(jsonPath("$.globalLatestVersion").value(2))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].location").value("站点A"))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-2"))
                .andExpect(jsonPath("$.items[1].version").value(1));

        JsonNode firstRead = readJson(getSnapshot("snap-1").andExpect(status().isOk()));

        // 快照之后的合并、解决与删除均不得改写快照
        tick("2026-09-20T03:00:00Z");
        merge("req-m1", "obs-1", 1, "站点Z", "3.0", "新备注").andExpect(status().isOk());
        tick("2026-09-20T04:00:00Z");
        delete("req-d1", "obs-2", 1).andExpect(status().isOk());

        JsonNode secondRead = readJson(getSnapshot("snap-1").andExpect(status().isOk()));
        assertThat(secondRead).isEqualTo(firstRead);
        assertThat(secondRead.get("globalLatestVersion").asLong()).isEqualTo(2L);
        assertThat(secondRead.get("items").get(0).get("location").asText()).isEqualTo("站点A");
        assertThat(secondRead.get("items").get(1).get("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void snapshotCreatedBeforeAnObservationExistedMarksItemAbsent() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T02:00:00Z");
        create("req-c2", "obs-2", "地点X", "9.5", "备注X").andExpect(status().isCreated());

        // 目标时刻 obs-2 尚未创建（obs-1 已存在），固化为 ABSENT；整次仍成功
        createSnapshot("req-s1", "snap-1", "2026-09-20T01:00:00Z", List.of("obs-1", "obs-2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].version").value(1))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-2"))
                .andExpect(jsonPath("$.items[1].state").value("ABSENT"))
                .andExpect(jsonPath("$.items[1].version").doesNotExist());
    }

    @Test
    void snapshotFreezesTombstoneWithoutContent() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");
        delete("req-d", "obs-1", 1).andExpect(status().isOk());
        tick("2026-09-20T02:00:00Z");

        createSnapshot("req-s1", "snap-1", "2026-09-20T01:30:00Z", List.of("obs-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].state").value("TOMBSTONE"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].deleted").value(true))
                .andExpect(jsonPath("$.items[0].location").doesNotExist());

        JsonNode frozen = readJson(getSnapshot("snap-1"));
        getSnapshot("snap-1").andExpect(status().isOk());
        assertThat(readJson(getSnapshot("snap-1"))).isEqualTo(frozen);
    }

    @Test
    void multipleSnapshotsAtSameTargetInstantAreIndependentAndImmutable() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");

        createSnapshot("req-s1", "snap-a", "2026-09-20T00:30:00Z", List.of("obs-1"))
                .andExpect(status().isCreated());
        createSnapshot("req-s2", "snap-b", "2026-09-20T00:30:00Z", List.of("obs-1"))
                .andExpect(status().isCreated());

        tick("2026-09-20T02:00:00Z");
        merge("req-m", "obs-1", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());

        JsonNode a = readJson(getSnapshot("snap-a"));
        JsonNode b = readJson(getSnapshot("snap-b"));
        assertThat(a.get("globalLatestVersion").asLong()).isEqualTo(1L);
        assertThat(b.get("globalLatestVersion").asLong()).isEqualTo(1L);
        assertThat(a.get("items").get(0).get("location").asText()).isEqualTo("站点A");
        assertThat(b.get("items").get(0).get("location").asText()).isEqualTo("站点A");
        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-a")).isEqualTo(1);
        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-b")).isEqualTo(1);
    }

    // ---------- 快照失败分支与 404 原子性 ----------

    @Test
    void snapshotWithNeverExistedIdReturns404AndSavesNothing() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");

        createSnapshot("req-s1", "snap-1", "2026-09-20T00:30:00Z", List.of("obs-1", "obs-missing"))
                .andExpect(status().isNotFound());

        // 不保存任何部分快照，也不占 snapshotKey / requestId
        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-1")).isZero();
        assertThat(tableCount("observation_snapshot_item", "snapshot_key", "snap-1")).isZero();
        assertThat(tableCount("request_log", "request_id", "req-s1")).isZero();

        // 失败不占键：补齐缺失记录后，同一 snapshotKey 与 requestId 可正常提交
        tick("2026-09-20T01:00:00Z");
        create("req-c2", "obs-missing", "地点Y", "1.0", "备注").andExpect(status().isCreated());
        createSnapshot("req-s1", "snap-1", "2026-09-20T00:30:00Z", List.of("obs-missing", "obs-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void snapshotFutureTargetReturns400AndSavesNothing() throws Exception {
        create("req-c", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T00:00:00Z");
        createSnapshot("req-s1", "snap-1", "2026-09-20T00:00:01Z", List.of("obs-1"))
                .andExpect(status().isBadRequest());
        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-1")).isZero();
        assertThat(tableCount("request_log", "request_id", "req-s1")).isZero();
    }

    @Test
    void snapshotOfFiftyRecordsWithLargeContentReplaysStoredResponse() throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        String bigNote = "重".repeat(900);
        for (int i = 0; i < 50; i++) {
            String id = String.format("obs-%02d", i);
            ids.add(id);
            create("req-c-" + i, id, "站点" + i, "1.0", bigNote).andExpect(status().isCreated());
        }
        tick("2026-09-20T01:00:00Z");

        // 响应体远超 request_log 旧 4000 字符上限：大字段存储 + 同键同参重放必须成功且内容一致
        JsonNode first = readJson(createSnapshot("req-s1", "snap-big",
                "2026-09-20T00:30:00Z", ids).andExpect(status().isCreated()));
        assertThat(first.get("items")).hasSize(50);
        JsonNode replayed = readJson(createSnapshot("req-s1", "snap-big",
                "2026-09-20T00:30:00Z", ids).andExpect(status().isCreated()));
        assertThat(replayed).isEqualTo(first);
        assertThat(replayed.get("items").get(49).get("note").asText()).isEqualTo(bigNote);
    }

    @Test
    void getUnknownSnapshotReturns404() throws Exception {
        getSnapshot("snap-nope").andExpect(status().isNotFound());
    }

    // ---------- 快照幂等 ----------

    @Test
    void snapshotSameRequestIdAndParamsReplaysOriginalSnapshot() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");

        createSnapshot("req-s1", "snap-1", "2026-09-20T00:30:00Z", List.of("obs-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.globalLatestVersion").value(1));

        tick("2026-09-20T02:00:00Z");
        merge("req-m", "obs-1", 1, "站点B", "2.0", "备注2").andExpect(status().isOk());

        // 同键同参重放：返回首次响应内容（仍固化 revision=1），不新建快照
        createSnapshot("req-s1", "snap-1", "2026-09-20T00:30:00Z", List.of("obs-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.globalLatestVersion").value(1))
                .andExpect(jsonPath("$.items[0].location").value("站点A"));
        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-1")).isEqualTo(1);
        assertThat(tableCount("observation_snapshot_item", "snapshot_key", "snap-1")).isEqualTo(1);
    }

    @Test
    void snapshotReorderedIdSetIsSameParameters() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-c2", "obs-2", "站点B", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");

        createSnapshot("req-s1", "snap-1", "2026-09-20T00:30:00Z", List.of("obs-2", "obs-1"))
                .andExpect(status().isCreated());
        // 集合换序 + 重复元素：同参重放，不新建快照，响应仍按升序返回
        createSnapshot("req-s1", "snap-1", "2026-09-20T00:30:00Z",
                        List.of("obs-1", "obs-2", "obs-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-2"));
        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-1")).isEqualTo(1);
    }

    @Test
    void snapshotSameKeyDifferentParametersReturns409AndKeepsOriginal() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-c2", "obs-2", "站点B", "1.0", "备注").andExpect(status().isCreated());
        tick("2026-09-20T01:00:00Z");

        createSnapshot("req-s1", "snap-1", "2026-09-20T00:30:00Z", List.of("obs-1"))
                .andExpect(status().isCreated());

        // 同 snapshotKey 不同 ID 集合：409，原快照不变
        createSnapshot("req-s2", "snap-1", "2026-09-20T00:30:00Z", List.of("obs-2"))
                .andExpect(status().isConflict());
        // 同 snapshotKey 不同目标时刻：409
        createSnapshot("req-s3", "snap-1", "2026-09-20T00:45:00Z", List.of("obs-1"))
                .andExpect(status().isConflict());
        // 同 requestId 不同参数：409
        createSnapshot("req-s1", "snap-2", "2026-09-20T00:30:00Z", List.of("obs-2"))
                .andExpect(status().isConflict());

        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-1")).isEqualTo(1);
        assertThat(tableCount("observation_snapshot", "snapshot_key", "snap-2")).isZero();
        JsonNode original = readJson(getSnapshot("snap-1"));
        assertThat(original.get("items")).hasSize(1);
        assertThat(original.get("items").get(0).get("observationId").asText()).isEqualTo("obs-1");
    }
}
