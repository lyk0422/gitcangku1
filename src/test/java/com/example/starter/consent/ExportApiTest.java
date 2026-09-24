package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

import com.example.starter.consent.dto.ExportRequest;
import com.example.starter.consent.dto.ExportResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 导出快照 API 集成测试：覆盖代次一致读取、快照不可变、STALE 标记、撤回竞争与幂等边界。
 * 使用固定时钟保证生成时刻可断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportApiTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-24T10:15:30Z");
    private static final String FIXED_GENERATED_AT = "2026-09-24T10:15:30Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExportService exportService;

    @Autowired
    private ConsentService consentService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM export_snapshot_record");
        jdbc.update("DELETE FROM export_snapshot_purpose");
        jdbc.update("DELETE FROM export_snapshot");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        when(clock.instant()).thenReturn(FIXED_INSTANT);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions write(String requestId, String subjectKey, String purpose,
                                String recordKey, String payload) throws Exception {
        return mockMvc.perform(post("/api/v1/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","payload":"%s"}
                        """.formatted(requestId, subjectKey, purpose, recordKey, payload)));
    }

    private ResultActions revoke(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions export(String requestId, String exportKey, String subjectKey,
                                 String... purposes) throws Exception {
        String purposeJson = java.util.Arrays.stream(purposes)
                .map(purpose -> "\"" + purpose + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return mockMvc.perform(post("/api/v1/consent-exports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","exportKey":"%s","subjectKey":"%s","purposes":[%s]}
                        """.formatted(requestId, exportKey, subjectKey, purposeJson)));
    }

    private ResultActions readExport(String exportKey) throws Exception {
        return mockMvc.perform(get("/api/v1/consent-exports/{exportKey}", exportKey));
    }

    private ResultActions listExports(String subjectKey) throws Exception {
        return mockMvc.perform(get("/api/v1/consent-exports").param("subjectKey", subjectKey));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 生成主流程与代次一致读取 ----------

    @Test
    void exportCapturesCurrentEpochRecordsSortedByRecordKey() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-b", "rb").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-a", "ra").andExpect(status().isOk());
        write("w-3", "subj-a", "PERSONALIZATION", "rec-1", "p1").andExpect(status().isOk());

        export("e-1", "exp-1", "subj-a", "PERSONALIZATION", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportKey").value("exp-1"))
                .andExpect(jsonPath("$.subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.generatedAt").value(FIXED_GENERATED_AT))
                .andExpect(jsonPath("$.purposes.length()").value(2))
                // 用途按字典序排列
                .andExpect(jsonPath("$.purposes[0].purpose").value("PERSONALIZATION"))
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.purposes[1].purpose").value("RESEARCH"))
                .andExpect(jsonPath("$.purposes[1].recordCount").value(2))
                // 记录按 recordKey 升序
                .andExpect(jsonPath("$.purposes[1].records[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$.purposes[1].records[0].payload").value("ra"))
                .andExpect(jsonPath("$.purposes[1].records[1].recordKey").value("rec-b"));

        // 快照只读：不推进 epoch、不写入业务记录
        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record")).isEqualTo(3);
    }

    @Test
    void exportExcludesOldEpochAndUnrequestedPurposeData() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-old", "old").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-new", "new").andExpect(status().isOk());
        grant("g-3", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-3", "subj-a", "PERSONALIZATION", "rec-p", "p").andExpect(status().isOk());

        // 只请求 RESEARCH：只含当前 epoch=2 的记录，不混入旧代与 PERSONALIZATION
        export("e-1", "exp-1", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes.length()").value(1))
                .andExpect(jsonPath("$.purposes[0].epoch").value(2))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-new"));
    }

    // ---------- 失败分支：404 / 403 / 400 / 409 ----------

    @Test
    void exportSubjectNotFoundReturns404() throws Exception {
        export("e-1", "exp-1", "subj-none", "RESEARCH")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
    }

    @Test
    void exportWithInactivePurposeReturns403AndSavesNothing() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "r1").andExpect(status().isOk());
        // PERSONALIZATION 从未授权：主体存在但用途无有效授权 → 403 并指明用途
        export("e-1", "exp-1", "subj-a", "RESEARCH", "PERSONALIZATION")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_ACTIVE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PERSONALIZATION")));
        // 不保存部分快照
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_purpose")).isZero();
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record")).isZero();
    }

    @Test
    void exportWithRevokedPurposeReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_ACTIVE"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
    }

    @Test
    void exportWithDuplicatePurposesReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH", "RESEARCH")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PURPOSE"));
    }

    @Test
    void exportWithTooManyPurposesReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/consent-exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"e-1","exportKey":"exp-1","subjectKey":"subj-a",
                                 "purposes":["RESEARCH","PERSONALIZATION","RESEARCH"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void readMissingExportReturns404() throws Exception {
        readExport("exp-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
    }

    // ---------- 幂等与 exportKey 唯一性 ----------

    @Test
    void exportReplaySameRequestIdWithReorderedPurposesReturnsFirstResponse() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "r1").andExpect(status().isOk());

        export("e-1", "exp-1", "subj-a", "RESEARCH", "PERSONALIZATION").andExpect(status().isOk());
        // 用途换序视为同参：重放首次响应快照，不生成新快照
        export("e-1", "exp-1", "subj-a", "PERSONALIZATION", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportKey").value("exp-1"))
                .andExpect(jsonPath("$.generatedAt").value(FIXED_GENERATED_AT));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'e-1'")).isEqualTo(1);
    }

    @Test
    void exportSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-2", "subj-a", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void exportDuplicateExportKeyReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-2", "exp-1", "subj-a", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_KEY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
    }

    @Test
    void failedExportConsumesNeitherExportKeyNorRequestId() throws Exception {
        // 主体不存在：失败不占键
        export("e-1", "exp-1", "subj-none", "RESEARCH").andExpect(status().isNotFound());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
    }

    // ---------- 快照不可变与 STALE ----------

    @Test
    void snapshotSurvivesRevokeAndRegrantAndIsMarkedStale() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 撤回旧代不得改写、删除或截断快照
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 重新授权产生新 epoch 也不向快照追加内容
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());

        readExport("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value(FIXED_GENERATED_AT))
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].status").value("STALE"))
                .andExpect(jsonPath("$.purposes[0].records.length()").value(1))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.purposes[0].records[0].payload").value("payload-1"));
        // 快照物理内容未被改写或截断
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-1'")).isEqualTo(1);
    }

    @Test
    void readIsStableAcrossRepeatsAndStaysCurrentWithoutEpochChange() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());

        for (int i = 0; i < 2; i++) {
            readExport("exp-1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generatedAt").value(FIXED_GENERATED_AT))
                    .andExpect(jsonPath("$.purposes[0].status").value("CURRENT"))
                    .andExpect(jsonPath("$.purposes[0].records[0].payload").value("payload-1"));
        }
    }

    @Test
    void revokedEpochRecordReadStillReturns410AfterSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        // 快照存在不放开已撤回代次的普通记录查询
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "subj-a")
                        .param("purpose", "RESEARCH")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 多快照与列表查询 ----------

    @Test
    void multipleSnapshotsForSameSubjectAreIndependent() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "v1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "v2").andExpect(status().isOk());
        export("e-2", "exp-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 第一份快照不受其后的写入与第二份快照影响
        readExport("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1));
        readExport("exp-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].recordCount").value(2));

        listExports("subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.snapshots[0].exportKey").value("exp-1"))
                .andExpect(jsonPath("$.snapshots[0].purposes[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.snapshots[1].exportKey").value("exp-2"));
    }

    @Test
    void listBySubjectReflectsStaleAfterRevoke() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        listExports("subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots[0].purposes[0].status").value("STALE"));
    }

    @Test
    void listBySubjectWithoutSnapshotsReturnsEmptyList() throws Exception {
        listExports("subj-none")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots.length()").value(0));
    }

    // ---------- 撤回与生成并发：按提交顺序裁决 ----------

    @Test
    void concurrentExportAndRevokeAreDecidedByCommitOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> exportTask = () -> {
            ready.countDown();
            start.await();
            try {
                ExportResponse response = exportService.export(new ExportRequest(
                        "e-race", "exp-race", "subj-a", List.of(Purpose.RESEARCH)));
                return "EXPORT_OK:" + response.exportKey();
            } catch (ApiException ex) {
                return "EXPORT_FAIL:" + ex.getCode();
            }
        };
        Callable<String> revokeTask = () -> {
            ready.countDown();
            start.await();
            consentService.revoke(new RevokeRequest("r-race", "subj-a", Purpose.RESEARCH, 1));
            return "REVOKE_OK";
        };
        Future<String> exportFuture = pool.submit(exportTask);
        Future<String> revokeFuture = pool.submit(revokeTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String exportResult = exportFuture.get(30, TimeUnit.SECONDS);
        String revokeResult = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(revokeResult).isEqualTo("REVOKE_OK");
        int snapshotCount = count("SELECT COUNT(*) FROM export_snapshot WHERE export_key = 'exp-race'");
        if (exportResult.equals("EXPORT_OK:exp-race")) {
            // 生成先提交：快照完整保留，随后读取标记 STALE
            assertThat(snapshotCount).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-race'"))
                    .isEqualTo(1);
            readExport("exp-race")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.purposes[0].status").value("STALE"))
                    .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-1"));
        } else {
            // 撤回先提交：生成整次 403 且无内容
            assertThat(exportResult).isEqualTo("EXPORT_FAIL:" + ExportService.CODE_PURPOSE_NOT_ACTIVE);
            assertThat(snapshotCount).isZero();
            assertThat(count("SELECT COUNT(*) FROM export_snapshot_record")).isZero();
        }
    }

    @Test
    void concurrentExportsWithSameExportKeyProduceSingleSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "e-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    exportService.export(new ExportRequest(
                            requestId, "exp-shared", "subj-a", List.of(Purpose.RESEARCH)));
                    return "OK";
                } catch (ApiException ex) {
                    return ex.getCode();
                }
            });
        }
        List<Future<String>> futures = new java.util.ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int ok = 0;
        int conflicts = 0;
        for (Future<String> future : futures) {
            String result = future.get(30, TimeUnit.SECONDS);
            if (result.equals("OK")) {
                ok++;
            } else if (result.equals(ExportService.CODE_EXPORT_KEY_CONFLICT)) {
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot WHERE export_key = 'exp-shared'")).isEqualTo(1);
    }

    @Test
    void exportFailureLeavesRequestIdReusableAfterConcurrentConflict() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // exportKey 冲突失败：不占用 requestId
        export("e-2", "exp-1", "subj-a", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_KEY_CONFLICT"));
        export("e-2", "exp-2", "subj-a", "RESEARCH").andExpect(status().isOk());
    }

    @Test
    void exportServiceRejectsRevokedPurposeWithoutSaving() {
        consentService.grant(new GrantRequest("g-1", "subj-svc", Purpose.RESEARCH));
        consentService.revoke(new RevokeRequest("r-1", "subj-svc", Purpose.RESEARCH, 1));
        assertThatThrownBy(() -> exportService.export(
                new ExportRequest("e-1", "exp-svc", "subj-svc", List.of(Purpose.RESEARCH))))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ExportService.CODE_PURPOSE_NOT_ACTIVE);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
    }
}
