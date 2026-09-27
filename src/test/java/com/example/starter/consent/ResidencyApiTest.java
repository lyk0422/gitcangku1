package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.CrossQueryRequest;
import com.example.starter.consent.dto.CrossQueryResponse;
import com.example.starter.consent.dto.MigrationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权数据驻留路由与跨域查询快照隔离集成测试：
 * 覆盖区域登记、区域路由写入、数据清除、驻留迁移、跨域查询快照的主流程、
 * 失败分支及事务、并发、幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResidencyApiTest {

    /**
     * 可控 UTC 时钟：时间相关断言通过显式推进获得确定的时间点，不依赖系统时钟分辨率。
     */
    static class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advanceSeconds(long seconds) {
            now.updateAndGet(instant -> instant.plusSeconds(seconds));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-27T00:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResidencyService residencyService;

    @Autowired
    private QueryService queryService;

    @Autowired
    private MutableClock mutableClock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM query_snapshot_item");
        jdbc.update("DELETE FROM query_snapshot");
        jdbc.update("DELETE FROM residency_migration");
        jdbc.update("DELETE FROM residency_region");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    // ---------- 请求辅助 ----------

    private ResultActions registerRegion(String requestId, String regionCode) throws Exception {
        return mockMvc.perform(post("/api/v1/residency/regions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","regionCode":"%s"}
                        """.formatted(requestId, regionCode)));
    }

    private ResultActions grant(String requestId, String subjectKey, String purpose, String regionCode)
            throws Exception {
        String region = regionCode == null ? "null" : "\"" + regionCode + "\"";
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","regionCode":%s}
                        """.formatted(requestId, subjectKey, purpose, region)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload, String regionCode) throws Exception {
        String region = regionCode == null ? "null" : "\"" + regionCode + "\"";
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s","regionCode":%s}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload, region)));
    }

    private ResultActions erase(String requestId, String subjectKey, String purpose, String recordKey)
            throws Exception {
        return mockMvc.perform(post("/api/v1/records/erasures")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey)));
    }

    private ResultActions migrate(String requestId, String subjectKey, String purpose,
                                  String targetRegion, String... recordKeys) throws Exception {
        String keys = java.util.Arrays.stream(recordKeys)
                .map(k -> "\"" + k + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return mockMvc.perform(post("/api/v1/residency/migrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","targetRegionCode":"%s","recordKeys":[%s]}
                        """.formatted(requestId, subjectKey, purpose, targetRegion, keys)));
    }

    private ResultActions crossQuery(String requestId, String allowedRegions, String objects) throws Exception {
        return mockMvc.perform(post("/api/v1/queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","allowedRegions":[%s],"objects":[%s]}
                        """.formatted(requestId, allowedRegions, objects)));
    }

    private String objectRef(String subjectKey, String purpose, String recordKey) {
        return """
                {"subjectKey":"%s","purpose":"%s","recordKey":"%s"}
                """.formatted(subjectKey, purpose, recordKey);
    }

    private String objectRef(String subjectKey, String purpose, String recordKey, int epoch) {
        return """
                {"subjectKey":"%s","purpose":"%s","recordKey":"%s","epoch":%d}
                """.formatted(subjectKey, purpose, recordKey, epoch);
    }

    private ResultActions read(String subjectKey, String purpose, String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private String jsonString(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(field).isNull() ? null : node.get(field).asText();
    }

    // ---------- 区域登记 ----------

    @Test
    void regionRegisterListAndIdempotency() throws Exception {
        registerRegion("rr-1", "eu-west")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value("eu-west"))
                .andExpect(jsonPath("$.createdAt").exists());

        // 同 requestId 同参重放：返回首次结果，不重复登记
        registerRegion("rr-1", "eu-west").andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM residency_region")).isEqualTo(1);

        // 同 requestId 异参：409
        registerRegion("rr-1", "ap-sg")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 新 requestId 重复登记同一区域：409
        registerRegion("rr-2", "eu-west")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGION_ALREADY_REGISTERED"));

        registerRegion("rr-3", "ap-sg").andExpect(status().isOk());
        // 区域列表按字典序稳定返回
        mockMvc.perform(get("/api/v1/residency/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regionCode").value("ap-sg"))
                .andExpect(jsonPath("$[1].regionCode").value("eu-west"));
    }

    // ---------- 授权区域登记与不可变 ----------

    @Test
    void grantWithUnregisteredRegionReturns422() throws Exception {
        grant("g-1", "subj-a", "RESEARCH", "eu-west")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REGION_NOT_REGISTERED"));
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(0);
    }

    @Test
    void grantRegionIsImmutableWhileActive() throws Exception {
        registerRegion("rr-1", "eu-west").andExpect(status().isOk());
        registerRegion("rr-2", "ap-sg").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH", "eu-west").andExpect(status().isOk());

        // 同区域重复授权：返回原代次
        grant("g-2", "subj-a", "RESEARCH", "eu-west")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        // 有效代次不得变更区域
        grant("g-3", "subj-a", "RESEARCH", "ap-sg")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_REGION_IMMUTABLE"));
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(1);
    }

    // ---------- 写入区域路由 ----------

    @Test
    void writeMustCarrySameRegionRoute() throws Exception {
        registerRegion("rr-1", "eu-west").andExpect(status().isOk());
        registerRegion("rr-2", "ap-sg").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH", "eu-west").andExpect(status().isOk());

        // 同区域路由：可写
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1", "eu-west")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1));
        // 不同区域路由：403
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2", "ap-sg")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_ROUTE_MISMATCH"));
        // 缺失区域路由：403
        write("w-3", "subj-a", "RESEARCH", "rec-3", "payload-3", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_ROUTE_MISMATCH"));
        // 失败请求不产生半成品
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE region_code = 'eu-west'")).isEqualTo(1);
    }

    // ---------- 数据清除 ----------

    @Test
    void eraseMakesRecordGoneAndIsIdempotent() throws Exception {
        registerRegion("rr-1", "eu-west").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH", "eu-west").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1", "eu-west").andExpect(status().isOk());

        erase("e-1", "subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.erasedAt").exists());
        // 同 requestId 重放返回首次结果
        erase("e-1", "subj-a", "RESEARCH", "rec-1").andExpect(status().isOk());

        // 清除后读取 410，内容已置 NULL
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DATA_ERASED"));
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE payload IS NULL")).isEqualTo(1);

        // 新 requestId 重复清除：409
        erase("e-2", "subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_ALREADY_ERASED"));
        // 清除后同 recordKey 不可重写
        write("w-2", "subj-a", "RESEARCH", "rec-1", "payload-x", "eu-west")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DATA_ERASED"));
    }

    @Test
    void eraseFailureBranches() throws Exception {
        erase("e-1", "subj-none", "RESEARCH", "rec-1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));

        grant("g-1", "subj-a", "RESEARCH", null).andExpect(status().isOk());
        erase("e-2", "subj-a", "RESEARCH", "rec-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));

        // 失败不占键：授权与写入后用同一 requestId 成功清除
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1", null).andExpect(status().isOk());
        erase("e-2", "subj-a", "RESEARCH", "rec-1").andExpect(status().isOk());
    }

    // ---------- 驻留迁移 ----------

    private void setupTwoRegionsWithRecords() throws Exception {
        registerRegion("rr-1", "eu-west").andExpect(status().isOk());
        registerRegion("rr-2", "ap-sg").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH", "eu-west").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1", "eu-west").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2", "eu-west").andExpect(status().isOk());
    }

    @Test
    void migrationCopiesListedRecordsAtomicallyAndRevokesSource() throws Exception {
        setupTwoRegionsWithRecords();

        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1", "rec-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceEpoch").value(1))
                .andExpect(jsonPath("$.targetEpoch").value(2))
                .andExpect(jsonPath("$.sourceRegionCode").value("eu-west"))
                .andExpect(jsonPath("$.targetRegionCode").value("ap-sg"))
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.copiedCount").value(2))
                .andExpect(jsonPath("$.migratedAt").exists());

        // 源代次已撤回，读取走最新代次：迁移记录在新代次可读
        read("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.payload").value("payload-1"));
        // 新代次写入必须携带新区域路由
        write("w-3", "subj-a", "RESEARCH", "rec-3", "payload-3", "eu-west")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_ROUTE_MISMATCH"));
        write("w-4", "subj-a", "RESEARCH", "rec-3", "payload-3", "ap-sg").andExpect(status().isOk());

        // 诊断视图：epoch1 REVOKED、epoch2 ACTIVE ap-sg
        mockMvc.perform(get("/api/v1/residency/grants")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].epoch").value(1))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].regionCode").value("eu-west"))
                .andExpect(jsonPath("$[0].recordCount").value(2))
                .andExpect(jsonPath("$[1].epoch").value(2))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].regionCode").value("ap-sg"))
                .andExpect(jsonPath("$[1].recordCount").value(3));

        // 迁移历史冻结可查
        mockMvc.perform(get("/api/v1/residency/migrations")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceEpoch").value(1))
                .andExpect(jsonPath("$[0].targetEpoch").value(2))
                .andExpect(jsonPath("$[0].copiedCount").value(2));
    }

    @Test
    void migrationWithErasedRecordReturns422AndLeavesNoPartialState() throws Exception {
        setupTwoRegionsWithRecords();
        erase("e-1", "subj-a", "RESEARCH", "rec-2").andExpect(status().isOk());

        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1", "rec-2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_RECORD_ERASED"));

        // 源和目标均不留下部分副本：无新代次、无复制记录、无迁移历史、源代次仍有效
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(0);
        // 失败不占键：同一 requestId 换参数可成功
        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copiedCount").value(1));
    }

    @Test
    void migrationWithMissingRecordReturns422() throws Exception {
        setupTwoRegionsWithRecords();
        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1", "rec-none")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_RECORD_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2")).isEqualTo(0);
    }

    @Test
    void migrationWithDifferentRegionObjectReturns422() throws Exception {
        setupTwoRegionsWithRecords();
        registerRegion("rr-3", "us-east").andExpect(status().isOk());
        // 显式撤回后按新区域重新授权：rec-1/rec-2 留在 eu-west 的旧代次
        mockMvc.perform(post("/api/v1/consents/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv-1\",\"subjectKey\":\"subj-a\",\"purpose\":\"RESEARCH\",\"epoch\":1}"))
                .andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH", "ap-sg").andExpect(status().isOk());
        write("w-3", "subj-a", "RESEARCH", "rec-3", "payload-3", "ap-sg").andExpect(status().isOk());

        // rec-1 只存在于 eu-west 的旧代次：属于不同区域对象
        migrate("m-1", "subj-a", "RESEARCH", "us-east", "rec-3", "rec-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_REGION_MISMATCH"));
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(0);
    }

    @Test
    void migrationValidationBranches() throws Exception {
        setupTwoRegionsWithRecords();

        // 目标区域未登记
        migrate("m-1", "subj-a", "RESEARCH", "us-east", "rec-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REGION_NOT_REGISTERED"));
        // 目标区域与源区域相同
        migrate("m-2", "subj-a", "RESEARCH", "eu-west", "rec-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_SAME_REGION"));
        // 无授权主体
        migrate("m-3", "subj-none", "RESEARCH", "ap-sg", "rec-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_GRANT_NOT_FOUND"));
        // 全部失败均不产生状态变更
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(0);
    }

    @Test
    void migrationIdempotencyReplayAndConflict() throws Exception {
        setupTwoRegionsWithRecords();
        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1").andExpect(status().isOk());

        // 同 requestId 同参重放：返回首次结果，不产生新迁移
        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetEpoch").value(2));
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(2);

        // 同 requestId 异参：409
        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 跨域查询快照 ----------

    private void setupTwoSubjectsInTwoRegions() throws Exception {
        registerRegion("rr-1", "eu-west").andExpect(status().isOk());
        registerRegion("rr-2", "ap-sg").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH", "eu-west").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-a", "eu-west").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH", "ap-sg").andExpect(status().isOk());
        write("w-2", "subj-b", "RESEARCH", "rec-1", "payload-b", "ap-sg").andExpect(status().isOk());
    }

    @Test
    void crossQueryReturnsFrozenSnapshotWhenAllRegionsAllowed() throws Exception {
        setupTwoSubjectsInTwoRegions();

        MvcResult result = crossQuery("q-1", "\"eu-west\",\"ap-sg\"",
                        objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-b", "RESEARCH", "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").exists())
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.items[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.items[0].epoch").value(1))
                .andExpect(jsonPath("$.items[0].consentVersion").value(1))
                .andExpect(jsonPath("$.items[0].regionCode").value("eu-west"))
                .andExpect(jsonPath("$.items[0].payload").value("payload-a"))
                .andExpect(jsonPath("$.items[1].regionCode").value("ap-sg"))
                .andReturn();

        // 明细查询返回同一冻结快照
        String snapshotId = jsonString(result, "snapshotId");
        mockMvc.perform(get("/api/v1/queries/{snapshotId}", snapshotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.items[1].payload").value("payload-b"));
    }

    @Test
    void crossQueryRejectsWholeRequestWhenAnyRegionNotAllowed() throws Exception {
        setupTwoSubjectsInTwoRegions();

        // subj-b 驻留 ap-sg 不在允许集合内：整次 403，不返回部分结果
        crossQuery("q-1", "\"eu-west\"",
                        objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-b", "RESEARCH", "rec-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_NOT_ALLOWED"));
        // 不生成快照、失败不占键
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'q-1'")).isEqualTo(0);

        // 无区域对象在指定允许集合时同样整次 403
        grant("g-3", "subj-c", "RESEARCH", null).andExpect(status().isOk());
        write("w-3", "subj-c", "RESEARCH", "rec-1", "payload-c", null).andExpect(status().isOk());
        crossQuery("q-2", "\"eu-west\"", objectRef("subj-c", "RESEARCH", "rec-1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_NOT_ALLOWED"));
    }

    @Test
    void crossQueryAfterRevokeReturns410() throws Exception {
        setupTwoSubjectsInTwoRegions();
        mockMvc.perform(post("/api/v1/consents/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv-1\",\"subjectKey\":\"subj-b\",\"purpose\":\"RESEARCH\",\"epoch\":1}"))
                .andExpect(status().isOk());

        crossQuery("q-1", "\"eu-west\",\"ap-sg\"",
                        objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-b", "RESEARCH", "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(0);
    }

    @Test
    void crossQueryWithErasedObjectReturns410() throws Exception {
        setupTwoSubjectsInTwoRegions();
        erase("e-1", "subj-b", "RESEARCH", "rec-1").andExpect(status().isOk());

        crossQuery("q-1", "\"eu-west\",\"ap-sg\"",
                        objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-b", "RESEARCH", "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DATA_ERASED"));
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(0);
    }

    @Test
    void crossQueryPinnedOldEpochAfterMigrationReturns410() throws Exception {
        setupTwoRegionsWithRecords();

        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1", "rec-2").andExpect(status().isOk());

        // 迁移已提交：钉住旧 epoch 的查询整次 410
        crossQuery("q-1", "\"eu-west\",\"ap-sg\"", objectRef("subj-a", "RESEARCH", "rec-1", 1))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 不钉 epoch：使用当前最新代次，区域在集合内则成功
        crossQuery("q-2", "\"ap-sg\"", objectRef("subj-a", "RESEARCH", "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].epoch").value(2))
                .andExpect(jsonPath("$.items[0].regionCode").value("ap-sg"));
    }

    @Test
    void snapshotIsFrozenAgainstLaterMigrationRevokeAndErase() throws Exception {
        setupTwoSubjectsInTwoRegions();

        MvcResult result = crossQuery("q-1", "\"eu-west\",\"ap-sg\"",
                        objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-b", "RESEARCH", "rec-1"))
                .andExpect(status().isOk())
                .andReturn();
        String snapshotId = jsonString(result, "snapshotId");

        // 后续迁移、撤回、清除均不改写已生成快照
        migrate("m-1", "subj-a", "RESEARCH", "ap-sg", "rec-1").andExpect(status().isOk());
        erase("e-1", "subj-b", "RESEARCH", "rec-1").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/queries/{snapshotId}", snapshotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].epoch").value(1))
                .andExpect(jsonPath("$.items[0].regionCode").value("eu-west"))
                .andExpect(jsonPath("$.items[0].payload").value("payload-a"))
                .andExpect(jsonPath("$.items[1].payload").value("payload-b"));
        // 快照证据物理保留且未被改写
        assertThat(count("SELECT COUNT(*) FROM query_snapshot_item WHERE snapshot_id = ?", snapshotId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM query_snapshot_item WHERE payload IS NULL")).isEqualTo(0);
    }

    @Test
    void crossQueryIdempotencyReplayAndConflict() throws Exception {
        setupTwoSubjectsInTwoRegions();
        String objects = objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-b", "RESEARCH", "rec-1");

        MvcResult first = crossQuery("q-1", "\"eu-west\",\"ap-sg\"", objects)
                .andExpect(status().isOk())
                .andReturn();
        String firstSnapshotId = jsonString(first, "snapshotId");

        // 同 requestId 同参重放：返回首次完整快照，不生成新快照
        MvcResult replay = crossQuery("q-1", "\"ap-sg\",\"eu-west\"", objects)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(firstSnapshotId))
                .andReturn();
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(1);

        // 同 requestId 异参：409
        crossQuery("q-1", "\"eu-west\"", objects)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void snapshotHistoryIsStableAndUsesLeftClosedRightOpenInterval() throws Exception {
        setupTwoSubjectsInTwoRegions();

        MvcResult first = crossQuery("q-1", "\"eu-west\"", objectRef("subj-a", "RESEARCH", "rec-1"))
                .andExpect(status().isOk()).andReturn();
        // 可控时钟推进 10 秒，保证两个快照时间点确定且不同
        mutableClock.advanceSeconds(10);
        MvcResult second = crossQuery("q-2", "\"ap-sg\"", objectRef("subj-b", "RESEARCH", "rec-1"))
                .andExpect(status().isOk()).andReturn();
        String firstCreatedAt = jsonString(first, "createdAt");
        String secondCreatedAt = jsonString(second, "createdAt");
        assertThat(firstCreatedAt).isNotEqualTo(secondCreatedAt);

        // 全量历史：按提交顺序稳定返回
        mockMvc.perform(get("/api/v1/queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].snapshotId").value(jsonString(first, "snapshotId")))
                .andExpect(jsonPath("$[0].requestedCount").value(1))
                .andExpect(jsonPath("$[1].snapshotId").value(jsonString(second, "snapshotId")));

        // 左闭右开 [from, to)：to 等于首条生成时间时首条被排除
        mockMvc.perform(get("/api/v1/queries")
                        .param("from", "1970-01-01T00:00:00Z")
                        .param("to", firstCreatedAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // [首条时间, 次条时间)：仅含首条
        mockMvc.perform(get("/api/v1/queries")
                        .param("from", firstCreatedAt)
                        .param("to", secondCreatedAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].snapshotId").value(jsonString(first, "snapshotId")));
        // from 等于次条生成时间时次条包含在内（左闭）
        mockMvc.perform(get("/api/v1/queries")
                        .param("from", secondCreatedAt)
                        .param("to", "9999-12-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].snapshotId").value(jsonString(second, "snapshotId")));
    }

    @Test
    void snapshotDetailNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/queries/{snapshotId}", "no-such-snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void diagnosticReadsDoNotChangeState() throws Exception {
        setupTwoSubjectsInTwoRegions();
        crossQuery("q-1", "\"eu-west\",\"ap-sg\"",
                objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-b", "RESEARCH", "rec-1"))
                .andExpect(status().isOk());

        int grantsBefore = count("SELECT COUNT(*) FROM consent_grant");
        int recordsBefore = count("SELECT COUNT(*) FROM consent_record");
        int idempotencyBefore = count("SELECT COUNT(*) FROM idempotency_request");
        int snapshotsBefore = count("SELECT COUNT(*) FROM query_snapshot");

        mockMvc.perform(get("/api/v1/residency/regions")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/residency/grants")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/residency/migrations")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/queries")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/queries/{snapshotId}", "q-nonexistent"))
                .andExpect(status().isNotFound());

        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(grantsBefore);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(recordsBefore);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request")).isEqualTo(idempotencyBefore);
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(snapshotsBefore);
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentMigrationsKeepExactlyOneActiveEpochPerSuccess() throws Exception {
        setupTwoRegionsWithRecords();
        registerRegion("rr-3", "us-east").andExpect(status().isOk());

        int threads = 4;
        String[] targets = {"ap-sg", "us-east", "ap-sg", "us-east"};
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    residencyService.migrate(new MigrationRequest(
                            "m-concurrent-" + index, "subj-a", Purpose.RESEARCH,
                            targets[index], List.of("rec-1", "rec-2")));
                    return "OK";
                } catch (ApiException ex) {
                    return ex.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int successes = 0;
        for (Future<String> future : futures) {
            String outcome = future.get(30, TimeUnit.SECONDS);
            if ("OK".equals(outcome)) {
                successes++;
            } else {
                // 失败原因必须可区分：并发冲突或源代次已撤回
                assertThat(outcome).isIn("MIGRATION_CONFLICT", "MIGRATION_GRANT_NOT_ACTIVE",
                        "MIGRATION_SAME_REGION");
            }
        }
        pool.shutdown();
        assertThat(successes).isGreaterThanOrEqualTo(1);
        // 最终状态一致：代次数 = 1 + 成功迁移数，且恰好一个有效代次
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-a'"))
                .isEqualTo(1 + successes);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-a' AND status = 'ACTIVE'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(successes);
        // 每条成功迁移的记录都完整复制到其目标代次，不留半成品
        int latestEpoch = jdbc.queryForObject(
                "SELECT MAX(epoch) FROM consent_grant WHERE subject_key = 'subj-a'", Integer.class);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE subject_key = 'subj-a' AND epoch = ?",
                latestEpoch)).isEqualTo(2);
    }

    @Test
    void concurrentCrossQueriesAndMigrationNeverReturnPartialResults() throws Exception {
        setupTwoRegionsWithRecords();

        int queryThreads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(queryThreads + 1);
        CountDownLatch ready = new CountDownLatch(queryThreads + 1);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<CrossQueryResponse>> queryTasks = new ArrayList<>();
        for (int i = 0; i < queryThreads; i++) {
            final int index = i;
            queryTasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    return queryService.query(new CrossQueryRequest(
                            "q-concurrent-" + index,
                            java.util.Set.of("eu-west", "ap-sg"),
                            List.of(new CrossQueryRequest.ObjectRef("subj-a", Purpose.RESEARCH, "rec-1", null),
                                    new CrossQueryRequest.ObjectRef("subj-a", Purpose.RESEARCH, "rec-2", null))));
                } catch (ApiException ex) {
                    // 迁移先提交则查询失败（410/403），允许但不允许部分结果
                    assertThat(ex.getCode()).isIn("CONSENT_REVOKED", "REGION_NOT_ALLOWED");
                    return null;
                }
            });
        }
        Callable<String> migrationTask = () -> {
            ready.countDown();
            start.await();
            residencyService.migrate(new MigrationRequest(
                    "m-concurrent", "subj-a", Purpose.RESEARCH, "ap-sg", List.of("rec-1", "rec-2")));
            return "OK";
        };

        List<Future<CrossQueryResponse>> queryFutures = new ArrayList<>();
        for (Callable<CrossQueryResponse> task : queryTasks) {
            queryFutures.add(pool.submit(task));
        }
        Future<String> migrationFuture = pool.submit(migrationTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successfulQueries = 0;
        for (Future<CrossQueryResponse> future : queryFutures) {
            CrossQueryResponse response = future.get(30, TimeUnit.SECONDS);
            if (response != null) {
                successfulQueries++;
                // 成功的查询必须返回冻结的一致快照：两个对象同代次、同区域
                assertThat(response.itemCount()).isEqualTo(2);
                assertThat(response.items().get(0).epoch()).isEqualTo(response.items().get(1).epoch());
                assertThat(response.items().get(0).regionCode())
                        .isEqualTo(response.items().get(1).regionCode());
            }
        }
        migrationFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 每个成功查询的快照均已完整固化，可重复读取
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(successfulQueries);
        assertThat(count("SELECT COUNT(*) FROM query_snapshot_item")).isEqualTo(successfulQueries * 2);
        // 迁移提交后：旧代次查询 410，新代次查询返回完整快照
        crossQuery("q-after", "\"ap-sg\"",
                        objectRef("subj-a", "RESEARCH", "rec-1") + "," + objectRef("subj-a", "RESEARCH", "rec-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.items[0].epoch").value(2))
                .andExpect(jsonPath("$.items[0].regionCode").value("ap-sg"));
        crossQuery("q-after-old", "\"eu-west\",\"ap-sg\"", objectRef("subj-a", "RESEARCH", "rec-1", 1))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }
}
