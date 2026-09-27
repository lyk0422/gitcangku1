package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.MigrateRequest;
import com.example.starter.consent.dto.BatchQueryRequest;

/**
 * 授权数据驻留 API 集成测试：在真实 H2（MODE=MySQL）上覆盖区域登记/绑定、写入路由、
 * 迁移原子性、跨对象查询与快照冻结、清除、幂等与并发提交顺序裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResidencyApiTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ResidencyService residencyService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_snapshot_item");
        jdbc.update("DELETE FROM consent_snapshot");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM residency_region");
        when(clock.instant()).thenReturn(T0);
    }

    // ---------- 工具方法 ----------

    private ResultActions registerRegion(String requestId, String regionCode) throws Exception {
        return mockMvc.perform(post("/api/v1/residency/regions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","regionCode":"%s"}
                        """.formatted(requestId, regionCode)));
    }

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions bind(String requestId, String subjectKey, String purpose,
                               int epoch, String regionCode) throws Exception {
        return mockMvc.perform(post("/api/v1/residency/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d,"regionCode":"%s"}
                        """.formatted(requestId, subjectKey, purpose, epoch, regionCode)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload, String regionCode) throws Exception {
        String regionJson = regionCode == null ? "null" : ("\"" + regionCode + "\"");
        String body = "{\"requestId\":\"" + requestId + "\",\"subjectKey\":\"" + subjectKey
                + "\",\"purpose\":\"" + purpose + "\",\"recordKey\":\"" + recordKey
                + "\",\"payload\":\"" + payload + "\",\"regionCode\":" + regionJson + "}";
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions revoke(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions erase(String requestId, String subjectKey, String purpose, String recordKey)
            throws Exception {
        return mockMvc.perform(post("/api/v1/consents/erasures")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey)));
    }

    private ResultActions migrate(String requestId, String subjectKey, String purpose,
                                  String targetRegion, List<String> recordKeys) throws Exception {
        String keys = recordKeys.stream().map(key -> "\"" + key + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return mockMvc.perform(post("/api/v1/residency/migrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","targetRegion":"%s","recordKeys":[%s]}
                        """.formatted(requestId, subjectKey, purpose, targetRegion, keys)));
    }

    private ResultActions query(String requestId, String subjectKey, String purpose,
                                List<String> recordKeys, List<String> allowedRegions) throws Exception {
        String keys = recordKeys.stream().map(key -> "\"" + key + "\"").reduce((a, b) -> a + "," + b).orElse("");
        String regions = allowedRegions.stream()
                .map(region -> region == null ? "null" : ("\"" + region + "\""))
                .reduce((a, b) -> a + "," + b).orElse("");
        return mockMvc.perform(post("/api/v1/residency/queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKeys":[%s],"allowedRegions":[%s]}
                        """.formatted(requestId, subjectKey, purpose, keys, regions)));
    }

    private ResultActions getSnapshot(String snapshotId) throws Exception {
        return mockMvc.perform(get("/api/v1/residency/snapshots/" + snapshotId));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private void setupEuEpochWithTwoRecords() throws Exception {
        registerRegion("reg-eu", "EU").andExpect(status().isOk());
        registerRegion("reg-us", "US").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        bind("b-1", "subj-a", "RESEARCH", 1, "EU").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1", "EU").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2", "EU").andExpect(status().isOk());
    }

    // ---------- 区域登记 ----------

    @Test
    void registerRegionSucceedsAndListsRegions() throws Exception {
        registerRegion("reg-1", "EU")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value("EU"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-28T10:00:00Z"));
        mockMvc.perform(get("/api/v1/residency/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].regionCode").value("EU"));
        assertThat(count("SELECT COUNT(*) FROM residency_region WHERE region_code = 'EU'")).isEqualTo(1);
    }

    @Test
    void registerSameRegionWithNewRequestIdIsIdempotent() throws Exception {
        registerRegion("reg-1", "EU").andExpect(status().isOk());
        registerRegion("reg-2", "EU")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").value("2026-09-28T10:00:00Z"));
        assertThat(count("SELECT COUNT(*) FROM residency_region")).isEqualTo(1);
    }

    @Test
    void registerRegionSameRequestIdDifferentRegionReturns409() throws Exception {
        registerRegion("reg-1", "EU").andExpect(status().isOk());
        registerRegion("reg-1", "US")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 区域绑定与写入路由 ----------

    @Test
    void bindRegionToEpochSucceedsAndIsImmutable() throws Exception {
        registerRegion("reg-eu", "EU").andExpect(status().isOk());
        registerRegion("reg-us", "US").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());

        bind("b-1", "subj-a", "RESEARCH", 1, "EU")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value("EU"));
        // 同一代次绑定其他区域返回 409，区域不可直接修改
        bind("b-2", "subj-a", "RESEARCH", 1, "US")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGION_ALREADY_BOUND"));
        // 重复绑定相同区域幂等成功
        bind("b-3", "subj-a", "RESEARCH", 1, "EU").andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND region_code = 'EU'")).isEqualTo(1);
    }

    @Test
    void bindUnregisteredRegionReturns400AndMissingEpochReturns404() throws Exception {
        registerRegion("reg-eu", "EU").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        bind("b-1", "subj-a", "RESEARCH", 1, "APAC")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_NOT_REGISTERED"));
        bind("b-2", "subj-a", "RESEARCH", 9, "EU")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GRANT_NOT_FOUND"));
    }

    @Test
    void writeMustCarrySameRegionRouteAfterBinding() throws Exception {
        registerRegion("reg-eu", "EU").andExpect(status().isOk());
        registerRegion("reg-us", "US").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        bind("b-1", "subj-a", "RESEARCH", 1, "EU").andExpect(status().isOk());

        // 未携带区域路由
        write("w-0", "subj-a", "RESEARCH", "rec-x", "p", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_ROUTE_FORBIDDEN"));
        // 携带其他区域
        write("w-0", "subj-a", "RESEARCH", "rec-x", "p", "US")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_ROUTE_FORBIDDEN"));
        // 相同区域成功且路由落库
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1", "EU")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value("EU"));
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE region_code = 'EU'")).isEqualTo(1);
    }

    @Test
    void writeBeforeBindingCarriesRegisteredRegionOrRejectsUnknownRegion() throws Exception {
        registerRegion("reg-eu", "EU").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 携带未登记区域：400，且不写数据
        write("w-0", "subj-a", "RESEARCH", "rec-x", "p", "APAC")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_NOT_REGISTERED"));
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1", "EU")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value("EU"));
        // 未绑定代次也可不携带区域，落库 NULL 保持 null 语义
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCode").value(org.hamcrest.Matchers.nullValue()));
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE region_code IS NULL")).isEqualTo(1);
    }

    // ---------- 迁移主流程 ----------

    @Test
    void migrationCreatesNewEpochCopiesObjectsAndRevokesSourceAtomically() throws Exception {
        setupEuEpochWithTwoRecords();

        migrate("m-1", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceEpoch").value(1))
                .andExpect(jsonPath("$.targetEpoch").value(2))
                .andExpect(jsonPath("$.sourceRegion").value("EU"))
                .andExpect(jsonPath("$.targetRegion").value("US"))
                .andExpect(jsonPath("$.requestedCount").value(2))
                .andExpect(jsonPath("$.copiedCount").value(2));

        // 源代次撤回、新代次有效且驻留 US、授权版本递增
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 2 AND status = 'ACTIVE'"
                + " AND region_code = 'US' AND consent_version = 2")).isEqualTo(1);
        // 复制语义：源和目标各有一份，新副本区域为 US
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2 AND region_code = 'US'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1 AND region_code = 'EU'")).isEqualTo(2);
        // 迁移先提交：旧代查询 410，新代查询返回新副本
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH").param("recordKey", "rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.regionCode").value("US"))
                .andExpect(jsonPath("$.payload").value("payload-1"));
    }

    // ---------- 迁移失败分支：422 且不留半成品 ----------

    @Test
    void migrationWithErasedObjectReturns422AndLeavesNoPartialCopy() throws Exception {
        setupEuEpochWithTwoRecords();
        erase("e-1", "subj-a", "RESEARCH", "rec-2").andExpect(status().isOk());

        migrate("m-bad", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_OBJECT_ERASED"));
        assertNoMigrationSideEffects();
    }

    @Test
    void migrationWithMissingObjectReturns422AndLeavesNoPartialCopy() throws Exception {
        setupEuEpochWithTwoRecords();
        migrate("m-bad", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-nope"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_OBJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("要求数量: 2")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("实际数量: 1")));
        assertNoMigrationSideEffects();
    }

    @Test
    void migrationWithForeignRegionObjectReturns422() throws Exception {
        registerRegion("reg-eu", "EU").andExpect(status().isOk());
        registerRegion("reg-us", "US").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 代次未绑定区域时先写一条 US 路由对象，再绑定 EU 并写 EU 对象
        write("w-0", "subj-a", "RESEARCH", "rec-us", "p-us", "US").andExpect(status().isOk());
        bind("b-1", "subj-a", "RESEARCH", 1, "EU").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "p-eu", "EU").andExpect(status().isOk());

        migrate("m-bad", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-us"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_OBJECT_REGION_MISMATCH"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("对象区域: US")));
        assertNoMigrationSideEffects();
    }

    @Test
    void migrationToUnregisteredOrSameRegionReturns422() throws Exception {
        setupEuEpochWithTwoRecords();
        migrate("m-1", "subj-a", "RESEARCH", "APAC", List.of("rec-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_TARGET_NOT_REGISTERED"));
        migrate("m-2", "subj-a", "RESEARCH", "EU", List.of("rec-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_OBJECT_REGION_MISMATCH"));
        assertNoMigrationSideEffects();
    }

    @Test
    void migrationWithRevokedSourceReturns410AndWithDuplicateKeysReturns422() throws Exception {
        setupEuEpochWithTwoRecords();
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        migrate("m-1", "subj-a", "RESEARCH", "US", List.of("rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("MIGRATION_SOURCE_REVOKED"));

        // 重新授权后再测重复对象集合
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        bind("b-2", "subj-a", "RESEARCH", 2, "EU").andExpect(status().isOk());
        write("w-3", "subj-a", "RESEARCH", "rec-1", "payload-1", "EU").andExpect(status().isOk());
        migrate("m-2", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MIGRATION_DUPLICATE_OBJECT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("差额")));
    }

    @Test
    void failedMigrationDoesNotConsumeRequestId() throws Exception {
        setupEuEpochWithTwoRecords();
        migrate("m-retry", "subj-a", "RESEARCH", "US", List.of("rec-nope"))
                .andExpect(status().isUnprocessableEntity());
        // 失败不占用 requestId：同一 requestId 改正参数后成功
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'm-retry'")).isZero();
        migrate("m-retry", "subj-a", "RESEARCH", "US", List.of("rec-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetEpoch").value(2));
    }

    @Test
    void migrationReplaySameRequestIdReturnsOriginalResult() throws Exception {
        setupEuEpochWithTwoRecords();
        migrate("m-1", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-2")).andExpect(status().isOk());
        migrate("m-1", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetEpoch").value(2))
                .andExpect(jsonPath("$.copiedCount").value(2));
        // 异参 409
        migrate("m-1", "subj-a", "RESEARCH", "US", List.of("rec-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(2);
    }

    private void assertNoMigrationSideEffects() {
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2")).isZero();
    }

    // ---------- 跨对象查询与快照 ----------

    @Test
    void batchQueryWithAllowedRegionsReturnsFrozenSnapshotForAllObjects() throws Exception {
        setupEuEpochWithTwoRecords();
        query("q-1", "subj-a", "RESEARCH", List.of("rec-2", "rec-1"), List.of("EU", "US"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                // 按记录键升序，顺序稳定
                .andExpect(jsonPath("$.items[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.items[0].epoch").value(1))
                .andExpect(jsonPath("$.items[0].regionCode").value("EU"))
                .andExpect(jsonPath("$.items[0].consentVersion").value(1))
                .andExpect(jsonPath("$.items[0].payload").value("payload-1"))
                .andExpect(jsonPath("$.items[1].recordKey").value("rec-2"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-28T10:00:00Z"))
                .andDo(result -> assertThat(result.getResponse().getContentAsString()).contains("snapshotId"));
        assertThat(count("SELECT COUNT(*) FROM consent_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_snapshot_item")).isEqualTo(2);
    }

    @Test
    void batchQueryRejectsWholeRequestWhenAnyObjectRegionNotAllowed() throws Exception {
        setupEuEpochWithTwoRecords();
        query("q-bad", "subj-a", "RESEARCH", List.of("rec-1", "rec-2"), List.of("US"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rec-1")));
        // 不返回部分结果，也不生成快照
        assertThat(count("SELECT COUNT(*) FROM consent_snapshot")).isZero();
        assertThat(count("SELECT COUNT(*) FROM consent_snapshot_item")).isZero();
    }

    @Test
    void batchQueryWithNullRegionObjectAndNoNullAllowedReturns403() throws Exception {
        registerRegion("reg-eu", "EU").andExpect(status().isOk());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-null", "p", null).andExpect(status().isOk());
        query("q-bad", "subj-a", "RESEARCH", List.of("rec-null"), List.of("EU"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGION_NOT_ALLOWED"));
    }

    @Test
    void batchQueryAfterRevokeReturns410AndCannotBypassRestrictedRead() throws Exception {
        setupEuEpochWithTwoRecords();
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        query("q-bad", "subj-a", "RESEARCH", List.of("rec-1", "rec-2"), List.of("EU"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        assertThat(count("SELECT COUNT(*) FROM consent_snapshot")).isZero();
    }

    @Test
    void batchQueryWithErasedOrMissingObjectReturns410Or404() throws Exception {
        setupEuEpochWithTwoRecords();
        erase("e-1", "subj-a", "RESEARCH", "rec-2").andExpect(status().isOk());
        query("q-1", "subj-a", "RESEARCH", List.of("rec-1", "rec-2"), List.of("EU"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RECORD_ERASED"));
        query("q-2", "subj-a", "RESEARCH", List.of("rec-1", "rec-nope"), List.of("EU"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM consent_snapshot")).isZero();
    }

    @Test
    void queryReplaySameRequestIdReturnsFirstFullSnapshotAndDifferentParams409() throws Exception {
        setupEuEpochWithTwoRecords();
        String firstBody = query("q-1", "subj-a", "RESEARCH", List.of("rec-1", "rec-2"), List.of("EU"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String snapshotId = com.jayway.jsonpath.JsonPath.read(firstBody, "$.snapshotId");

        // 同参（集合顺序不同语义相同）重放返回首次完整快照
        query("q-1", "subj-a", "RESEARCH", List.of("rec-2", "rec-1"), List.of("EU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId));
        // 异参 409
        query("q-1", "subj-a", "RESEARCH", List.of("rec-1"), List.of("EU"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM consent_snapshot")).isEqualTo(1);
    }

    @Test
    void snapshotRemainsFrozenAfterLaterMigrationRevokeAndErasure() throws Exception {
        setupEuEpochWithTwoRecords();
        when(clock.instant()).thenReturn(Instant.parse("2026-09-28T11:00:00Z"));
        String body = query("q-1", "subj-a", "RESEARCH", List.of("rec-1", "rec-2"), List.of("EU"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String snapshotId = com.jayway.jsonpath.JsonPath.read(body, "$.snapshotId");

        // 随后迁移到 US、撤回源代、再清除数据
        migrate("m-1", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-2")).andExpect(status().isOk());
        erase("e-1", "subj-a", "RESEARCH", "rec-1").andExpect(status().isOk());

        // 已生成快照不改写：仍是 epoch 1 / EU / 原 payload
        getSnapshot(snapshotId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.items[0].regionCode").value("EU"))
                .andExpect(jsonPath("$.items[0].payload").value("payload-1"))
                .andExpect(jsonPath("$.items[1].payload").value("payload-2"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-28T11:00:00Z"));
    }

    @Test
    void getUnknownSnapshotReturns404() throws Exception {
        getSnapshot("nonexistent-snapshot-id")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }

    // ---------- 数据清除 ----------

    @Test
    void eraseNullifiesPayloadAcrossEpochsAndSurvivesRevokeAndReplay() throws Exception {
        setupEuEpochWithTwoRecords();
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 撤回后仍可执行合规清除，不要求授权有效
        when(clock.instant()).thenReturn(Instant.parse("2026-09-28T12:00:00Z"));
        erase("e-1", "subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(1))
                .andExpect(jsonPath("$.erasedAt").value("2026-09-28T12:00:00Z"));

        // 内容已 NULL 且清除时间落库，行仍保留
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-1'"
                + " AND payload IS NULL AND erased_at IS NOT NULL")).isEqualTo(1);
        // 同 requestId 重放返回首次结果（实际值 1，不重复清除）
        erase("e-1", "subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erasedCount").value(1));
        // 清除不存在的记录键：404 且不占键
        erase("e-2", "subj-a", "RESEARCH", "rec-nope")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'e-2'")).isZero();
    }

    // ---------- 历史与诊断（只读） ----------

    @Test
    void snapshotHistoryUsesHalfOpenUtcIntervalAndDiagnosticsShowEpochState() throws Exception {
        setupEuEpochWithTwoRecords();
        Instant t1 = Instant.parse("2026-09-28T11:00:00Z");
        Instant t2 = Instant.parse("2026-09-28T12:00:00Z");
        Instant t3 = Instant.parse("2026-09-28T13:00:00Z");
        when(clock.instant()).thenReturn(t1);
        query("q-1", "subj-a", "RESEARCH", List.of("rec-1"), List.of("EU")).andExpect(status().isOk());
        when(clock.instant()).thenReturn(t2);
        query("q-2", "subj-a", "RESEARCH", List.of("rec-2"), List.of("EU")).andExpect(status().isOk());

        // [t1, t2) 只含第一份快照（左闭右开）
        mockMvc.perform(get("/api/v1/residency/snapshots")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH")
                        .param("from", "2026-09-28T11:00:00Z").param("to", "2026-09-28T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value("q-1"))
                .andExpect(jsonPath("$[0].itemCount").value(1));
        // [t2, t3) 只含第二份
        mockMvc.perform(get("/api/v1/residency/snapshots")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH")
                        .param("from", "2026-09-28T12:00:00Z").param("to", "2026-09-28T13:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value("q-2"));
        // 无边界返回全部，按时间升序
        mockMvc.perform(get("/api/v1/residency/snapshots")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].requestId").value("q-1"))
                .andExpect(jsonPath("$[1].requestId").value("q-2"));
        // 非法区间 400
        mockMvc.perform(get("/api/v1/residency/snapshots")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH")
                        .param("from", "2026-09-28T13:00:00Z").param("to", "2026-09-28T11:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        // 迁移后诊断：两个代次状态、区域、版本与记录数量
        migrate("m-1", "subj-a", "RESEARCH", "US", List.of("rec-1", "rec-2")).andExpect(status().isOk());
        erase("e-1", "subj-a", "RESEARCH", "rec-1").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/residency/diagnostics/epochs")
                        .param("subjectKey", "subj-a").param("purpose", "RESEARCH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].epoch").value(1))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].regionCode").value("EU"))
                .andExpect(jsonPath("$[0].consentVersion").value(1))
                .andExpect(jsonPath("$[0].totalRecords").value(2))
                .andExpect(jsonPath("$[0].erasedRecords").value(1))
                .andExpect(jsonPath("$[1].epoch").value(2))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].regionCode").value("US"))
                .andExpect(jsonPath("$[1].consentVersion").value(2))
                .andExpect(jsonPath("$[1].totalRecords").value(2))
                .andExpect(jsonPath("$[1].erasedRecords").value(1));
    }

    // ---------- 并发：真实协调与提交顺序裁决 ----------

    @Test
    void concurrentMigrationsSerializeAndNeverLeavePartialCopies() throws Exception {
        setupEuEpochWithTwoRecords();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        // 两个迁移目标区域相同：先提交者 EU→US 成功，后裁决者在新代次上看到源目标区域相同而整批 422
        List<Callable<Integer>> tasks = List.of(
                () -> runMigration(ready, start, "m-us-1", "US"),
                () -> runMigration(ready, start, "m-us-2", "US"));
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 恰好一个迁移成功（200），另一个按提交顺序裁决整批失败：
        // 或在锁定的源行上看到已撤回（410 MIGRATION_SOURCE_REVOKED，H2 锁定读行为），
        // 或在新代次上看到目标区域相同（422 MIGRATION_OBJECT_REGION_MISMATCH）
        assertThat(statuses).hasSize(2);
        assertThat(statuses).contains(200);
        assertThat(statuses.get(0) == 200 ? statuses.get(1) : statuses.get(0)).isIn(410, 422);
        // 最终只有两个代次：源已撤回、目标有效；不存在 epoch 3 半成品
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 2 AND status = 'ACTIVE'"
                + " AND region_code = 'US'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 3")).isZero();
        // 对象只复制一次，源不残留半成品
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 2 AND region_code = 'US'")).isEqualTo(2);
    }

    private Integer runMigration(CountDownLatch ready, CountDownLatch start,
                                 String requestId, String targetRegion) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            residencyService.migrate(new MigrateRequest(
                    requestId, "subj-a", Purpose.RESEARCH, targetRegion, List.of("rec-1", "rec-2")));
            return 200;
        } catch (ApiException ex) {
            return ex.getStatus().value();
        }
    }

    @Test
    void concurrentQueryAndMigrationAreAdjudicatedByCommitOrder() throws Exception {
        setupEuEpochWithTwoRecords();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Object> queryFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                return residencyService.query(new BatchQueryRequest(
                        "q-race", "subj-a", Purpose.RESEARCH,
                        List.of("rec-1", "rec-2"), List.of("EU")));
            } catch (ApiException ex) {
                return ex;
            }
        });
        Future<Integer> migrateFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            try {
                residencyService.migrate(new MigrateRequest(
                        "m-race", "subj-a", Purpose.RESEARCH, "US", List.of("rec-1", "rec-2")));
                return 200;
            } catch (ApiException ex) {
                return ex.getStatus().value();
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        Object queryResult = queryFuture.get(20, TimeUnit.SECONDS);
        int migrateStatus = migrateFuture.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(migrateStatus).isEqualTo(200);

        if (queryResult instanceof com.example.starter.consent.dto.BatchQueryResponse snapshot) {
            // 查询先成功：返回冻结的 epoch 1 / EU 一致快照，迁移后仍不改写
            assertThat(snapshot.epoch()).isEqualTo(1);
            assertThat(snapshot.items()).allSatisfy(item -> assertThat(item.regionCode()).isEqualTo("EU"));
            getSnapshot(snapshot.snapshotId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.epoch").value(1))
                    .andExpect(jsonPath("$.items[0].regionCode").value("EU"));
        } else {
            // 迁移先提交：旧代查询整次 410，不返回部分结果
            ApiException ex = (ApiException) queryResult;
            assertThat(ex.getStatus().value()).isEqualTo(410);
            assertThat(ex.getCode()).isEqualTo("CONSENT_REVOKED");
        }
        // 最终裁决：源代撤回，新代有效
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 2 AND status = 'ACTIVE'")).isEqualTo(1);
    }
}
