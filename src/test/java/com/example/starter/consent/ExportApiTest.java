package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.ExportRequest;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 导出快照 API 集成测试：覆盖代次一致读取、快照不可变、STALE 标记、
 * 撤回竞争裁决、幂等重放与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExportService exportService;

    @Autowired
    private ConsentService consentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM export_snapshot_record");
        jdbc.update("DELETE FROM export_snapshot_purpose");
        jdbc.update("DELETE FROM export_snapshot");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
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
                                 String purposesJson) throws Exception {
        return mockMvc.perform(post("/api/v1/exports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","exportKey":"%s","subjectKey":"%s","purposes":%s}
                        """.formatted(requestId, exportKey, subjectKey, purposesJson)));
    }

    private ResultActions detail(String exportKey) throws Exception {
        return mockMvc.perform(get("/api/v1/exports/{exportKey}", exportKey));
    }

    private ResultActions listBySubject(String subjectKey) throws Exception {
        return mockMvc.perform(get("/api/v1/exports").param("subjectKey", subjectKey));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 代次一致读取与主流程 ----------

    @Test
    void exportSinglePurposeCapturesEpochAndSortedRecords() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 乱序写入，快照须按 recordKey 升序固化
        write("w-2", "subj-a", "RESEARCH", "rec-b", "payload-b").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-a", "payload-a").andExpect(status().isOk());

        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportKey").value("exp-1"))
                .andExpect(jsonPath("$.subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.purposes[0].purpose").value("RESEARCH"))
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(2))
                .andExpect(jsonPath("$.purposes[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$.purposes[0].records[1].recordKey").value("rec-b"));

        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record")).isEqualTo(2);
    }

    @Test
    void exportTwoPurposesCapturesEachEpochIndependently() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        revoke("r-1", "subj-a", "PERSONALIZATION", 1).andExpect(status().isOk());
        grant("g-3", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-r", "research-data").andExpect(status().isOk());
        write("w-2", "subj-a", "PERSONALIZATION", "rec-p", "personal-data").andExpect(status().isOk());

        export("e-1", "exp-1", "subj-a", "[\"PERSONALIZATION\",\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].purpose").value("RESEARCH"))
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[1].purpose").value("PERSONALIZATION"))
                .andExpect(jsonPath("$.purposes[1].epoch").value(2))
                .andExpect(jsonPath("$.purposes[1].records[0].payload").value("personal-data"));
    }

    @Test
    void exportOnlyCoversRequestedPurposes() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-r", "research-data").andExpect(status().isOk());
        write("w-2", "subj-a", "PERSONALIZATION", "rec-p", "personal-data").andExpect(status().isOk());

        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        detail("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes.length()").value(1))
                .andExpect(jsonPath("$.purposes[0].purpose").value("RESEARCH"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_purpose")).isEqualTo(1);
    }

    @Test
    void exportOnlyCoversCurrentEpochRecords() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-old", "old-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-new", "new-payload").andExpect(status().isOk());

        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].epoch").value(2))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-new"));
    }

    // ---------- 403 / 404 失败分支 ----------

    @Test
    void exportWithInactivePurposeReturns403AndSavesNothing() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\",\"PERSONALIZATION\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_ACTIVE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PERSONALIZATION")));

        // 不保存部分快照，exportKey 未被占用
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_purpose")).isEqualTo(0);
        detail("exp-1").andExpect(status().isNotFound());
    }

    @Test
    void exportWithRevokedPurposeReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_ACTIVE"));
    }

    @Test
    void exportUnknownSubjectReturns404() throws Exception {
        export("e-1", "exp-1", "subj-none", "[\"RESEARCH\"]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
    }

    @Test
    void detailUnknownExportKeyReturns404() throws Exception {
        detail("exp-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
    }

    @Test
    void failedExportDoesNotConsumeKeyOrRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\",\"PERSONALIZATION\"]")
                .andExpect(status().isForbidden());
        // 失败后同一 requestId 与 exportKey 可重试成功
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\",\"PERSONALIZATION\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes.length()").value(2));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
    }

    // ---------- 快照不可变与 STALE ----------

    @Test
    void snapshotIsImmutableAfterRevokeAndRegrantAndMarkedStale() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());

        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 已撤回代次的普通记录查询仍返回 410，不因快照放开
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "subj-a")
                        .param("purpose", "RESEARCH")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));

        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());

        // 快照内容保持 epoch 1 的旧记录，状态标记 STALE
        detail("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[0].status").value("STALE"))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.purposes[0].records[0].payload").value("payload-1"));
        // 快照物理内容未被改写或截断
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-1'")).isEqualTo(1);
    }

    @Test
    void snapshotIsImmutableAfterSubsequentWritesInSameEpoch() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());

        // 后续同代写入不追加进快照；代次未变，状态仍为 CURRENT
        detail("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1));
    }

    @Test
    void repeatedDetailReadsAreStable() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());

        MvcResult first = detail("exp-1").andExpect(status().isOk()).andReturn();
        MvcResult second = detail("exp-1").andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void multipleSnapshotsOfSameSubjectAreIndependent() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
        export("e-2", "exp-2", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());

        detail("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1));
        detail("exp-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].recordCount").value(2));
    }

    // ---------- 幂等与唯一性 ----------

    @Test
    void exportReplaySameRequestIdWithReorderedPurposesReturnsFirstResponse() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\",\"PERSONALIZATION\"]")
                .andExpect(status().isOk());

        // 用途集合换序视为同参：重放首次响应快照，不新增快照
        export("e-1", "exp-1", "subj-a", "[\"PERSONALIZATION\",\"RESEARCH\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportKey").value("exp-1"))
                .andExpect(jsonPath("$.purposes.length()").value(2));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'e-1'")).isEqualTo(1);
    }

    @Test
    void exportSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        export("e-1", "exp-2", "subj-b", "[\"RESEARCH\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void exportKeyConflictWithDifferentRequestIdReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        export("e-2", "exp-1", "subj-a", "[\"RESEARCH\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_KEY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
    }

    // ---------- 列表查询与只读语义 ----------

    @Test
    void listBySubjectReturnsAllSnapshotsOfThatSubjectOnly() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        export("e-2", "exp-2", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        export("e-3", "exp-3", "subj-b", "[\"RESEARCH\"]").andExpect(status().isOk());

        listBySubject("subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.snapshots[0].exportKey").value("exp-1"))
                .andExpect(jsonPath("$.snapshots[0].purposes[0].purpose").value("RESEARCH"))
                .andExpect(jsonPath("$.snapshots[0].purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.snapshots[1].exportKey").value("exp-2"));

        listBySubject("subj-none")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots.length()").value(0));
    }

    @Test
    void readOnlyQueriesDoNotAdvanceEpochOrWriteRecords() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());

        int grantsBefore = count("SELECT COUNT(*) FROM consent_grant");
        int recordsBefore = count("SELECT COUNT(*) FROM consent_record");
        int idempotencyBefore = count("SELECT COUNT(*) FROM idempotency_request");

        detail("exp-1").andExpect(status().isOk());
        listBySubject("subj-a").andExpect(status().isOk());

        assertThat(count("SELECT COUNT(*) FROM consent_grant")).isEqualTo(grantsBefore);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(recordsBefore);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request")).isEqualTo(idempotencyBefore);
    }

    // ---------- 参数校验 ----------

    @Test
    void emptyPurposesReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void missingExportKeyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-1\",\"subjectKey\":\"subj-a\",\"purposes\":[\"RESEARCH\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentExportsWithSameExportKeyProduceSingleSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "e-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    exportService.export(new ExportRequest(requestId, "exp-race", "subj-a",
                            java.util.Set.of(Purpose.RESEARCH)));
                    return 200;
                } catch (ApiException ex) {
                    return ex.getStatus().value();
                }
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int successes = 0;
        int conflicts = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(20, TimeUnit.SECONDS);
            if (status == 200) {
                successes++;
            } else if (status == 409) {
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot WHERE export_key = 'exp-race'")).isEqualTo(1);
    }

    @Test
    void concurrentRevokeAndExportAreArbitratedByCommitOrder() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> exportTask = () -> {
            ready.countDown();
            start.await();
            try {
                exportService.export(new ExportRequest("e-race", "exp-race", "subj-a",
                        java.util.Set.of(Purpose.RESEARCH)));
                return 200;
            } catch (ApiException ex) {
                return ex.getStatus().value();
            }
        };
        Callable<Integer> revokeTask = () -> {
            ready.countDown();
            start.await();
            consentService.revoke(new RevokeRequest("r-race", "subj-a", Purpose.RESEARCH, 1));
            return 200;
        };
        Future<Integer> exportFuture = pool.submit(exportTask);
        Future<Integer> revokeFuture = pool.submit(revokeTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int exportStatus = exportFuture.get(20, TimeUnit.SECONDS);
        assertThat(revokeFuture.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        pool.shutdown();

        if (exportStatus == 200) {
            // 生成先提交：快照完整保留，随后读取标记 STALE
            assertThat(count("SELECT COUNT(*) FROM export_snapshot WHERE export_key = 'exp-race'")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-race'")).isEqualTo(1);
            detail("exp-race")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.purposes[0].status").value("STALE"))
                    .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-1"));
        } else {
            // 撤回先提交：生成整次 403 且无内容
            assertThat(exportStatus).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM export_snapshot WHERE export_key = 'exp-race'")).isEqualTo(0);
            detail("exp-race").andExpect(status().isNotFound());
        }
    }

    @Test
    void exportAfterRevokeCommittedFails403WhileExportBeforeRevokeSurvives() throws Exception {
        // 顺序场景一：先生成后撤回，快照保留且标记 STALE
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "[\"RESEARCH\"]").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        detail("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].status").value("STALE"))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1));

        // 顺序场景二：先撤回后生成，整次 403 且无内容
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        revoke("r-2", "subj-b", "RESEARCH", 1).andExpect(status().isOk());
        export("e-2", "exp-2", "subj-b", "[\"RESEARCH\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_ACTIVE"));
        detail("exp-2").andExpect(status().isNotFound());
    }
}
