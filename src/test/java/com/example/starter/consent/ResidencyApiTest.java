package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.MigrationRequest;
import com.example.starter.consent.dto.QueryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权数据驻留 H2 集成测试：覆盖区域登记与同区域路由、跨对象区域集合查询整批裁决、
 * 不可变查询快照、跨区域原子迁移（422 明细与零半成品）、数据清除、合法保留 UTC 左闭右开窗口、
 * 提交顺序裁决、并发与幂等边界。全部基于真实 H2（MODE=MySQL）事务、唯一约束与回滚验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResidencyApiTest {

    private static final String SUBJECT = "subj-res";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ServiceClock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM query_snapshot_item");
        jdbc.update("DELETE FROM query_snapshot");
        jdbc.update("DELETE FROM legal_hold");
        jdbc.update("DELETE FROM erasure_request");
        jdbc.update("DELETE FROM residency_migration_item");
        jdbc.update("DELETE FROM residency_migration");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM residency_region WHERE region_code <> 'DEFAULT'");
        clock.setClock(Clock.systemUTC());
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    private ResultActions registerRegion(String requestId, String code, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/residency/regions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","regionCode":"%s","regionName":"%s"}
                        """.formatted(requestId, code, name)));
    }

    private ResultActions grant(String requestId, String region) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","region":"%s"}
                        """.formatted(requestId, SUBJECT, region)));
    }

    private ResultActions write(String requestId, String recordKey, String payload, String region) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","recordKey":"%s",
                        "payload":"%s","region":"%s"}
                        """.formatted(requestId, SUBJECT, recordKey, payload, region)));
    }

    private ResultActions revoke(String requestId, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d}
                        """.formatted(requestId, SUBJECT, epoch)));
    }

    private ResultActions migrate(String requestId, int sourceEpoch, String targetRegion, List<String> keys)
            throws Exception {
        return mockMvc.perform(post("/api/v1/residency/migrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new MigrationRequest(requestId, SUBJECT, Purpose.RESEARCH,
                                sourceEpoch, targetRegion, keys))));
    }

    private ResultActions query(String requestId, List<String> regions, List<String> keys) throws Exception {
        return mockMvc.perform(post("/api/v1/records/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new QueryRequest(requestId, SUBJECT, Purpose.RESEARCH, regions, keys))));
    }

    private ResultActions erase(String requestId, int epoch, String recordKey) throws Exception {
        return mockMvc.perform(post("/api/v1/records/erasures")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"RESEARCH","epoch":%d,"recordKey":"%s"}
                        """.formatted(requestId, SUBJECT, epoch, recordKey)));
    }

    private ResultActions hold(int epoch, String recordKey, String reason, Instant from, Instant to) throws Exception {
        return mockMvc.perform(post("/api/v1/legal-holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"subjectKey":"%s","purpose":"RESEARCH","epoch":%d,"recordKey":"%s",
                        "holdReason":"%s","validFrom":"%s","validTo":"%s"}
                        """.formatted(SUBJECT, epoch, recordKey, reason, from, to)));
    }

    private ResultActions read(String recordKey) throws Exception {
        return mockMvc.perform(get("/api/v1/records")
                .param("subjectKey", SUBJECT)
                .param("purpose", "RESEARCH")
                .param("recordKey", recordKey));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private void prepareTwoRegionsWithRecords() throws Exception {
        registerRegion("reg-cn", "CN", "中国区").andExpect(status().isOk());
        registerRegion("reg-eu", "EU", "欧盟区").andExpect(status().isOk());
        grant("g-1", "CN").andExpect(status().isOk());
        write("w-1", "rec-a", "payload-a", "CN").andExpect(status().isOk());
        write("w-2", "rec-b", "payload-b", "CN").andExpect(status().isOk());
    }

    // ---------- 区域登记与授权绑定 ----------

    @Test
    void regionMustBeRegisteredBeforeGrantBindsIt() throws Exception {
        grant("g-1", "CN").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        registerRegion("reg-cn", "CN", "中国区").andExpect(status().isOk());
        grant("g-1", "CN").andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.region").value("CN"));
    }

    @Test
    void duplicateRegionSameNameIsIdempotentDifferentNameConflicts() throws Exception {
        registerRegion("reg-1", "CN", "中国区").andExpect(status().isOk());
        registerRegion("reg-2", "CN", "中国区").andExpect(status().isOk());
        registerRegion("reg-3", "CN", "别的名字")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGION_ALREADY_REGISTERED"));
        assertThat(count("SELECT COUNT(*) FROM residency_region WHERE region_code = 'CN'")).isEqualTo(1);
    }

    @Test
    void invalidRegionCodeReturns400() throws Exception {
        registerRegion("reg-1", "cn", "小写").andExpect(status().isBadRequest());
    }

    @Test
    void activeEpochRegionCannotChangeButNewEpochAfterRevokeCan() throws Exception {
        registerRegion("reg-cn", "CN", "中国区").andExpect(status().isOk());
        registerRegion("reg-eu", "EU", "欧盟区").andExpect(status().isOk());
        grant("g-1", "CN").andExpect(status().isOk());
        // 同一 epoch 的区域不可直接修改
        grant("g-2", "EU").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_MISMATCH"));
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(1);
        revoke("r-1", 1).andExpect(status().isOk());
        grant("g-3", "EU").andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.region").value("EU"));
    }

    // ---------- 同区域写入路由 ----------

    @Test
    void writeMustCarrySameRegionRouteAsEpoch() throws Exception {
        registerRegion("reg-cn", "CN", "中国区").andExpect(status().isOk());
        registerRegion("reg-eu", "EU", "欧盟区").andExpect(status().isOk());
        grant("g-1", "CN").andExpect(status().isOk());
        write("w-1", "rec-a", "payload-a", "EU")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_MISMATCH"));
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(0);
        write("w-1", "rec-a", "payload-a", "CN").andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("CN"))
                .andExpect(jsonPath("$.consentVersion").value(1));
        // 错区域重放同一 requestId 也不能借幂等绕过：仍 403
        write("w-1", "rec-a", "payload-a", "EU")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_MISMATCH"));
    }

    // ---------- 跨对象查询：整批裁决 ----------

    @Test
    void crossObjectQueryReturnsSnapshotOnlyWhenAllObjectsRoutedWithinAllowedRegions() throws Exception {
        prepareTwoRegionsWithRecords();
        query("q-1", List.of("CN"), List.of("rec-b", "rec-a"))
                .andExpect(status().isOk())
                // 排序稳定：去重后按 record_key 字典序
                .andExpect(jsonPath("$.objects[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$.objects[0].epoch").value(1))
                .andExpect(jsonPath("$.objects[0].region").value("CN"))
                .andExpect(jsonPath("$.objects[0].consentVersion").value(1))
                .andExpect(jsonPath("$.objects[1].recordKey").value("rec-b"))
                .andExpect(jsonPath("$.objectCount").value(2))
                .andExpect(jsonPath("$.regions[0]").value("CN"));
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM query_snapshot_item")).isEqualTo(2);
    }

    @Test
    void anyObjectRegionNotAllowedFailsWholeQuery403WithNoPartialSnapshot() throws Exception {
        prepareTwoRegionsWithRecords();
        query("q-1", List.of("EU"), List.of("rec-a", "rec-b"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_NOT_ALLOWED"));
        // 不得返回部分结果，也不得留下快照
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM query_snapshot_item")).isEqualTo(0);
    }

    @Test
    void missingObjectFailsWholeQuery404() throws Exception {
        prepareTwoRegionsWithRecords();
        query("q-1", List.of("CN"), List.of("rec-a", "rec-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(0);
    }

    @Test
    void queryRejectsUnregisteredAllowedRegion() throws Exception {
        prepareTwoRegionsWithRecords();
        query("q-1", List.of("US"), List.of("rec-a"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
    }

    @Test
    void queryAfterRevokeReturns410AndCannotUseLegalHoldToBypass() throws Exception {
        prepareTwoRegionsWithRecords();
        Instant now = clock.now();
        hold(1, "rec-a", "CASE-1", now, now.plusSeconds(3600)).andExpect(status().isCreated());
        revoke("r-1", 1).andExpect(status().isOk());
        // 单对象可受限读取
        read("rec-a").andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true));
        // 跨对象查询不得借合法保留绕过
        query("q-1", List.of("CN"), List.of("rec-a", "rec-b"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(0);
    }

    // ---------- 查询快照不可变与幂等 ----------

    @Test
    void snapshotFreezesEpochRegionVersionAndPayloadAcrossMigrationRevokeErasure() throws Exception {
        prepareTwoRegionsWithRecords();
        query("q-1", List.of("CN"), List.of("rec-a", "rec-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[0].payload").value("payload-a"));
        // 迁移先提交：旧 epoch 已撤回
        migrate("m-1", 1, "EU", List.of("rec-a", "rec-b")).andExpect(status().isOk());
        // 同参重放返回首次完整快照（CN/epoch1/原内容），不被迁移改写
        query("q-1", List.of("CN"), List.of("rec-b", "rec-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[0].region").value("CN"))
                .andExpect(jsonPath("$.objects[0].epoch").value(1))
                .andExpect(jsonPath("$.objects[0].consentVersion").value(1))
                .andExpect(jsonPath("$.objects[0].payload").value("payload-a"));
        // 诊断通道读取同一冻结快照
        mockMvc.perform(get("/api/v1/query-snapshots/q-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[1].region").value("CN"));
        // 清除新 epoch 的副本也不影响旧快照
        erase("e-1", 2, "rec-a").andExpect(status().isOk());
        query("q-1", List.of("CN"), List.of("rec-a", "rec-b"))
                .andExpect(jsonPath("$.objects[0].payload").value("payload-a"));
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(1);
        // 迁移后用新区域做旧请求之外的查询：未清除对象副本已在 EU，按 CN 查询整次 403
        query("q-2", List.of("CN"), List.of("rec-b"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_NOT_ALLOWED"));
        query("q-3", List.of("EU"), List.of("rec-a", "rec-b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_ERASED"));
        query("q-4", List.of("EU"), List.of("rec-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[0].epoch").value(2))
                .andExpect(jsonPath("$.objects[0].region").value("EU"))
                .andExpect(jsonPath("$.objects[0].consentVersion").value(2));
    }

    @Test
    void sameSnapshotRequestIdWithDifferentParamsReturns409() throws Exception {
        prepareTwoRegionsWithRecords();
        query("q-1", List.of("CN"), List.of("rec-a")).andExpect(status().isOk());
        query("q-1", List.of("CN"), List.of("rec-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        query("q-1", List.of("CN", "EU"), List.of("rec-a"))
                .andExpect(status().isConflict());
    }

    @Test
    void failedQueryDoesNotConsumeRequestId() throws Exception {
        prepareTwoRegionsWithRecords();
        // 先失败（区域不允许）
        query("q-1", List.of("EU"), List.of("rec-a")).andExpect(status().isForbidden());
        // 同 requestId 改合法参数后成功
        query("q-1", List.of("CN"), List.of("rec-a")).andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM query_snapshot")).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalQueriesCommitExactlyOneSnapshot() throws Exception {
        prepareTwoRegionsWithRecords();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.queryRecords(new QueryRequest(
                        "q-concurrent", SUBJECT, Purpose.RESEARCH, List.of("CN"), List.of("rec-a")))
                        .objectCount();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM query_snapshot WHERE snapshot_id = 'q-concurrent'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM query_snapshot_item WHERE snapshot_id = 'q-concurrent'")).isEqualTo(1);
    }

    // ---------- 跨区域迁移：原子提交与 422 ----------

    @Test
    void migrationCreatesNewEpochCopiesObjectsAtVersionPlusOneAndRevokesSource() throws Exception {
        prepareTwoRegionsWithRecords();
        migrate("m-1", 1, "EU", List.of("rec-b", "rec-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceEpoch").value(1))
                .andExpect(jsonPath("$.sourceRegion").value("CN"))
                .andExpect(jsonPath("$.targetEpoch").value(2))
                .andExpect(jsonPath("$.targetRegion").value("EU"))
                .andExpect(jsonPath("$.requestedObjectCount").value(2))
                .andExpect(jsonPath("$.migratedObjectCount").value(2))
                .andExpect(jsonPath("$.delta").value(0))
                .andExpect(jsonPath("$.recordKeys[0]").value("rec-a"))
                .andExpect(jsonPath("$.status").value("COMMITTED"));
        // 源 epoch 已撤回且撤回原因为迁移
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'REVOKED'"
                + " AND revoke_reason = 'MIGRATION'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 2 AND status = 'ACTIVE'"
                + " AND region_code = 'EU'")).isEqualTo(1);
        // 副本驻留 EU 且授权版本 +1
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2 AND region_code = 'EU'"
                + " AND consent_version = 2")).isEqualTo(2);
        // 源数据仍物理保留
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1")).isEqualTo(2);
        // 明细可查
        mockMvc.perform(get("/api/v1/residency/migrations/m-1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$[0].targetRegion").value("EU"));
    }

    @Test
    void migrationRejectsBatch422WhenObjectErasedMissingWrongEpochOrWrongRegionWithoutPartialCopies() throws Exception {
        prepareTwoRegionsWithRecords();
        erase("e-1", 1, "rec-b").andExpect(status().isOk());

        // 含已清除对象：422 且逐条原因可区分
        migrate("m-bad-1", 1, "EU", List.of("rec-a", "rec-b"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_REJECTED"))
                .andExpect(jsonPath("$.details[?(@.code == 'RECORD_ERASED')].target").value(hasItem("rec-b")));
        assertNoMigrationSideEffects();

        // 含不存在对象
        migrate("m-bad-2", 1, "EU", List.of("rec-a", "rec-nope"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[?(@.code == 'OBJECT_NOT_FOUND')].target").value(hasItem("rec-nope")));
        assertNoMigrationSideEffects();

        // 目标区域未登记 / 与源相同
        migrate("m-bad-3", 1, "US", List.of("rec-a"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[?(@.code == 'REGION_NOT_FOUND')]").exists());
        migrate("m-bad-4", 1, "CN", List.of("rec-a"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[?(@.code == 'MIGRATION_TARGET_SAME_AS_SOURCE')]").exists());
        assertNoMigrationSideEffects();

        // 撤回旧 epoch 并在新一代写入对象，再让迁移集合混入其他 epoch 的对象
        revoke("r-1", 1).andExpect(status().isOk());
        grant("g-2", "CN").andExpect(status().isOk());
        write("w-3", "rec-c", "payload-c", "CN").andExpect(status().isOk());
        // rec-a 属于 epoch1，不属于源 epoch2
        migrate("m-bad-5", 2, "EU", List.of("rec-c", "rec-a"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[?(@.code == 'EPOCH_MISMATCH')].target").value(hasItem("rec-a")));
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 3")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(0);
    }

    private void assertNoMigrationSideEffects() {
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM residency_migration_item")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 2")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void migrationSourceNotCurrentFails422() throws Exception {
        prepareTwoRegionsWithRecords();
        revoke("r-1", 1).andExpect(status().isOk());
        grant("g-2", "CN").andExpect(status().isOk());
        migrate("m-1", 1, "EU", List.of("rec-a"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[?(@.code == 'SOURCE_NOT_CURRENT')]").exists());
    }

    @Test
    void migrationIdempotentReplayAndFailureDoesNotConsumeKey() throws Exception {
        prepareTwoRegionsWithRecords();
        migrate("m-1", 1, "EU", List.of("rec-a")).andExpect(status().isOk());
        // 同参重放
        migrate("m-1", 1, "EU", List.of("rec-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetEpoch").value(2));
        // 异参 409
        migrate("m-1", 1, "EU", List.of("rec-b"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(1);

        // 失败不占键：m-bad 先因源已失效而失败，换合法场景后仍可复用该 requestId
        migrate("m-bad", 1, "EU", List.of("rec-b"))
                .andExpect(status().isUnprocessableEntity());
        revoke("r-2", 2).andExpect(status().isOk());
        grant("g-3", "EU").andExpect(status().isOk());
        write("w-4", "rec-b", "payload-b2", "EU").andExpect(status().isOk());
        registerRegion("reg-us", "US", "美国区").andExpect(status().isOk());
        migrate("m-bad", 3, "US", List.of("rec-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetEpoch").value(4));
    }

    @Test
    void concurrentMigrationsAdjudicateByCommitOrderWithExactlyOneWinner() throws Exception {
        prepareTwoRegionsWithRecords();
        registerRegion("reg-us", "US", "美国区").andExpect(status().isOk());
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> {
            ready.countDown();
            start.await();
            return migrationOutcome(new MigrationRequest(
                    "m-eu", SUBJECT, Purpose.RESEARCH, 1, "EU", List.of("rec-a", "rec-b")));
        });
        tasks.add(() -> {
            ready.countDown();
            start.await();
            return migrationOutcome(new MigrationRequest(
                    "m-us", SUBJECT, Purpose.RESEARCH, 1, "US", List.of("rec-a", "rec-b")));
        });
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        // 真实锁竞争不预设赢家：恰好一个提交、一个 422
        assertThat(outcomes).containsExactlyInAnyOrder("COMMITTED", "REJECTED");
        // 最终状态：恰好一个新 epoch、一份副本集合、一条迁移单，源只撤回一次
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM residency_migration")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM residency_migration_item")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2")).isEqualTo(2);
        String targetRegion = jdbc.queryForObject(
                "SELECT target_region FROM residency_migration", String.class);
        assertThat(targetRegion).isIn("EU", "US");
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2 AND region_code = ?",
                targetRegion)).isEqualTo(2);
    }

    private String migrationOutcome(MigrationRequest request) {
        try {
            consentService.migrate(request);
            return "COMMITTED";
        } catch (ApiException ex) {
            return ex.getStatus().value() == 422 ? "REJECTED" : "UNEXPECTED_" + ex.getStatus().value();
        }
    }

    // ---------- 数据清除 ----------

    @Test
    void erasureNullsPayloadKeepsHistoryAndFreezesNoRewrite() throws Exception {
        prepareTwoRegionsWithRecords();
        query("q-1", List.of("CN"), List.of("rec-a")).andExpect(status().isOk());
        erase("e-1", 1, "rec-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERASED"))
                .andExpect(jsonPath("$.region").value("CN"));
        // payload 为 NULL，驻留区域与版本历史保留（null 语义稳定）
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-a'"
                + " AND payload IS NULL AND region_code = 'CN' AND consent_version = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM erasure_request WHERE status = 'ERASED'")).isEqualTo(1);
        // 普通读取不可恢复内容
        read("rec-a").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_ERASED"));
        // 已生成快照内容不改写
        mockMvc.perform(get("/api/v1/query-snapshots/q-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[0].payload").value("payload-a"));
        // 历史诊断仍可查
        mockMvc.perform(get("/api/v1/records/history")
                        .param("subjectKey", SUBJECT)
                        .param("purpose", "RESEARCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$[0].payload").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].regionCode").value("CN"));
    }

    @Test
    void erasureReplayReturnsFirstResultAndSecondErasureConflicts() throws Exception {
        prepareTwoRegionsWithRecords();
        erase("e-1", 1, "rec-a").andExpect(status().isOk());
        erase("e-1", 1, "rec-a").andExpect(status().isOk());
        erase("e-2", 1, "rec-a")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_ALREADY_ERASED"));
        erase("e-3", 1, "rec-nope")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM erasure_request")).isEqualTo(1);
    }

    // ---------- 合法保留：UTC 左闭右开 ----------

    @Test
    void legalHoldRestrictedReadFollowsUtcHalfOpenWindowBoundaries() throws Exception {
        registerRegion("reg-cn", "CN", "中国区").andExpect(status().isOk());
        grant("g-1", "CN").andExpect(status().isOk());
        write("w-1", "rec-a", "payload-a", "CN").andExpect(status().isOk());
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        hold(1, "rec-a", "CASE-1", base, base.plusSeconds(100)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationSeconds").value(100));
        revoke("r-1", 1).andExpect(status().isOk());

        // 窗口起点（含）：受限可读
        clock.setClock(Clock.fixed(base, java.time.ZoneOffset.UTC));
        read("rec-a").andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true))
                .andExpect(jsonPath("$.payload").value("payload-a"));
        // 窗口内
        clock.setClock(Clock.fixed(base.plusSeconds(99), java.time.ZoneOffset.UTC));
        read("rec-a").andExpect(status().isOk());
        // 窗口终点（不含）：410
        clock.setClock(Clock.fixed(base.plusSeconds(100), java.time.ZoneOffset.UTC));
        read("rec-a").andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 窗口之前：410
        clock.setClock(Clock.fixed(base.minusSeconds(1), java.time.ZoneOffset.UTC));
        read("rec-a").andExpect(status().isGone());
    }

    @Test
    void zeroLengthHoldWindowNeverAllowsRead() throws Exception {
        registerRegion("reg-cn", "CN", "中国区").andExpect(status().isOk());
        grant("g-1", "CN").andExpect(status().isOk());
        write("w-1", "rec-a", "payload-a", "CN").andExpect(status().isOk());
        Instant base = Instant.parse("2026-02-02T08:00:00Z");
        hold(1, "rec-a", "CASE-ZERO", base, base).andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationSeconds").value(0));
        revoke("r-1", 1).andExpect(status().isOk());
        clock.setClock(Clock.fixed(base, java.time.ZoneOffset.UTC));
        read("rec-a").andExpect(status().isGone());
    }

    @Test
    void invalidHoldWindowReturns400AndErasedRecordCannotBeReadViaHold() throws Exception {
        registerRegion("reg-cn", "CN", "中国区").andExpect(status().isOk());
        grant("g-1", "CN").andExpect(status().isOk());
        write("w-1", "rec-a", "payload-a", "CN").andExpect(status().isOk());
        Instant base = Instant.parse("2026-03-03T00:00:00Z");
        hold(1, "rec-a", "CASE-BAD", base.plusSeconds(10), base).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LEGAL_HOLD_WINDOW"));
        hold(1, "rec-a", "CASE-1", base, base.plusSeconds(500)).andExpect(status().isCreated());
        erase("e-1", 1, "rec-a").andExpect(status().isOk());
        clock.setClock(Clock.fixed(base.plusSeconds(100), java.time.ZoneOffset.UTC));
        // 即使保留窗口有效，已清除内容也不可恢复
        read("rec-a").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_ERASED"));
    }

    // ---------- 提交顺序：迁移先提交 vs 查询先成功 ----------

    @Test
    void queryFirstThenMigrationKeepsFrozenConsistentSnapshot() throws Exception {
        prepareTwoRegionsWithRecords();
        // 查询先成功
        query("q-1", List.of("CN"), List.of("rec-a", "rec-b")).andExpect(status().isOk());
        // 迁移后提交
        migrate("m-1", 1, "EU", List.of("rec-a", "rec-b")).andExpect(status().isOk());
        // 新对象在 EU 新版本，旧快照仍一致冻结在 CN/epoch1（另一个用例已断言），新查询按新 epoch 裁决
        query("q-2", List.of("EU"), List.of("rec-a", "rec-b")).andExpect(status().isOk())
                .andExpect(jsonPath("$.objects[0].epoch").value(2))
                .andExpect(jsonPath("$.objects[0].consentVersion").value(2));
    }

    // ---------- 历史与诊断只读 ----------

    @Test
    void historyEndpointsRemainReadOnlyAndStable() throws Exception {
        prepareTwoRegionsWithRecords();
        migrate("m-1", 1, "EU", List.of("rec-a")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/consents/grants")
                        .param("subjectKey", SUBJECT)
                        .param("purpose", "RESEARCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].epoch").value(1))
                .andExpect(jsonPath("$[0].revokeReason").value("MIGRATION"))
                .andExpect(jsonPath("$[1].epoch").value(2))
                .andExpect(jsonPath("$[1].regionCode").value("EU"));
        mockMvc.perform(get("/api/v1/residency/migrations")
                        .param("subjectKey", SUBJECT)
                        .param("purpose", "RESEARCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].migrationId").value("m-1"));
        mockMvc.perform(get("/api/v1/residency/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.regionCode == 'CN')]").exists());
        mockMvc.perform(get("/api/v1/query-snapshots/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }

    private static org.hamcrest.Matcher<java.lang.Iterable<? super String>> hasItem(String value) {
        return org.hamcrest.Matchers.hasItem(value);
    }
}
