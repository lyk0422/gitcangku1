package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.ExportRequest;
import com.example.starter.consent.dto.ExportResponse;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RevokeRequest;

/**
 * 导出快照 API 集成测试：覆盖代次一致读取、快照不可变、STALE 标记、
 * 撤回竞争、幂等与唯一键边界，全部基于真实 H2（MySQL 兼容模式）验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ExportService exportService;

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
                                 String... purposes) throws Exception {
        String purposeJson = java.util.Arrays.stream(purposes)
                .map(p -> "\"" + p + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return mockMvc.perform(post("/api/v1/exports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","exportKey":"%s","subjectKey":"%s","purposes":[%s]}
                        """.formatted(requestId, exportKey, subjectKey, purposeJson)));
    }

    private ResultActions readExport(String exportKey) throws Exception {
        return mockMvc.perform(get("/api/v1/exports/{exportKey}", exportKey));
    }

    private ResultActions listExports(String subjectKey) throws Exception {
        return mockMvc.perform(get("/api/v1/exports").param("subjectKey", subjectKey));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：代次一致读取与快照内容 ----------

    @Test
    void exportTwoPurposesSnapshotCompleteAndSortedByRecordKey() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        // 故意乱序写入，验证快照按 recordKey 升序
        write("w-1", "subj-a", "RESEARCH", "rec-b", "payload-b").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-a", "payload-a").andExpect(status().isOk());
        write("w-3", "subj-a", "PERSONALIZATION", "rec-1", "payload-1").andExpect(status().isOk());

        // 用途按字典序排列：PERSONALIZATION 在前，RESEARCH 在后
        export("e-1", "exp-1", "subj-a", "RESEARCH", "PERSONALIZATION")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportKey").value("exp-1"))
                .andExpect(jsonPath("$.subjectKey").value("subj-a"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.purposes.length()").value(2))
                .andExpect(jsonPath("$.purposes[0].purpose").value("PERSONALIZATION"))
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-1"))
                .andExpect(jsonPath("$.purposes[1].purpose").value("RESEARCH"))
                .andExpect(jsonPath("$.purposes[1].epoch").value(1))
                .andExpect(jsonPath("$.purposes[1].recordCount").value(2))
                .andExpect(jsonPath("$.purposes[1].records[0].recordKey").value("rec-a"))
                .andExpect(jsonPath("$.purposes[1].records[0].payload").value("payload-a"))
                .andExpect(jsonPath("$.purposes[1].records[1].recordKey").value("rec-b"));

        assertThat(count("SELECT COUNT(*) FROM export_snapshot WHERE export_key = 'exp-1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-1'")).isEqualTo(3);
    }

    @Test
    void exportSinglePurposeDoesNotIncludeOtherPurposeData() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "research-data").andExpect(status().isOk());
        write("w-2", "subj-a", "PERSONALIZATION", "rec-1", "personal-data").andExpect(status().isOk());

        export("e-1", "exp-1", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes.length()").value(1))
                .andExpect(jsonPath("$.purposes[0].purpose").value("RESEARCH"))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].records[0].payload").value("research-data"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-1'")).isEqualTo(1);
    }

    @Test
    void exportCoversOnlyCurrentActiveEpochRecords() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-old", "old-payload").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-2", "subj-a", "RESEARCH", "rec-new", "new-payload").andExpect(status().isOk());

        // 快照只覆盖当前有效代次（epoch 2），不混入旧代记录
        export("e-1", "exp-1", "subj-a", "RESEARCH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].epoch").value(2))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-new"));
    }

    // ---------- 失败分支：403 / 404 / 400 ----------

    @Test
    void exportWithRevokedPurposeReturns403AndSavesNothing() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "PERSONALIZATION", "rec-1", "payload-1").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 任一用途无有效授权：整次 403 并指明该用途，不保存部分快照
        export("e-1", "exp-1", "subj-a", "RESEARCH", "PERSONALIZATION")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_GRANTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("RESEARCH")));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'e-1'")).isZero();

        // 失败不占键：重新授权后同一 exportKey 与 requestId 可成功复用
        grant("g-3", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH", "PERSONALIZATION")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportKey").value("exp-1"));
    }

    @Test
    void exportWithNeverGrantedPurposeReturns403() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH", "PERSONALIZATION")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PURPOSE_NOT_GRANTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PERSONALIZATION")));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
    }

    @Test
    void exportWithUnknownSubjectReturns404() throws Exception {
        export("e-1", "exp-1", "subj-none", "RESEARCH")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBJECT_NOT_FOUND"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
    }

    @Test
    void readUnknownExportReturns404() throws Exception {
        readExport("exp-none")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
    }

    @Test
    void exportValidationErrorsReturn400() throws Exception {
        // 空用途集合
        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-1\",\"exportKey\":\"exp-1\",\"subjectKey\":\"subj-a\",\"purposes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // 超过 2 个用途
        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-1\",\"exportKey\":\"exp-1\",\"subjectKey\":\"subj-a\","
                                + "\"purposes\":[\"RESEARCH\",\"RESEARCH\",\"PERSONALIZATION\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // 缺少 exportKey
        mockMvc.perform(post("/api/v1/exports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"e-1\",\"subjectKey\":\"subj-a\",\"purposes\":[\"RESEARCH\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ---------- 快照不可变与 STALE ----------

    @Test
    void snapshotImmutableAgainstWritesRevokeAndRegrant() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 快照后写入新记录
        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
        // 撤回旧代
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 撤回不改写、不删除、不截断快照；当前代次已失效 → STALE
        readExport("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].status").value("STALE"))
                .andExpect(jsonPath("$.purposes[0].records.length()").value(1))
                .andExpect(jsonPath("$.purposes[0].records[0].recordKey").value("rec-1"));

        // 重新授权产生新 epoch，也不向快照追加内容
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-3", "subj-a", "RESEARCH", "rec-3", "payload-3").andExpect(status().isOk());
        readExport("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].epoch").value(1))
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].status").value("STALE"))
                .andExpect(jsonPath("$.purposes[0].records.length()").value(1));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-1'")).isEqualTo(1);
    }

    @Test
    void readExportIsStableAcrossRepeats() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());

        MvcResult first = readExport("exp-1").andExpect(status().isOk()).andReturn();
        MvcResult second = readExport("exp-1").andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 只读查询不推进代次、不写入记录
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-a'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
    }

    @Test
    void revokedEpochRecordQueryStillGoneAfterSnapshot() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        // 已撤回代次的普通记录查询仍返回 410，不因快照而放开
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "subj-a")
                        .param("purpose", "RESEARCH")
                        .param("recordKey", "rec-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    // ---------- 幂等与唯一键 ----------

    @Test
    void exportReplaySameParamsReorderedPurposesReturnsFirstResponse() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());

        MvcResult first = export("e-1", "exp-1", "subj-a", "RESEARCH", "PERSONALIZATION")
                .andExpect(status().isOk()).andReturn();
        // 用途集合换序视为同参：重放首次响应快照
        MvcResult replay = export("e-1", "exp-1", "subj-a", "PERSONALIZATION", "RESEARCH")
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'e-1'")).isEqualTo(1);
    }

    @Test
    void exportSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-b", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 同 requestId 换 exportKey：异参 409
        export("e-1", "exp-2", "subj-a", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 同 requestId 换主体：异参 409
        export("e-1", "exp-1", "subj-b", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void exportKeyIsGloballyUnique() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 新 requestId 复用同一 exportKey：409
        export("e-2", "exp-1", "subj-a", "RESEARCH")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPORT_KEY_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isEqualTo(1);
    }

    // ---------- 多快照与列表 ----------

    @Test
    void multipleSnapshotsPerSubjectAreIndependentAndImmutable() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());

        write("w-2", "subj-a", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
        export("e-2", "exp-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 第一个快照不受后续写入影响
        readExport("exp-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$.purposes[0].records.length()").value(1));
        readExport("exp-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[0].recordCount").value(2))
                .andExpect(jsonPath("$.purposes[0].records.length()").value(2));
    }

    @Test
    void listBySubjectReturnsSummariesWithoutRecords() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-a", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        export("e-1", "exp-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        export("e-2", "exp-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        listExports("subj-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].exportKey").value("exp-1"))
                .andExpect(jsonPath("$[0].subjectKey").value("subj-a"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].purposes[0].purpose").value("RESEARCH"))
                .andExpect(jsonPath("$[0].purposes[0].epoch").value(1))
                .andExpect(jsonPath("$[0].purposes[0].recordCount").value(1))
                .andExpect(jsonPath("$[0].purposes[0].records").doesNotExist())
                .andExpect(jsonPath("$[1].exportKey").value("exp-2"));

        // 其他主体互不可见；无快照主体返回空列表
        listExports("subj-other")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 并发：撤回与生成按提交顺序裁决 ----------

    @Test
    void concurrentExportAndRevokeResolvedByCommitOrder() throws Exception {
        grant("g-1", "subj-race", "RESEARCH").andExpect(status().isOk());
        write("w-1", "subj-race", "RESEARCH", "rec-1", "payload-1").andExpect(status().isOk());
        write("w-2", "subj-race", "RESEARCH", "rec-2", "payload-2").andExpect(status().isOk());
        write("w-3", "subj-race", "RESEARCH", "rec-3", "payload-3").andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<ExportResponse> exportFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            return exportService.create(new ExportRequest(
                    "e-race", "exp-race", "subj-race", List.of(Purpose.RESEARCH)));
        });
        Future<GrantResponse> revokeFuture = pool.submit(() -> {
            ready.countDown();
            start.await();
            return consentService.revoke(new RevokeRequest("r-race", "subj-race", Purpose.RESEARCH, 1));
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        ExportResponse exportResult = null;
        ApiException exportError = null;
        try {
            exportResult = exportFuture.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ApiException.class);
            exportError = (ApiException) e.getCause();
        }
        // 两种裁决下撤回都必须成功
        GrantResponse revokeResult = revokeFuture.get(20, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(revokeResult.status()).isEqualTo(GrantStatus.REVOKED);

        if (exportError != null) {
            // 撤回先提交：生成整次 403 且无内容
            assertThat(exportError.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exportError.getCode()).isEqualTo(ExportService.CODE_PURPOSE_NOT_GRANTED);
            assertThat(count("SELECT COUNT(*) FROM export_snapshot")).isZero();
            assertThat(count("SELECT COUNT(*) FROM export_snapshot_record")).isZero();
        } else {
            // 生成先提交：快照完整保留，随后读取标记 STALE
            assertThat(exportResult.purposes().get(0).records()).hasSize(3);
            assertThat(count("SELECT COUNT(*) FROM export_snapshot WHERE export_key = 'exp-race'")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM export_snapshot_record WHERE export_key = 'exp-race'")).isEqualTo(3);
            ExportResponse reread = exportService.read("exp-race");
            assertThat(reread.purposes().get(0).status()).isEqualTo(ExportResponse.STATUS_STALE);
            assertThat(reread.purposes().get(0).records()).hasSize(3);
        }
    }
}
