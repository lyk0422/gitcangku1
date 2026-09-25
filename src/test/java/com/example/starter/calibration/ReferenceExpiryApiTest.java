package com.example.starter.calibration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标准器到期门禁与放行版本追溯综合测试：
 * 证书版本/不确定度/singleBatchOnly、测量时态门禁与 referenceKey、批量提交预校验与幂等、
 * 放行到期/撤销门禁与批次绑定 422、未放行重算事务原子、血缘快照、并发幂等与绑定裁决。
 * 全部基于真实嵌入式 H2（MODE=MySQL），不 mock 数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReferenceExpiryApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM certificate_batch_binding");
        jdbc.update("DELETE FROM batch_submit");
        jdbc.update("DELETE FROM measurement_version");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    // ---------- 构造工具 ----------

    private String certBody(String standard, String version, String from, String to,
                            String a, String b, String uncertainty, String uVersion,
                            boolean singleBatchOnly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("standardId", standard);
        m.put("instrumentId", standard);
        m.put("version", version);
        m.put("validFrom", from);
        m.put("validTo", to);
        m.put("a", a);
        m.put("b", b);
        m.put("uncertainty", uncertainty);
        m.put("uncertaintyVersion", uVersion);
        m.put("singleBatchOnly", singleBatchOnly);
        return write(m);
    }

    private long createCert(String standard, String version, String from, String to,
                            String a, String b, String uncertainty, String uVersion,
                            boolean singleBatchOnly) throws Exception {
        String body = mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(certBody(standard, version, from, to, a, b, uncertainty, uVersion,
                                singleBatchOnly)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private Map<String, Object> measurement(String key, String instrument, String standard,
                                            String certVersion, String at, String reading,
                                            String lower, String upper, String by) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("measurementKey", key);
        m.put("instrumentId", instrument);
        if (standard != null) {
            m.put("standardId", standard);
            m.put("certificateVersion", certVersion);
        }
        m.put("measuredAt", at);
        m.put("reading", reading);
        m.put("lowerLimit", lower);
        m.put("upperLimit", upper);
        m.put("submittedBy", by);
        return m;
    }

    private String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MvcResult submit(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/measurements")
                .contentType(MediaType.APPLICATION_JSON).content(write(body))).andReturn();
    }

    private int http(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ---------- 1. 证书版本、不确定度、singleBatchOnly ----------

    @Test
    void certificateVersionUncertaintyAndSingleBatchOnlyExposed() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certBody("STD-1", "rev-7", "2026-01-01T00:00:00Z",
                                "2030-01-01T00:00:00Z", "1.5", "0.1", "0.25", "u-3", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.standardId").value("STD-1"))
                .andExpect(jsonPath("$.version").value("rev-7"))
                .andExpect(jsonPath("$.uncertainty").value("0.25"))
                .andExpect(jsonPath("$.uncertaintyVersion").value("u-3"))
                .andExpect(jsonPath("$.singleBatchOnly").value(true));
    }

    @Test
    void duplicateActiveVersionRejectedButNewVersionAllowed() throws Exception {
        createCert("STD-2", "v1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        // 同标准器同版本（区间不重叠也不行：版本号唯一）→ 409
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(certBody("STD-2", "v1", "2027-01-01T00:00:00Z",
                                "2028-01-01T00:00:00Z", "1", "0", "0", "u1", false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_VERSION_EXISTS"));
        // 新版本号 + 相邻区间 → 201
        createCert("STD-2", "v2", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z",
                "1", "0", "0", "u2", false);
    }

    @Test
    void timelineListsAllVersionsIncludingRevoked() throws Exception {
        long id = createCert("STD-T", "v1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        createCert("STD-T", "v2", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z",
                "2", "0", "0", "u2", false);
        mvc.perform(post("/api/certificates/{id}/revoke", id)).andExpect(status().isOk());

        mvc.perform(get("/api/certificates").param("standardId", "STD-T"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value("v1"))
                .andExpect(jsonPath("$[0].revoked").value(true))
                .andExpect(jsonPath("$[1].version").value("v2"));
    }

    // ---------- 2. 测量时态门禁、显式引用、版本快照、referenceKey ----------

    @Test
    void explicitReferenceProducesVersionedSnapshotAndUncertainty() throws Exception {
        createCert("STD-3", "v2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "2", "1", "0.5", "u9", false);
        // 2 × 3 + 1 = 7；扩展不确定度 = 2 × 0.5 = 1
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("K-1", "DUT-1", "STD-3", "v2",
                                "2026-06-01T00:00:00Z", "3", "0", "9", "alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.standardId").value("STD-3"))
                .andExpect(jsonPath("$.certificateVersion").value("v2"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.computedValue").value("7"))
                .andExpect(jsonPath("$.expandedUncertainty").value("1"))
                .andExpect(jsonPath("$.uncertaintyVersion").value("u9"))
                .andExpect(jsonPath("$.referenceKey").value(
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));

        Integer versionRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_version mv JOIN measurement m ON m.id = mv.measurement_id "
                        + "WHERE m.measurement_key = 'K-1'", Integer.class);
        assertEquals(1, versionRows);
    }

    @Test
    void validToEndpointIsExpiredAndNotYetValidRejected() throws Exception {
        createCert("STD-4", "v1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        // 恰为 validTo（右开）→ 到期无效 422，错误码可区分
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("K-EXP", "DUT-4", "STD-4", "v1",
                                "2027-01-01T00:00:00Z", "1", "0", "9", "alice"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_EXPIRED"));
        // 早于 validFrom → 未生效 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("K-EARLY", "DUT-4", "STD-4", "v1",
                                "2025-12-31T23:59:59Z", "1", "0", "9", "alice"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_NOT_YET_VALID"));
        // 引用不存在的证书版本 → 422 CERTIFICATE_NOT_FOUND
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("K-NO", "DUT-4", "STD-4", "v99",
                                "2026-06-01T00:00:00Z", "1", "0", "9", "alice"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_NOT_FOUND"));
        // 全部失败：不留任何测量或版本
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_version", Integer.class));
    }

    @Test
    void revokedExplicitReferenceRejected422ButHistoryKept() throws Exception {
        long certId = createCert("STD-5", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("K-R", "DUT-5", "STD-5", "v1",
                                "2026-06-01T00:00:00Z", "1", "0", "9", "alice"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("K-R")))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        // 撤销阻断后续测量
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("K-R2", "DUT-5", "STD-5", "v1",
                                "2026-06-02T00:00:00Z", "1", "0", "9", "alice"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));
        // 已放行快照保留：状态仍 RELEASED，放行记录固化版本号 1
        mvc.perform(get("/api/measurements/{key}", "K-R"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].measurementVersionNo").value(1));
    }

    // ---------- 3. 批量提交预校验、原子性与幂等 ----------

    @Test
    void batchSubmitValidatesFinalReferencesBeforeWriting() throws Exception {
        createCert("STD-6", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        List<Map<String, Object>> items = List.of(
                measurement("B-1", "DUT-6", "STD-6", "v1", "2026-06-01T00:00:00Z",
                        "1", "0", "9", "alice"),
                // 第二条引用了已到期版本 → 整批失败
                measurement("B-2", "DUT-6", "STD-6", "v9", "2026-06-01T00:00:00Z",
                        "1", "0", "9", "alice"));
        Map<String, Object> req = Map.of("batchId", "BATCH-FAIL", "submittedBy", "alice", "items", items);
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON).content(write(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_NOT_FOUND"));
        // 整批无半成品
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_version", Integer.class));
        // 失败不占键：同 batchId 修正载荷后可成功
        List<Map<String, Object>> fixed = List.of(
                measurement("B-1", "DUT-6", "STD-6", "v1", "2026-06-01T00:00:00Z",
                        "1", "0", "9", "alice"));
        Map<String, Object> fixedReq = Map.of("batchId", "BATCH-FAIL", "submittedBy", "alice", "items", fixed);
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(write(fixedReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void batchSubmitReplaySamePayloadAndConflictOnDifferentPayload() throws Exception {
        createCert("STD-7", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        List<Map<String, Object>> items = List.of(
                measurement("P-1", "DUT-7", "STD-7", "v1", "2026-06-01T00:00:00Z",
                        "1", "0", "9", "alice"),
                measurement("P-2", "DUT-7", "STD-7", "v1", "2026-06-02T00:00:00Z",
                        "2", "0", "9", "alice"));
        Map<String, Object> req = Map.of("batchId", "BATCH-OK", "submittedBy", "alice", "items", items);
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON).content(write(req)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false));

        // 相同载荷重放 → replayed=true，不重复落库
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON).content(write(req)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_submit", Integer.class));

        // 同 batchId 不同载荷（读数变化）→ 409
        List<Map<String, Object>> changed = List.of(
                measurement("P-1", "DUT-7", "STD-7", "v1", "2026-06-01T00:00:00Z",
                        "5", "0", "9", "alice"));
        Map<String, Object> changedReq = Map.of("batchId", "BATCH-OK", "submittedBy", "alice", "items", changed);
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(write(changedReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_SUBMIT_CONFLICT"));
    }

    // ---------- 4. 放行门禁：到期、撤销、绑定 422 ----------

    @Test
    void releaseRejectsExpiredCertificateWithStateUnchanged() throws Exception {
        // 证书在任意当前日期之前即已到期；测量时刻落在有效期内，提交成功
        createCert("STD-8", "v1", "2000-01-01T00:00:00Z", "2000-02-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("E-1", "DUT-8", "STD-8", "v1",
                                "2000-01-15T00:00:00Z", "1", "0", "9", "alice"))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("E-1")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_EXPIRED"));
        // 状态不变，无放行历史
        mvc.perform(get("/api/measurements/{key}", "E-1")).andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 诊断（不放行）同样报到期
        mvc.perform(post("/api/measurements/release/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("E-1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasable").value(false))
                .andExpect(jsonPath("$.items[0].reasons[0]").value("CERTIFICATE_EXPIRED"));
    }

    @Test
    void singleBatchOnlyBindsFirstBatchAndRejectsOtherBatches422() throws Exception {
        createCert("STD-9", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", true);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("S-1", "DUT-9", "STD-9", "v1",
                        "2026-06-01T00:00:00Z", "1", "0", "9", "alice")))).andExpect(status().isCreated());
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("S-2", "DUT-9", "STD-9", "v1",
                        "2026-06-02T00:00:00Z", "1", "0", "9", "bob")))).andExpect(status().isCreated());

        // 首批次放行成功并绑定
        MvcResult first = mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("S-1")))))
                .andExpect(status().isOk()).andReturn();
        String firstBatch = JsonPath.read(body(first), "$.batchId");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_batch_binding", Integer.class));

        // 其他批次引用同一证书 → 422 且返回已绑定批次
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("S-2")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_BOUND_TO_OTHER_BATCH"))
                .andExpect(jsonPath("$.failures[0].key").value("S-2"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_BOUND_TO_OTHER_BATCH"))
                .andExpect(jsonPath("$.failures[0].boundBatchId").value(firstBatch));
        // 被拒测量状态不变、无放行历史
        mvc.perform(get("/api/measurements/{key}", "S-2")).andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void singleBatchOnlyAllowsMultipleMeasurementsInSameBatch() throws Exception {
        createCert("STD-10", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", true);
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                    .content(write(measurement("SB-" + i, "DUT-10", "STD-10", "v1",
                            "2026-06-0" + i + "T00:00:00Z", "1", "0", "9", "alice"))))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("keys", List.of("SB-1", "SB-2", "SB-3")))))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_batch_binding", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    // ---------- 5. 重算事务：新版本、全量重算、失败保留旧版本 ----------

    @Test
    void recalculatingPendingMeasurementCreatesVersionAndReleaseSnapshotsIt() throws Exception {
        createCert("STD-11", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0.1", "u1", false);
        createCert("STD-11", "v2", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "3", "2", "0.4", "u2", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("V-1", "DUT-11", "STD-11", "v1",
                        "2026-06-01T00:00:00Z", "2", "0", "9", "alice")))).andExpect(status().isCreated());

        // 替换为 v2：3 × 2 + 2 = 8；扩展不确定度 0.8；版本号 2
        mvc.perform(post("/api/measurements/{key}/recalculate", "V-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("standardId", "STD-11", "certificateVersion", "v2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.certificateVersion").value("v2"))
                .andExpect(jsonPath("$.computedValue").value("8"))
                .andExpect(jsonPath("$.expandedUncertainty").value("0.8"))
                .andExpect(jsonPath("$.uncertaintyVersion").value("u2"));

        // 血缘：v1、v2 两版，v2 标记当前；两版 referenceKey 不同（版本号+证书版本均入指纹）
        mvc.perform(get("/api/measurements/{key}/lineage", "V-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].versionNo").value(1))
                .andExpect(jsonPath("$.versions[0].current").value(false))
                .andExpect(jsonPath("$.versions[1].versionNo").value(2))
                .andExpect(jsonPath("$.versions[1].current").value(true));
        String keyV1 = jdbc.queryForObject(
                "SELECT mv.reference_key FROM measurement_version mv JOIN measurement m ON m.id=mv.measurement_id "
                        + "WHERE m.measurement_key='V-1' AND mv.version_no=1", String.class);
        String keyV2 = jdbc.queryForObject(
                "SELECT mv.reference_key FROM measurement_version mv JOIN measurement m ON m.id=mv.measurement_id "
                        + "WHERE m.measurement_key='V-1' AND mv.version_no=2", String.class);
        assertNotEquals(keyV1, keyV2);

        // 放行后历史固化版本号 2
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("V-1")))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/{key}", "V-1"))
                .andExpect(jsonPath("$.releases[0].measurementVersionNo").value(2));
    }

    @Test
    void failedRecalculationKeepsOldVersionCurrentAndPending() throws Exception {
        createCert("STD-12", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0.1", "u1", false);
        // v2 区间不覆盖测量时刻（测量时刻已到期）
        createCert("STD-12", "v2", "2027-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "3", "2", "0.4", "u2", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("F-1", "DUT-12", "STD-12", "v1",
                        "2026-06-01T00:00:00Z", "2", "0", "9", "alice")))).andExpect(status().isCreated());

        mvc.perform(post("/api/measurements/{key}/recalculate", "F-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("standardId", "STD-12", "certificateVersion", "v2"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_NOT_YET_VALID"));

        // 旧版本仍为当前版本：版本号 1、证书 v1、计算值 2，仍 PENDING，版本表只有一行
        mvc.perform(get("/api/measurements/{key}", "F-1"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.certificateVersion").value("v1"))
                .andExpect(jsonPath("$.computedValue").value("2"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement_version mv JOIN measurement m ON m.id=mv.measurement_id "
                        + "WHERE m.measurement_key='F-1'", Integer.class));
    }

    @Test
    void recalculatingReleasedMeasurementRejected() throws Exception {
        createCert("STD-13", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        createCert("STD-13", "v2", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "2", "0", "0", "u2", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("RL-1", "DUT-13", "STD-13", "v1",
                        "2026-06-01T00:00:00Z", "1", "0", "9", "alice")))).andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("RL-1")))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/measurements/{key}/recalculate", "RL-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("standardId", "STD-13", "certificateVersion", "v2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
    }

    @Test
    void recalculatingToSameCertificateIsIdempotent() throws Exception {
        createCert("STD-17", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("ID-1", "DUT-17", "STD-17", "v1",
                        "2026-06-01T00:00:00Z", "1", "0", "9", "alice")))).andExpect(status().isCreated());
        // 目标证书与当前引用相同：幂等返回，不追加版本
        mvc.perform(post("/api/measurements/{key}/recalculate", "ID-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("standardId", "STD-17", "certificateVersion", "v1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement_version", Integer.class));
    }

    @Test
    void autoMatchWithOverlappingExplicitVersionsIsAmbiguous() throws Exception {
        // 两张显式版本证书时间窗重叠（换版过渡），自动匹配（不指定版本）应判歧义
        createCert("STD-18", "v1", "2026-01-01T00:00:00Z", "2027-06-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        createCert("STD-18", "v2", "2026-03-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "2", "0", "0", "u2", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("AM-1", "STD-18", null, null,
                                "2026-05-01T00:00:00Z", "1", "0", "9", "alice"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_AMBIGUOUS"));
        // 显式指定版本则正常
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(write(measurement("AM-2", "DUT-18", "STD-18", "v2",
                                "2026-05-01T00:00:00Z", "1", "0", "9", "alice"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificateVersion").value("v2"));
    }

    @Test
    void diagnoseDoesNotBindOrChangeState() throws Exception {
        createCert("STD-19", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", true);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("DG-1", "DUT-19", "STD-19", "v1",
                        "2026-06-01T00:00:00Z", "1", "0", "9", "alice")))).andExpect(status().isCreated());
        // 诊断：可放行
        mvc.perform(post("/api/measurements/release/diagnose")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("DG-1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasable").value(true))
                .andExpect(jsonPath("$.items[0].reasons").doesNotExist());
        // 诊断未产生绑定，之后正式放行成功
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("DG-1")))))
                .andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_batch_binding", Integer.class));
    }

    @Test
    void batchSubmitValidationErrorsDoNotOccupyKey() throws Exception {
        createCert("STD-20", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        // 空批次 → 400
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("batchId", "BV", "submittedBy", "alice", "items", List.of()))))
                .andExpect(status().isBadRequest());
        // 批次内重复测量键 → 400
        List<Map<String, Object>> dup = List.of(
                measurement("D-1", "DUT-20", "STD-20", "v1", "2026-06-01T00:00:00Z",
                        "1", "0", "9", "alice"),
                measurement("D-1", "DUT-20", "STD-20", "v1", "2026-06-02T00:00:00Z",
                        "1", "0", "9", "alice"));
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("batchId", "BV", "submittedBy", "alice", "items", dup))))
                .andExpect(status().isBadRequest());
        // 上述失败均不占键：同 batchId 的合法提交成功
        List<Map<String, Object>> ok = List.of(
                measurement("D-9", "DUT-20", "STD-20", "v1", "2026-06-01T00:00:00Z",
                        "1", "0", "9", "alice"));
        mvc.perform(post("/api/measurements/batch").contentType(MediaType.APPLICATION_JSON)
                        .content(write(Map.of("batchId", "BV", "submittedBy", "alice", "items", ok))))
                .andExpect(status().isCreated());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key <> 'D-9'", Integer.class));
    }

    @Test
    void missingVersionSnapshotBreaksTraceabilityGate() throws Exception {
        createCert("STD-14", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                .content(write(measurement("T-1", "DUT-14", "STD-14", "v1",
                        "2026-06-01T00:00:00Z", "1", "0", "9", "alice")))).andExpect(status().isCreated());
        // 人为破坏可追溯性：删除版本血缘快照
        jdbc.update("DELETE FROM measurement_version");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(write(Map.of("keys", List.of("T-1")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("TRACEABILITY_INCOMPLETE"));
    }

    // ---------- 6. 并发幂等与绑定裁决 ----------

    @Test
    void concurrentSameBatchSubmitOneCreatesOtherReplays() throws Exception {
        createCert("STD-15", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", false);
        List<Map<String, Object>> items = List.of(
                measurement("C-1", "DUT-15", "STD-15", "v1", "2026-06-01T00:00:00Z",
                        "1", "0", "9", "alice"));
        Map<String, Object> req = Map.of("batchId", "BATCH-C", "submittedBy", "alice", "items", items);
        String payload = write(req);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/batch")
                                .contentType(MediaType.APPLICATION_JSON).content(payload))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<Integer> future : results) {
            assertEquals(201, future.get(30, TimeUnit.SECONDS));
            ok++;
        }
        pool.shutdown();
        assertEquals(threads, ok);
        // 无论谁先提交，只落一条测量与一条台账；其余全部为同载荷重放
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_submit", Integer.class));
    }

    @Test
    void concurrentBatchesOnSingleBatchOnlyCertificateExactlyOneWins() throws Exception {
        createCert("STD-16", "v1", "2026-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "1", "0", "0", "u1", true);
        int threads = 6;
        for (int i = 0; i < threads; i++) {
            mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                    .content(write(measurement("X-" + i, "DUT-16", "STD-16", "v1",
                            "2026-06-01T00:00:0" + i + "Z", "1", "0", "9", "alice"))))
                    .andExpect(status().isCreated());
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol-" + index)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(write(Map.of("keys", List.of("X-" + index)))))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            assertTrue(status == 200 || status == 422, "仅允许 200 或 422，实际: " + status);
            if (status == 200) {
                ok.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "singleBatchOnly 证书并发下放行必须恰好一个批次成功");
        assertEquals(threads - 1, rejected.get(), "其余批次必须 422");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM certificate_batch_binding", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }
}
