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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * 观测禁运与到期披露 API 测试：批量设置、422 校验分支、整批回滚、到期发布、
 * 公开/证据/披露/诊断查询、附页与幂等边界。真实 H2 内存库（MySQL 兼容模式）与可控 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmbargoApiTest {

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
        jdbcTemplate.update("DELETE FROM disclosure_appendix");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM observation_conflict");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        stubClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private void stubClock(Instant instant) {
        Mockito.when(clock.instant()).thenReturn(instant);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    /**
     * 生成一个合法禁运截止时刻：真实当前时间之后一小时（不早于簇创建），秒级精度。
     */
    private String futureDeadline() {
        return Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS).toString();
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
                                  String location, String selection) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("selections", Map.of("location", selection));
        body.put("operator", "operator-1");
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions setEmbargo(String requestId, String operator,
                                     List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/embargo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> item(String observationId, String embargoUntilUtc, String scope) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("embargoUntilUtc", embargoUntilUtc);
        item.put("disclosureScope", scope);
        return item;
    }

    private ResultActions release(String requestId, String operator, String observationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/release", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getPublic(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/public", observationId));
    }

    private ResultActions getEvidence(String observationId, String operator) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/evidence", observationId)
                .param("operator", operator));
    }

    private ResultActions getDisclosure(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/disclosure", observationId));
    }

    private ResultActions getAppendices(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/disclosure/appendices", observationId));
    }

    private ResultActions getDiagnostics(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/embargo", observationId));
    }

    private int countRows(String table, String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }
    // ---------- 批量禁运设置 ----------

    @Test
    void setEmbargoBatchSuccessAndDiagnostics() throws Exception {
        create("req-c1", "obs-a", "站点A", "1.0", "备注").andExpect(status().isCreated());
        create("req-c2", "obs-b", "站点B", "2.0", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        stubClock(now);

        // 故意乱序提交，响应应按 observationId 升序规范化
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-b", until, "公开范围"), item("obs-a", until, "公开范围")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-e1"))
                .andExpect(jsonPath("$.operator").value("auditor-1"))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.setAtUtc").value(now.toString()))
                .andExpect(jsonPath("$.items[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.items[0].embargoUntilUtc").value(until))
                .andExpect(jsonPath("$.items[1].observationId").value("obs-b"));

        long expectedRemaining = Instant.parse(until).toEpochMilli() - now.toEpochMilli();
        getDiagnostics("obs-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoSet").value(true))
                .andExpect(jsonPath("$.embargoUntilUtc").value(until))
                .andExpect(jsonPath("$.disclosureScope").value("公开范围"))
                .andExpect(jsonPath("$.operator").value("auditor-1"))
                .andExpect(jsonPath("$.nowUtc").value(now.toString()))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.remainingMillis").value(expectedRemaining))
                .andExpect(jsonPath("$.openConflicts").value(0))
                .andExpect(jsonPath("$.released").value(false));
    }

    @Test
    void publicQueryEmbargoedButEvidenceVisible() throws Exception {
        create("req-c1", "obs-1", "站点A", "12.5", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "公开范围")))
                .andExpect(status().isOk());

        // 截止前公开查询不返回簇内容
        getPublic("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoed").value(true))
                .andExpect(jsonPath("$.embargoUntilUtc").value(until))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.reading").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());

        // 授权证据查询可见簇内容
        getEvidence("obs-1", "auditor-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.reading").value("12.5"))
                .andExpect(jsonPath("$.embargoed").value(true))
                .andExpect(jsonPath("$.embargoUntilUtc").value(until))
                .andExpect(jsonPath("$.openConflicts").value(0))
                .andExpect(jsonPath("$.released").value(false));

        // 缺少操作者标识的证据查询拒绝
        mockMvc.perform(get("/api/observations/{id}/evidence", "obs-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publicQueryWithoutEmbargoReturnsCurrentContent() throws Exception {
        create("req-c1", "obs-1", "站点A", "12.5", "备注").andExpect(status().isCreated());
        getPublic("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoed").value(false))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("站点A"));
        getDiagnostics("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoSet").value(false))
                .andExpect(jsonPath("$.released").value(false));
    }

    @Test
    void setEmbargoDeadlineBeforeCreationReturns422() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", "2020-01-01T00:00:00Z", "公开范围")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.itemErrors[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.details.itemErrors[0].reason").value("DEADLINE_BEFORE_CREATION"))
                .andExpect(jsonPath("$.details.itemErrors[0].actualValue").value("2020-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.details.itemErrors[0].requiredValue").exists());
        assertThat(countRows("observation_embargo", "obs-1")).isZero();
    }

    @Test
    void setEmbargoEmptyScopeReturns422() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", futureDeadline(), "   ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.itemErrors[0].reason").value("EMPTY_SCOPE"))
                .andExpect(jsonPath("$.details.itemErrors[0].requiredValue").value("non-blank scope"));
        assertThat(countRows("observation_embargo", "obs-1")).isZero();
    }

    @Test
    void setEmbargoDuplicateInBatchReturns422() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "范围A"), item("obs-1", until, "范围B")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.itemErrors[0].reason").value("DUPLICATE_IN_BATCH"));
        assertThat(countRows("observation_embargo", "obs-1")).isZero();
    }

    @Test
    void setEmbargoUnresolvedConflictsReturns422ThenSucceedsAfterResolve() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-m1", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 离线端基于 v1 改站点C：与站点B 冲突，登记 OPEN 冲突
        merge("req-m2", "obs-1", 1, "站点C", "1.0", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"));

        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", futureDeadline(), "公开范围")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.itemErrors[0].reason").value("UNRESOLVED_CONFLICTS"))
                .andExpect(jsonPath("$.details.itemErrors[0].actualValue").value("1"))
                .andExpect(jsonPath("$.details.itemErrors[0].requiredValue").value("0"));
        assertThat(countRows("observation_embargo", "obs-1")).isZero();

        // 显式解决冲突后（关闭 OPEN 记录），禁运设置成功
        resolve("req-r1", "res-1", "obs-1", 1, 2, "站点C", "CANDIDATE")
                .andExpect(status().isOk());
        setEmbargo("req-e2", "auditor-1", List.of(item("obs-1", futureDeadline(), "公开范围")))
                .andExpect(status().isOk());
        getDiagnostics("obs-1")
                .andExpect(jsonPath("$.embargoSet").value(true))
                .andExpect(jsonPath("$.openConflicts").value(0));
    }

    @Test
    void setEmbargoBatchRollbackAndFailureDoesNotOccupyKey() throws Exception {
        create("req-c1", "obs-ok", "站点A", "1.0", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        // 一个条目不存在：整次 422 回滚，合法簇也不写入
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-ok", until, "公开范围"), item("obs-missing", until, "公开范围")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.requestedCount").value(2))
                .andExpect(jsonPath("$.details.invalidCount").value(1))
                .andExpect(jsonPath("$.details.itemErrors[0].observationId").value("obs-missing"))
                .andExpect(jsonPath("$.details.itemErrors[0].reason").value("OBSERVATION_NOT_FOUND"));
        getDiagnostics("obs-ok").andExpect(jsonPath("$.embargoSet").value(false));
        assertThat(countRows("observation_embargo", "obs-ok")).isZero();

        // 失败不占键：同一 requestId 修正参数后成功
        create("req-c2", "obs-missing", "站点B", "2.0", "备注").andExpect(status().isCreated());
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-ok", until, "公开范围"), item("obs-missing", until, "公开范围")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2));
    }

    @Test
    void embargoIsImmutableAndRequestIdIdempotent() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        MvcResult first = setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "公开范围")))
                .andExpect(status().isOk()).andReturn();

        // 同参重放：返回首次完整响应快照
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "公开范围")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));

        // 同键异参：409
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "另一个范围")))
                .andExpect(status().isConflict());

        // 禁运不可变：新 requestId 重复设置 422
        setEmbargo("req-e2", "auditor-2", List.of(item("obs-1", until, "另一个范围")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.itemErrors[0].reason").value("ALREADY_EMBARGOED"));
        assertThat(countRows("observation_embargo", "obs-1")).isEqualTo(1);
    }
    // ---------- 到期发布与披露快照 ----------

    @Test
    void releaseBeforeExpiryReturns422WithRemainingThenSucceedsAfterExpiry() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "公开范围")))
                .andExpect(status().isOk());

        // 未到期：422 并返回剩余时长（实际值）
        Instant beforeExpiry = Instant.parse(until).minusSeconds(600);
        stubClock(beforeExpiry);
        release("req-r1", "publisher-1", "obs-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.reason").value("EMBARGO_NOT_EXPIRED"))
                .andExpect(jsonPath("$.details.embargoUntilUtc").value(until))
                .andExpect(jsonPath("$.details.nowUtc").value(beforeExpiry.toString()))
                .andExpect(jsonPath("$.details.remainingMillis").value(600000));
        assertThat(countRows("disclosure_snapshot", "obs-1")).isZero();

        // 失败不占键：到期后同一 requestId 发布成功
        Instant afterExpiry = Instant.parse(until).plusSeconds(60);
        stubClock(afterExpiry);
        release("req-r1", "publisher-1", "obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.winningVersion").value(1))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.qualityFlags[0]").value("CLEAN"))
                .andExpect(jsonPath("$.releasedAtUtc").value(afterExpiry.toString()))
                .andExpect(jsonPath("$.appendices").isEmpty());
        assertThat(countRows("disclosure_snapshot", "obs-1")).isEqualTo(1);
    }

    @Test
    void releaseWithoutEmbargoReturns422() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        release("req-r1", "publisher-1", "obs-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.reason").value("NO_EMBARGO"));
    }

    @Test
    void disclosureNotFoundBeforeRelease() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        getDisclosure("obs-1").andExpect(status().isNotFound());
        getAppendices("obs-1").andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void releaseSnapshotContainsWinningVersionResolutionsAndQualityFlags() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-m1", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        merge("req-m2", "obs-1", 1, "站点C", "1.0", "备注").andExpect(status().isConflict());
        resolve("req-s1", "res-1", "obs-1", 1, 2, "站点C", "CANDIDATE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));

        String until = futureDeadline();
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "公开范围")))
                .andExpect(status().isOk());
        Instant afterExpiry = Instant.parse(until).plusSeconds(1);
        stubClock(afterExpiry);

        release("req-r1", "publisher-1", "obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningVersion").value(3))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.resolutions[0].resolutionId").value("res-1"))
                .andExpect(jsonPath("$.resolutions[0].newVersion").value(3))
                .andExpect(jsonPath("$.resolutions[0].conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.qualityFlags[0]").value("CONFLICT_RESOLVED"))
                .andExpect(jsonPath("$.releaseRequestId").value("req-r1"))
                .andExpect(jsonPath("$.operator").value("publisher-1"));

        // 披露明细查询返回同一不可变快照
        getDisclosure("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningVersion").value(3))
                .andExpect(jsonPath("$.resolutions[0].resolutionId").value("res-1"));

        // 到期后公开查询返回首次披露快照
        getPublic("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embargoed").value(false))
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.disclosure.winningVersion").value(3))
                .andExpect(jsonPath("$.disclosure.location").value("站点C"));
    }

    @Test
    void releaseReplayReturnsFirstSnapshotAndReleaseIsImmutable() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "公开范围")))
                .andExpect(status().isOk());
        Instant afterExpiry = Instant.parse(until).plusSeconds(1);
        stubClock(afterExpiry);

        MvcResult first = release("req-r1", "publisher-1", "obs-1")
                .andExpect(status().isOk()).andReturn();

        // 同参重放：返回首次完整响应快照
        release("req-r1", "publisher-1", "obs-1")
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));

        // 同键异参：409
        release("req-r1", "publisher-2", "obs-1")
                .andExpect(status().isConflict());

        // 新 requestId 重复发布：返回首次快照（发布不可变）
        stubClock(afterExpiry.plusSeconds(3600));
        release("req-r2", "publisher-2", "obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAtUtc").value(afterExpiry.toString()))
                .andExpect(jsonPath("$.releaseRequestId").value("req-r1"))
                .andExpect(jsonPath("$.operator").value("publisher-1"));
        assertThat(countRows("disclosure_snapshot", "obs-1")).isEqualTo(1);
    }

    @Test
    void postReleaseCorrectionsGoToAppendicesAndSnapshotStaysImmutable() throws Exception {
        create("req-c1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        String until = futureDeadline();
        setEmbargo("req-e1", "auditor-1", List.of(item("obs-1", until, "公开范围")))
                .andExpect(status().isOk());
        Instant afterExpiry = Instant.parse(until).plusSeconds(1);
        stubClock(afterExpiry);
        release("req-r1", "publisher-1", "obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningVersion").value(1));

        // 到期后的新更正：产生 v2，只进入附页
        merge("req-m1", "obs-1", 1, "站点B", "2.0", "更正备注").andExpect(status().isOk());
        // 再删除：产生 v3 墓碑，也进入附页
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-d1");
        deleteBody.put("expectedVersion", 2);
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        // 首次快照不被改写
        getDisclosure("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winningVersion").value(1))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.appendices[0].seq").value(1))
                .andExpect(jsonPath("$.appendices[0].version").value(2))
                .andExpect(jsonPath("$.appendices[0].operation").value("MERGE"))
                .andExpect(jsonPath("$.appendices[0].requestId").value("req-m1"))
                .andExpect(jsonPath("$.appendices[1].seq").value(2))
                .andExpect(jsonPath("$.appendices[1].version").value(3))
                .andExpect(jsonPath("$.appendices[1].operation").value("DELETE"));

        // 附页历史查询：按 seq 升序，排序稳定
        getAppendices("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[1].seq").value(2))
                .andExpect(jsonPath("$.length()").value(2));

        // 证据查询仍可见当前真实状态（已删除墓碑）
        getEvidence("obs-1", "auditor-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.released").value(true));
    }
}