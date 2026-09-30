package com.example.starter;

import com.example.starter.support.FixedBusinessClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 基于真实 H2 内存库（MODE=MySQL）与 MockMvc 的业务链集成测试：
 * 正常流程、各类拒绝原因、复检追加、作废失效/快照保留、requestId 幂等与原键重试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfiguration.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:cert-integration;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
class CertificationIntegrationTest {

    private static final String BATCH_A = "UT-BATCH-A";
    private static final String BATCH_B = "UT-BATCH-B";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FixedBusinessClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM batch_release");
        jdbcTemplate.update("DELETE FROM germination_certificate");
        jdbcTemplate.update("DELETE FROM germination_test");
        jdbcTemplate.update("DELETE FROM seed_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        clock.setTime(LocalDateTime.of(2026, 9, 15, 10, 0));
    }

    @Test
    void happyPath_twoQualifiedTests_issueAndReleaseWithSnapshot() throws Exception {
        createBatch(BATCH_A, "合成水稻");

        // 两次 100 粒检测，分别发芽 86、90 粒：86%、90% 均达标。
        registerTest(BATCH_A, "T-A-1", 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0));
        registerTest(BATCH_A, "T-A-2", 100, 90, LocalDateTime.of(2026, 9, 15, 9, 30));

        MvcResult issueResult = issueCertificate(BATCH_A, "REQ-ISSUE-1");
        String certificateNo = objectMapper.readTree(issueResult.getResponse().getContentAsString())
                .get("certificateNo").asText();
        assertEquals("CERT-" + BATCH_A + "-1", certificateNo);

        mockMvc.perform(post("/api/batches/{batchNo}/certificates", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-ISSUE-1"))))
                .andExpect(status().isCreated())
                // 同参重放返回首次响应：同一证书编号与同一签发时刻。
                .andExpect(jsonPath("$.certificateNo").value(certificateNo))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.firstTest.testNo").value("T-A-1"))
                .andExpect(jsonPath("$.firstTest.ratio").value(0.8600))
                .andExpect(jsonPath("$.secondTest.testNo").value("T-A-2"))
                .andExpect(jsonPath("$.secondTest.ratio").value(0.9000))
                .andExpect(jsonPath("$.issuedAt").value("2026-09-15T10:00:00"))
                .andExpect(jsonPath("$.requestId").value("REQ-ISSUE-1"));

        // 同 requestId 用于其他批次：异参重放 409。
        createBatch(BATCH_B, "合成小麦");
        registerTest(BATCH_B, "T-B-1", 100, 90, LocalDateTime.of(2026, 9, 15, 8, 0));
        registerTest(BATCH_B, "T-B-2", 100, 91, LocalDateTime.of(2026, 9, 15, 8, 30));
        mockMvc.perform(post("/api/batches/{batchNo}/certificates", BATCH_B)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-ISSUE-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(0, countCertificates(BATCH_B), "异参重放不得产生任何证书");

        clock.advanceSeconds(120);
        MvcResult releaseResult = release(BATCH_A, "REQ-RELEASE-1");
        assertEquals(certificateNo, objectMapper.readTree(releaseResult.getResponse().getContentAsString())
                .get("certificateNo").asText());

        // 放行同参重放返回首次响应（首次放行时刻 10:02）。
        mockMvc.perform(post("/api/batches/{batchNo}/release", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-RELEASE-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateNo").value(certificateNo))
                .andExpect(jsonPath("$.firstTestNo").value("T-A-1"))
                .andExpect(jsonPath("$.firstRatio").value(0.8600))
                .andExpect(jsonPath("$.secondTestNo").value("T-A-2"))
                .andExpect(jsonPath("$.secondRatio").value(0.9000))
                .andExpect(jsonPath("$.releasedAt").value("2026-09-15T10:02:00"));

        // 检测链可见全部检测、当前证书及其依赖的检测编号、放行状态。
        mockMvc.perform(get("/api/batches/{batchNo}/chain", BATCH_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests.length()").value(2))
                .andExpect(jsonPath("$.tests[0].testNo").value("T-A-1"))
                .andExpect(jsonPath("$.tests[1].testNo").value("T-A-2"))
                .andExpect(jsonPath("$.currentCertificate.certificateNo").value(certificateNo))
                .andExpect(jsonPath("$.currentCertificate.firstTest.testNo").value("T-A-1"))
                .andExpect(jsonPath("$.currentCertificate.secondTest.testNo").value("T-A-2"))
                .andExpect(jsonPath("$.release.certificateNo").value(certificateNo));
    }

    @Test
    void voidAfterRelease_keepsCertificateSnapshotAndReleaseFact() throws Exception {
        createBatch(BATCH_A);
        registerTest(BATCH_A, "T-A-1", 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0));
        registerTest(BATCH_A, "T-A-2", 100, 90, LocalDateTime.of(2026, 9, 15, 9, 30));
        issueCertificate(BATCH_A, "REQ-ISSUE-1");
        release(BATCH_A, "REQ-RELEASE-1");

        // 放行后作废第一次检测：历史不回滚，证书保持 VALID 快照、放行事实保留。
        mockMvc.perform(post("/api/batches/{batchNo}/tests/{testNo}/void", BATCH_A, "T-A-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"))
                .andExpect(jsonPath("$.sampleSize").value(100))
                .andExpect(jsonPath("$.germinatedCount").value(86));

        mockMvc.perform(get("/api/batches/{batchNo}/chain", BATCH_A))
                .andExpect(jsonPath("$.tests[0].status").value("VOID"))
                .andExpect(jsonPath("$.tests[0].voidedAt").value("2026-09-15T10:00:00"))
                .andExpect(jsonPath("$.currentCertificate.status").value("VALID"))
                .andExpect(jsonPath("$.currentCertificate.firstTest.testNo").value("T-A-1"))
                .andExpect(jsonPath("$.release").exists());
        assertEquals(1, countReleases(BATCH_A));
    }

    @Test
    void voidBeforeRelease_invalidatesCertificateAndReleaseReturns409() throws Exception {
        createBatch(BATCH_A);
        registerTest(BATCH_A, "T-A-1", 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0));
        registerTest(BATCH_A, "T-A-2", 100, 90, LocalDateTime.of(2026, 9, 15, 9, 30));
        issueCertificate(BATCH_A, "REQ-ISSUE-1");

        // 作废第一次检测后不再满足两次有效检测：未放行证书转为失效。
        mockMvc.perform(post("/api/batches/{batchNo}/tests/{testNo}/void", BATCH_A, "T-A-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOID"));

        mockMvc.perform(get("/api/batches/{batchNo}/chain", BATCH_A))
                .andExpect(jsonPath("$.currentCertificate.status").value("INVALID"))
                .andExpect(jsonPath("$.release").doesNotExist());

        mockMvc.perform(post("/api/batches/{batchNo}/release", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-RELEASE-1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_VALID_CERTIFICATE"));
        assertEquals(0, countReleases(BATCH_A), "失效证书不得放行，不产生放行事实");

        // 重复作废已作废记录被拒绝。
        mockMvc.perform(post("/api/batches/{batchNo}/tests/{testNo}/void", BATCH_A, "T-A-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_ALREADY_VOID"));
    }

    @Test
    void issueRejected_whenTestsInsufficientOrRatioBelow85_withoutPartialCertificate() throws Exception {
        createBatch(BATCH_A);
        registerTest(BATCH_A, "T-A-1", 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0));
        mockMvc.perform(post("/api/batches/{batchNo}/certificates", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-ISSUE-X"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_VALID_TESTS"));

        // 第二次仅 84 粒：最近两次不是都达到 85%。
        registerTest(BATCH_A, "T-A-2", 100, 84, LocalDateTime.of(2026, 9, 15, 9, 30));
        mockMvc.perform(post("/api/batches/{batchNo}/certificates", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-ISSUE-Y"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RATIO_NOT_QUALIFIED"));
        assertEquals(0, countCertificates(BATCH_A), "被拒绝时不得产生部分证书");

        // 85 粒正好达到 85%：边界合格（先作废 84 粒记录，再补一次 85 粒检测）。
        mockMvc.perform(post("/api/batches/{batchNo}/tests/{testNo}/void", BATCH_A, "T-A-2"))
                .andExpect(status().isOk());
        registerTest(BATCH_A, "T-A-3", 100, 85, LocalDateTime.of(2026, 9, 15, 9, 45));
        issueCertificate(BATCH_A, "REQ-ISSUE-Z");
        mockMvc.perform(get("/api/batches/{batchNo}/chain", BATCH_A))
                .andExpect(jsonPath("$.currentCertificate.status").value("VALID"))
                .andExpect(jsonPath("$.currentCertificate.secondTest.testNo").value("T-A-3"))
                .andExpect(jsonPath("$.currentCertificate.secondTest.ratio").value(0.8500));
    }

    @Test
    void sampleBoundsAndCountValidationAreRejected() throws Exception {
        createBatch(BATCH_A);
        mockMvc.perform(post("/api/batches/{batchNo}/tests", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("testNo", "T-LOW", "sampleSize", 99, "germinatedCount", 90))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/batches/{batchNo}/tests", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("testNo", "T-HIGH", "sampleSize", 401, "germinatedCount", 90))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 样本数在注解范围内，但发芽粒数大于样本数：跨字段校验同样拒绝。
        mockMvc.perform(post("/api/batches/{batchNo}/tests", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("testNo", "T-EXCEED", "sampleSize", 100, "germinatedCount", 101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 边界 100 粒合法。
        registerTest(BATCH_A, "T-BOUND", 100, 100, LocalDateTime.of(2026, 9, 15, 9, 0));
        assertEquals(1, countTests(BATCH_A));
    }

    @Test
    void duplicateAndCrossBatchTestNumbersAreRejected() throws Exception {
        createBatch(BATCH_A);
        createBatch(BATCH_B);
        registerTest(BATCH_A, "T-SHARED", 100, 90, LocalDateTime.of(2026, 9, 15, 9, 0));

        // 同批次重复检测编号。
        mockMvc.perform(post("/api/batches/{batchNo}/tests", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("testNo", "T-SHARED", "sampleSize", 100, "germinatedCount", 88))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_NO_DUPLICATE"));

        // 跨批次引用同一检测编号：数据库唯一约束保证一个编号只归属一个批次。
        mockMvc.perform(post("/api/batches/{batchNo}/tests", BATCH_B)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("testNo", "T-SHARED", "sampleSize", 100, "germinatedCount", 88))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CROSS_BATCH_REFERENCE"));
        assertEquals(1, countTestsByTestNo("T-SHARED"), "同一检测编号在全库只能有一行");

        // 跨批次路径作废不属于本批次的记录同样拒绝。
        registerTest(BATCH_B, "T-B-ONLY", 100, 90, LocalDateTime.of(2026, 9, 15, 8, 0));
        mockMvc.perform(post("/api/batches/{batchNo}/tests/{testNo}/void", BATCH_A, "T-B-ONLY"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CROSS_BATCH_REFERENCE"));
    }

    @Test
    void reusedTestRecordAndReleaseWithoutCertificateAreRejected() throws Exception {
        createBatch(BATCH_A);
        registerTest(BATCH_A, "T-A-1", 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0));
        registerTest(BATCH_A, "T-A-2", 100, 90, LocalDateTime.of(2026, 9, 15, 9, 30));
        issueCertificate(BATCH_A, "REQ-ISSUE-1");

        // 未追加新检测再次签发：最近两次仍为已被有效证书固化的记录，拒绝重复使用。
        mockMvc.perform(post("/api/batches/{batchNo}/certificates", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-ISSUE-2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_RECORD_REUSED"));
        assertEquals(1, countCertificates(BATCH_A), "重复使用检测记录不得产生第二张证书");

        // 无证书批次直接放行。
        createBatch(BATCH_B);
        registerTest(BATCH_B, "T-B-1", 100, 90, LocalDateTime.of(2026, 9, 15, 8, 0));
        mockMvc.perform(post("/api/batches/{batchNo}/release", BATCH_B)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-RELEASE-B"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_VALID_CERTIFICATE"));

        // 已放行批次再次放行（不同 requestId）冲突。
        release(BATCH_A, "REQ-RELEASE-1");
        mockMvc.perform(post("/api/batches/{batchNo}/release", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-RELEASE-2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_ALREADY_RELEASED"));
    }

    @Test
    void retestAppendsRecordAndLatestTwoDetermineCertificate() throws Exception {
        createBatch(BATCH_A);
        registerTest(BATCH_A, "T-A-1", 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0));
        registerTest(BATCH_A, "T-A-2", 100, 90, LocalDateTime.of(2026, 9, 15, 9, 30));
        // 复检追加：历史两行原样保留，最近两次变为 T-A-2、T-A-3。
        registerTest(BATCH_A, "T-A-3", 200, 180, LocalDateTime.of(2026, 9, 15, 9, 45));

        mockMvc.perform(get("/api/batches/{batchNo}/chain", BATCH_A))
                .andExpect(jsonPath("$.tests.length()").value(3))
                .andExpect(jsonPath("$.tests[0].testNo").value("T-A-1"))
                .andExpect(jsonPath("$.tests[1].testNo").value("T-A-2"))
                .andExpect(jsonPath("$.tests[2].testNo").value("T-A-3"));

        issueCertificate(BATCH_A, "REQ-ISSUE-1");
        mockMvc.perform(get("/api/batches/{batchNo}/chain", BATCH_A))
                .andExpect(jsonPath("$.currentCertificate.firstTest.testNo").value("T-A-2"))
                .andExpect(jsonPath("$.currentCertificate.secondTest.testNo").value("T-A-3"))
                .andExpect(jsonPath("$.currentCertificate.secondTest.sampleSize").value(200))
                .andExpect(jsonPath("$.currentCertificate.secondTest.ratio").value(0.9000));
    }

    @Test
    void failedTransactionLeavesNoIdempotencyRecord_originalRequestIdCanRetry() throws Exception {
        createBatch(BATCH_A);
        registerTest(BATCH_A, "T-A-1", 100, 86, LocalDateTime.of(2026, 9, 15, 9, 0));

        // 首次签发因检测不足失败：事务回滚，幂等记录不落库。
        mockMvc.perform(post("/api/batches/{batchNo}/certificates", BATCH_A)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "REQ-RETRY"))))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, countIdempotency("ISSUE", "REQ-RETRY"));
        assertEquals(0, countCertificates(BATCH_A));

        // 补齐第二次检测后允许原键重试并成功。
        registerTest(BATCH_A, "T-A-2", 100, 90, LocalDateTime.of(2026, 9, 15, 9, 30));
        issueCertificate(BATCH_A, "REQ-RETRY");
        assertEquals(1, countIdempotency("ISSUE", "REQ-RETRY"));
        assertEquals(1, countCertificates(BATCH_A));
    }

    @Test
    void unknownBatchAndMissingFieldsReturn404And400() throws Exception {
        mockMvc.perform(get("/api/batches/{batchNo}/chain", "NO-SUCH-BATCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));

        createBatch(BATCH_A);
        mockMvc.perform(post("/api/batches/{batchNo}/tests", BATCH_A)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 重复批次编号 409。
        mockMvc.perform(post("/api/batches")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("batchNo", BATCH_A, "cropName", "重复作物"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_DUPLICATE"));

        // 合成种子批次初始化数据在本地 H2 启动时存在；测试库已清空，建立批次后非空字段回显正常。
        MvcResult result = mockMvc.perform(post("/api/batches")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("batchNo", BATCH_B, "cropName", "合成小麦"))))
                .andExpect(status().isCreated())
                .andReturn();
        assertNotNull(objectMapper.readTree(result.getResponse().getContentAsString()).get("createdAt"));
    }

    private void createBatch(String batchNo) throws Exception {
        createBatch(batchNo, "合成水稻");
    }

    private void createBatch(String batchNo, String cropName) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "batchNo", batchNo, "cropName", cropName))))
                .andExpect(status().isCreated());
    }

    private void registerTest(String batchNo, String testNo, int sampleSize, int germinated,
                              LocalDateTime testedAt) throws Exception {
        mockMvc.perform(post("/api/batches/{batchNo}/tests", batchNo)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "testNo", testNo,
                                "sampleSize", sampleSize,
                                "germinatedCount", germinated,
                                "testedAt", testedAt.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.testNo").value(testNo))
                .andExpect(jsonPath("$.ratio").value((double) germinated / sampleSize));
    }

    private MvcResult issueCertificate(String batchNo, String requestId) throws Exception {
        return mockMvc.perform(post("/api/batches/{batchNo}/certificates", batchNo)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", requestId))))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult release(String batchNo, String requestId) throws Exception {
        return mockMvc.perform(post("/api/batches/{batchNo}/release", batchNo)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("requestId", requestId))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private long batchId(String batchNo) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM seed_batch WHERE batch_no = ?", Long.class, batchNo);
        return id == null ? -1 : id;
    }

    private int countCertificates(String batchNo) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM germination_certificate WHERE batch_id = ?",
                Integer.class, batchId(batchNo));
        return c == null ? 0 : c;
    }

    private int countReleases(String batchNo) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_release WHERE batch_id = ?",
                Integer.class, batchId(batchNo));
        return c == null ? 0 : c;
    }

    private int countTests(String batchNo) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM germination_test WHERE batch_id = ?",
                Integer.class, batchId(batchNo));
        return c == null ? 0 : c;
    }

    private int countTestsByTestNo(String testNo) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM germination_test WHERE test_no = ?",
                Integer.class, testNo);
        return c == null ? 0 : c;
    }

    private int countIdempotency(String scope, String requestId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE scope = ? AND request_id = ?",
                Integer.class, scope, requestId);
        return c == null ? 0 : c;
    }
}
