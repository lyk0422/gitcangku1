package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测禁运与到期披露 API 测试：主流程、422 失败分支、批量回滚、幂等重放、
 * 公开/证据可见性、不可变快照与附页（真实 H2 内存库，MySQL 兼容模式，可控时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbargoApiTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2030-06-01T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2030-07-01T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);

    @TestConfiguration
    static class MutableClockConfiguration {
        @Bean
        @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        CLOCK.setInstant(T0);
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM disclosure_appendix");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM open_conflict");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createObs(String requestId, String id, String location) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", id);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "初始备注");
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String id, int baseVersion,
                                String location, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String requestId, String id, String resolutionId,
                                  int baseVersion, int expectedCurrent, String location,
                                  String selection) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrent);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "初始备注");
        body.put("selections", Map.of("location", selection));
        body.put("operator", "operator-1");
        return mockMvc.perform(post("/api/observations/{id}/resolve", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions setEmbargo(String requestId, String operator,
                                     List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/embargoes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> embargoItem(String id, String until, List<String> scope) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", id);
        item.put("embargoUntilUtc", until);
        item.put("disclosureScope", scope);
        return item;
    }

    private ResultActions release(String requestId, String id, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/release", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int tableCount(String table, String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 禁运设置 ----------

    @Test
    void setEmbargoSucceedsWithNormalizedScopeAndDuration() throws Exception {
        createObs("req-c1", "obs-1", "站点A").andExpect(status().isCreated());

        setEmbargo("req-e1", "operator-1",
                List.of(embargoItem("obs-1", "2030-06-01T00:00:00Z", List.of("RESEARCH", "PUBLIC", "PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("req-e1"))
                .andExpect(jsonPath("$.operator").value("operator-1"))
                .andExpect(jsonPath("$.requestedCount").value(1))
                .andExpect(jsonPath("$.appliedCount").value(1))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.items[0].embargoUntilUtc").value("2030-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[0].setAtUtc").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[0].versionAtSet").value(1))
                .andExpect(jsonPath("$.items[0].disclosureScope[0]").value("PUBLIC"))
                .andExpect(jsonPath("$.items[0].disclosureScope[1]").value("RESEARCH"))
                .andExpect(jsonPath("$.items[0].embargoDurationSeconds")
                        .value(Duration.between(T0, UNTIL).getSeconds()));

        mockMvc.perform(get("/api/observations/embargoes/obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoUntilUtc").value("2030-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.versionAtSet").value(1));
        mockMvc.perform(get("/api/observations/embargoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(tableCount("observation_embargo", "obs-1")).isEqualTo(1);
    }

    @Test
    void embargoHidesPublicQueryButEvidenceStaysVisible() throws Exception {
        createObs("req-c2", "obs-2", "站点B").andExpect(status().isCreated());
        setEmbargo("req-e2", "operator-1",
                List.of(embargoItem("obs-2", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        // 截止前：公开查询不返回簇内容，授权证据查询可见
        mockMvc.perform(get("/api/observations/obs-2")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/obs-2/versions/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/obs-2/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点B"));

        // 到期后（未发布）：禁运区间结束，公开查询恢复可见，读取不改变状态
        CLOCK.setInstant(AFTER);
        mockMvc.perform(get("/api/observations/obs-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点B"));
        assertThat(tableCount("disclosure_snapshot", "obs-2")).isZero();
    }

    @Test
    void batchSetEmbargoIsAllOrNothingAndFailureDoesNotBurnKey() throws Exception {
        createObs("req-c3a", "obs-3a", "站点A").andExpect(status().isCreated());
        createObs("req-c3b", "obs-3b", "站点B").andExpect(status().isCreated());

        setEmbargo("req-e3", "operator-1", List.of(
                embargoItem("obs-3a", "2030-06-01T00:00:00Z", List.of("PUBLIC")),
                embargoItem("obs-3b", "2030-06-01T00:00:00Z", List.of())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].observationId").value("obs-3b"))
                .andExpect(jsonPath("$.details[0].reason").value("EMPTY_DISCLOSURE_SCOPE"));

        // 整次回滚：合法簇也未写入，失败不占幂等键
        mockMvc.perform(get("/api/observations/embargoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/observations/embargoes/obs-3a")).andExpect(status().isNotFound());
        Integer logRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-e3'", Integer.class);
        assertThat(logRows).isZero();

        // 修正参数后同一 requestId 可重新提交成功
        setEmbargo("req-e3", "operator-1", List.of(
                embargoItem("obs-3a", "2030-06-01T00:00:00Z", List.of("PUBLIC")),
                embargoItem("obs-3b", "2030-06-01T00:00:00Z", List.of("RESEARCH"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliedCount").value(2));
    }    @Test
    void embargoDeadlineBeforeClusterCreationReturns422() throws Exception {
        createObs("req-c4", "obs-4", "站点A").andExpect(status().isCreated());

        setEmbargo("req-e4", "operator-1",
                List.of(embargoItem("obs-4", "2020-01-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].reason").value("EMBARGO_BEFORE_CREATION"))
                .andExpect(jsonPath("$.details[0].actual").value("2020-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.details[0].required").exists())
                .andExpect(jsonPath("$.details[0].differenceSeconds").isNumber());
        assertThat(tableCount("observation_embargo", "obs-4")).isZero();
    }

    @Test
    void unresolvedConflictBlocksEmbargoUntilResolved() throws Exception {
        createObs("req-c5", "obs-5", "站点A").andExpect(status().isCreated());
        merge("req-m5a", "obs-5", 1, "站点B", "初始备注").andExpect(status().isOk());
        // 基于旧基线的并发修改：三方都改地点，检出冲突
        merge("req-m5b", "obs-5", 1, "站点C", "初始备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"));

        setEmbargo("req-e5", "operator-1",
                List.of(embargoItem("obs-5", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].reason").value("UNRESOLVED_CONFLICT"));
        mockMvc.perform(get("/api/observations/embargoes/obs-5/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unresolvedConflict").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"));

        // 冲突裁决后未解决状态清除，禁运可设置
        resolve("req-r5", "obs-5", "res-5", 1, 2, "站点C", "CANDIDATE")
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/embargoes/obs-5/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unresolvedConflict").value(false));
        setEmbargo("req-e5b", "operator-1",
                List.of(embargoItem("obs-5", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].versionAtSet").value(3));
    }

    @Test
    void embargoIsImmutableAndRequestIdReplayIsStable() throws Exception {
        createObs("req-c6", "obs-6", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e6", "operator-1",
                List.of(embargoItem("obs-6", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        // 同键同参重放：返回首次完整响应快照
        setEmbargo("req-e6", "operator-1",
                List.of(embargoItem("obs-6", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].setAtUtc").value("2030-01-01T00:00:00Z"));
        // 同键异参：409
        setEmbargo("req-e6", "operator-1",
                List.of(embargoItem("obs-6", "2030-08-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isConflict());
        // 禁运不可变：新请求重复设置同簇返回 409 并区分原因
        setEmbargo("req-e6b", "operator-1",
                List.of(embargoItem("obs-6", "2030-08-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].reason").value("EMBARGO_ALREADY_SET"));
        assertThat(tableCount("observation_embargo", "obs-6")).isEqualTo(1);
    }

    @Test
    void fingerprintUsesNormalizedScopeSoEquivalentReplaysMatch() throws Exception {
        createObs("req-c7", "obs-7", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e7", "operator-1",
                List.of(embargoItem("obs-7", "2030-06-01T00:00:00Z", List.of("B", "A", "A"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].disclosureScope[0]").value("A"))
                .andExpect(jsonPath("$.items[0].disclosureScope[1]").value("B"));

        // 规范化后等价的范围集合视为同参重放
        setEmbargo("req-e7", "operator-1",
                List.of(embargoItem("obs-7", "2030-06-01T00:00:00Z", List.of("A", "B"))))
                .andExpect(status().isCreated());
        // 范围内容不同视为异参
        setEmbargo("req-e7", "operator-1",
                List.of(embargoItem("obs-7", "2030-06-01T00:00:00Z", List.of("A", "C"))))
                .andExpect(status().isConflict());
        assertThat(tableCount("observation_embargo", "obs-7")).isEqualTo(1);
    }

    @Test
    void duplicateObservationIdInBatchReturns422() throws Exception {
        createObs("req-c8", "obs-8", "站点A").andExpect(status().isCreated());

        setEmbargo("req-e8", "operator-1", List.of(
                embargoItem("obs-8", "2030-06-01T00:00:00Z", List.of("PUBLIC")),
                embargoItem("obs-8", "2030-07-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].reason").value("DUPLICATE_OBSERVATION"));
        assertThat(tableCount("observation_embargo", "obs-8")).isZero();
    }

    // ---------- 到期发布 ----------

    @Test
    void releaseBeforeDeadlineReturns422WithRemainingSeconds() throws Exception {
        createObs("req-c9", "obs-9", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e9", "operator-1",
                List.of(embargoItem("obs-9", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        release("req-rel9", "obs-9", "operator-2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].reason").value("EMBARGO_STILL_ACTIVE"))
                .andExpect(jsonPath("$.details[0].actual").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.details[0].required").value("2030-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.details[0].differenceSeconds")
                        .value(Duration.between(T0, UNTIL).getSeconds()));
        assertThat(tableCount("disclosure_snapshot", "obs-9")).isZero();
    }

    @Test
    void releaseAtExactDeadlineSucceedsAndOneSecondBeforeFails() throws Exception {
        createObs("req-c10", "obs-10", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e10", "operator-1",
                List.of(embargoItem("obs-10", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        // 左闭右开：截止时刻前一秒仍在禁运期
        CLOCK.setInstant(UNTIL.minusSeconds(1));
        release("req-rel10a", "obs-10", "operator-2").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/observations/obs-10")).andExpect(status().isNotFound());

        // 到达截止时刻即解禁：可发布，公开查询恢复
        CLOCK.setInstant(UNTIL);
        release("req-rel10b", "obs-10", "operator-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.releasedAtUtc").value("2030-06-01T00:00:00Z"));
        mockMvc.perform(get("/api/observations/obs-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"));
    }    @Test
    void releaseSnapshotCapturesWinningVersionResolutionsAndFlags() throws Exception {
        createObs("req-c11", "obs-11", "站点A").andExpect(status().isCreated());
        merge("req-m11a", "obs-11", 1, "站点B", "初始备注").andExpect(status().isOk());
        merge("req-m11b", "obs-11", 1, "站点C", "初始备注").andExpect(status().isConflict());
        resolve("req-r11", "obs-11", "res-11", 1, 2, "站点C", "CANDIDATE").andExpect(status().isOk());
        setEmbargo("req-e11", "operator-1",
                List.of(embargoItem("obs-11", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].versionAtSet").value(3));
        // 禁运后的更正：版本前进，发布时应带上内容变更质量标记
        merge("req-m11c", "obs-11", 3, "站点C", "禁运后更正").andExpect(status().isOk());

        CLOCK.setInstant(AFTER);
        release("req-rel11", "obs-11", "operator-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-11"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.note").value("禁运后更正"))
                .andExpect(jsonPath("$.resolutions.length()").value(1))
                .andExpect(jsonPath("$.resolutions[0].resolutionId").value("res-11"))
                .andExpect(jsonPath("$.resolutions[0].newVersion").value(3))
                .andExpect(jsonPath("$.qualityFlags[0]").value("CONTENT_CHANGED_AFTER_EMBARGO"))
                .andExpect(jsonPath("$.qualityFlags[1]").value("HAS_RESOLVED_CONFLICTS"))
                .andExpect(jsonPath("$.releasedAtUtc").value("2030-07-01T00:00:00Z"))
                .andExpect(jsonPath("$.appendixCount").value(0));

        // 到期后的新更正只进入附页，不改写首次快照
        merge("req-m11d", "obs-11", 4, "站点D", "禁运后更正").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/disclosures/obs-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.appendixCount").value(1));
        mockMvc.perform(get("/api/observations/disclosures/obs-11/appendices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].version").value(5))
                .andExpect(jsonPath("$[0].location").value("站点D"))
                .andExpect(jsonPath("$[0].sourceRequestId").value("req-m11d"))
                .andExpect(jsonPath("$[0].recordedAtUtc").value("2030-07-01T00:00:00Z"));
        mockMvc.perform(get("/api/observations/disclosures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void releaseIsIdempotentAndSnapshotStaysSingle() throws Exception {
        createObs("req-c12", "obs-12", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e12", "operator-1",
                List.of(embargoItem("obs-12", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());
        CLOCK.setInstant(AFTER);

        release("req-rel12", "obs-12", "operator-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.releasedAtUtc").value("2030-07-01T00:00:00Z"));
        // 同键同参重放：返回首次完整响应快照
        release("req-rel12", "obs-12", "operator-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.releasedAtUtc").value("2030-07-01T00:00:00Z"));
        // 同键异参：409
        release("req-rel12", "obs-12", "operator-9").andExpect(status().isConflict());
        // 新请求重复发布：返回既有快照（200），不生成第二个快照
        release("req-rel12b", "obs-12", "operator-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAtUtc").value("2030-07-01T00:00:00Z"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(tableCount("disclosure_snapshot", "obs-12")).isEqualTo(1);
    }

    @Test
    void deleteAfterReleaseAppendsTombstoneAppendix() throws Exception {
        createObs("req-c13", "obs-13", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e13", "operator-1",
                List.of(embargoItem("obs-13", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());
        CLOCK.setInstant(AFTER);
        release("req-rel13", "obs-13", "operator-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.qualityFlags.length()").value(0));

        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d13");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/obs-13/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/observations/disclosures/obs-13/appendices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].deleted").value(true))
                .andExpect(jsonPath("$[0].location").doesNotExist());
        // 首次快照不被删除更正改写
        mockMvc.perform(get("/api/observations/disclosures/obs-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.appendixCount").value(1));
    }

    @Test
    void releaseOfDeletedClusterCapturesTombstoneFlag() throws Exception {
        createObs("req-c14", "obs-14", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e14", "operator-1",
                List.of(embargoItem("obs-14", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d14");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/obs-14/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        CLOCK.setInstant(AFTER);
        release("req-rel14", "obs-14", "operator-2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.qualityFlags[0]").value("CONTENT_CHANGED_AFTER_EMBARGO"))
                .andExpect(jsonPath("$.qualityFlags[1]").value("DELETED_BEFORE_RELEASE"));
    }

    @Test
    void embargoStatusDiagnosticsReportActualValues() throws Exception {
        createObs("req-c15", "obs-15", "站点A").andExpect(status().isCreated());
        setEmbargo("req-e15", "operator-1",
                List.of(embargoItem("obs-15", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/observations/embargoes/obs-15/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationId").value("obs-15"))
                .andExpect(jsonPath("$.nowUtc").value("2030-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.embargoSet").value(true))
                .andExpect(jsonPath("$.embargoActive").value(true))
                .andExpect(jsonPath("$.remainingSeconds")
                        .value(Duration.between(T0, UNTIL).getSeconds()))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.unresolvedConflict").value(false))
                .andExpect(jsonPath("$.appendixCount").value(0));

        CLOCK.setInstant(AFTER);
        release("req-rel15", "obs-15", "operator-2").andExpect(status().isCreated());
        mockMvc.perform(get("/api/observations/embargoes/obs-15/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoActive").value(false))
                .andExpect(jsonPath("$.remainingSeconds")
                        .value(Duration.between(AFTER, UNTIL).getSeconds()))
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.releasedAtUtc").value("2030-07-01T00:00:00Z"));
    }

    @Test
    void embargoListKeepsStableOrdering() throws Exception {
        createObs("req-c16a", "obs-16a", "站点A").andExpect(status().isCreated());
        createObs("req-c16b", "obs-16b", "站点B").andExpect(status().isCreated());
        // 同一批次内乱序提交：响应与列表均按簇标识字典序稳定输出
        setEmbargo("req-e16", "operator-1", List.of(
                embargoItem("obs-16b", "2030-06-01T00:00:00Z", List.of("PUBLIC")),
                embargoItem("obs-16a", "2030-06-01T00:00:00Z", List.of("PUBLIC"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].observationId").value("obs-16a"))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-16b"));

        mockMvc.perform(get("/api/observations/embargoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].observationId").value("obs-16a"))
                .andExpect(jsonPath("$[1].observationId").value("obs-16b"));
    }

    @Test
    void releaseWithoutEmbargoReturns404() throws Exception {
        createObs("req-c17", "obs-17", "站点A").andExpect(status().isCreated());
        release("req-rel17", "obs-17", "operator-2").andExpect(status().isNotFound());
        assertThat(tableCount("disclosure_snapshot", "obs-17")).isZero();
    }
}