package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 环境补偿与放行门禁集成测试（真实 H2 MySQL 兼容库）：
 * 环境边界 422 与区间回传、数值精度持久化、批次整次 422 门禁与稳定列表、
 * 系数版本只影响后续测量、重算新链与已放行快照不改写、驳回重算、calcKey 幂等重放/失败不占键/并发裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvironmentCompensationApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM calc_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM compensation_coefficient");
        jdbc.update("DELETE FROM compensation_model");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createCert(String instrument, String a, String b) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated());
    }

    private long publishCoeff(String model, String k0, String kt, String kh,
                             String tmin, String tmax, String hmin, String hmax) throws Exception {
        String body = mvc.perform(post("/api/coefficients").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentModel":"%s","k0":"%s","kTemperature":"%s","kHumidity":"%s",
                                 "tempMin":"%s","tempMax":"%s","humidityMin":"%s","humidityMax":"%s"}
                                """.formatted(model, k0, kt, kh, tmin, tmax, hmin, hmax)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private String measurementJson(String key, String instrument, String model, String reading,
                               String lower, String upper, String temp, String humidity,
                               String uncertainty, String calcKey) {
        return """
                {"measurementKey":"%s","instrumentId":"%s","instrumentModel":"%s",
                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                 "lowerLimit":"%s","upperLimit":"%s",
                 "temperatureC":"%s","humidityPct":"%s","uncertainty":"%s",
                 "submittedBy":"alice","calcKey":"%s"}
                """.formatted(key, instrument, model == null ? "" : model, reading,
                        lower, upper,
                        temp == null ? "" : temp, humidity == null ? "" : humidity,
                        uncertainty == null ? "" : uncertainty,
                        calcKey == null ? "" : calcKey);
    }

    private void submit(String key, String reading, String lower, String upper,
                         String temp, String humidity, String uncertainty) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson(key, "INS-1", "M-1", reading, lower, upper,
                                temp, humidity, uncertainty, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void inRangeCompensationComputedToSixDecimalsAndPersisted() throws Exception {
        createCert("INS-1", "1", "0");
        // 系数均最多 6 位小数；C = 0.123456×1.0001 + 0.000001×50
        // = 0.1234683456 + 0.00005 = 0.1235183456 → HALF_UP 6 位 = 0.123518
        publishCoeff("M-1", "0", "0.123456", "0.000001", "0", "40", "0", "100");
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-1", "INS-1", "M-1", "5", "0", "9",
                                "1.0001", "50", "0.3", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.computedValue").value("5"))
                .andExpect(jsonPath("$.compensationValue").value("0.123518"))
                .andExpect(jsonPath("$.compensatedValue").value("5.123518"))
                .andExpect(jsonPath("$.passedAfterComp").value(true))
                .andExpect(jsonPath("$.coefficientVersion").value(1))
                .andExpect(jsonPath("$.temperatureC").value("1.0001"))
                .andExpect(jsonPath("$.humidityPct").value("50"))
                .andExpect(jsonPath("$.chain.length()").value(1))
                .andExpect(jsonPath("$.chain[0].versionNo").value(1));

        // H2 持久化精度核对：补偿值 6 位、补偿后值精确
        java.math.BigDecimal compDb = jdbc.queryForObject(
                "SELECT compensation_value FROM measurement WHERE measurement_key = 'E-1'",
                java.math.BigDecimal.class);
        java.math.BigDecimal compAfterDb = jdbc.queryForObject(
                "SELECT compensated_value FROM measurement WHERE measurement_key = 'E-1'",
                java.math.BigDecimal.class);
        assertEquals(0, new java.math.BigDecimal("0.123518").compareTo(compDb));
        assertEquals(0, new java.math.BigDecimal("5.123518").compareTo(compAfterDb));
    }

    @Test
    void outOfRangeReturns422WithIntervalAndNoMeasurementPersisted() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "10", "40", "20", "80");
        // 温度 41 超出上限 40
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-OOR", "INS-1", "M-1", "5", "0", "9",
                                "41", "50", null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENV_OUT_OF_COMPENSATION_RANGE"))
                .andExpect(jsonPath("$.instrumentModel").value("M-1"))
                .andExpect(jsonPath("$.tempMin").value(10))
                .andExpect(jsonPath("$.tempMax").value(40))
                .andExpect(jsonPath("$.humidityMin").value(20))
                .andExpect(jsonPath("$.humidityMax").value(80));
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM measurement", Integer.class);
        assertEquals(0, rows, "超区间不得落任何测量行");
        // 无生效系数版本 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-NC", "INS-1", "M-OTHER", "5", "0", "9",
                                "25", "50", null, null)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void environmentFieldsMustBeAllOrNothing() throws Exception {
        createCert("INS-1", "1", "0");
        // 只给温度不给型号/湿度 → 400
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"E-P","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"5",
                                 "lowerLimit":"0","upperLimit":"9","temperatureC":"25",
                                 "submittedBy":"alice"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void batchGateRejectsWholeBatch422WithStableKeysAndLeavesState() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        // E-A：有环境，补偿后 5.1 合格，不确定度 0.3 合格
        submit("E-A", "5", "0", "9", "25", "50", "0.3");
        // E-B：无环境 → MISSING_ENVIRONMENT
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"E-B","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"5",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"bob"}"""))
                .andExpect(status().isCreated());
        // E-C：有环境但补偿后 10.1 超上限 9 → OUT_OF_SPEC_AFTER_COMP，且不确定度 2 > 1
        submit("E-C", "10", "0", "9", "25", "50", "2");

        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-C","E-A","E-B"],"uncertaintyLimit":"1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_ENVIRONMENT_GATE"))
                .andExpect(jsonPath("$.failures.length()").value(2))
                // 稳定字典序列出测量标识
                .andExpect(jsonPath("$.failures[0].key").value("E-B"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("MISSING_ENVIRONMENT"))
                .andExpect(jsonPath("$.failures[1].key").value("E-C"))
                .andExpect(jsonPath("$.failures[1].reasons[0]").value("OUT_OF_SPEC_AFTER_COMP"))
                .andExpect(jsonPath("$.failures[1].reasons[1]").value("UNCERTAINTY_EXCEEDED"));

        // 既有放行状态不变：全部仍 PENDING，无放行历史
        Integer released = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE status = 'RELEASED'", Integer.class);
        assertEquals(0, released);
        Integer history = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, history);
    }

    @Test
    void releaseWithEnvironmentUsesCompensatedValueAndUncertainty() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        submit("E-OK", "5", "0", "9", "25", "50", "0.5");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-OK"],"uncertaintyLimit":"1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isString());
        mvc.perform(get("/api/measurements/{key}", "E-OK"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));
    }

    @Test
    void coefficientVersionOnlyAffectsLaterMeasurementsAndRecalcBuildsChain() throws Exception {
        createCert("INS-1", "1", "0");
        long v1 = publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        submit("E-V", "5", "0", "6", "25", "50", "0.2");
        mvc.perform(get("/api/measurements/{key}", "E-V"))
                .andExpect(jsonPath("$.coefficientId").value(v1))
                .andExpect(jsonPath("$.compensatedValue").value("5.1"));

        long v2 = publishCoeff("M-1", "1", "0", "0", "0", "40", "0", "100");
        // 新版本只影响后续测量：旧测量仍快照 v1
        mvc.perform(get("/api/measurements/{key}", "E-V"))
                .andExpect(jsonPath("$.coefficientId").value(v1));

        // 重算：单事务新版本，使用 v2；补偿后 6.0 恰在上限内
        String body = mvc.perform(post("/api/measurements/recalc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"E-V","batchKeys":["E-V"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.measurement.coefficientId").value(v2))
                .andExpect(jsonPath("$.measurement.compensatedValue").value("6"))
                .andExpect(jsonPath("$.measurement.status").value("PENDING"))
                .andExpect(jsonPath("$.diagnostics[0].measurementKey").value("E-V"))
                .andExpect(jsonPath("$.diagnostics[0].passable").value(true))
                .andReturn().getResponse().getContentAsString();
        long newId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.measurement.id")).longValue();

        // 重算链：两版本，旧版本保留 v1 快照
        mvc.perform(get("/api/measurements/{key}", "E-V"))
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.chain.length()").value(2))
                .andExpect(jsonPath("$.chain[0].coefficientId").value(v1))
                .andExpect(jsonPath("$.chain[0].compensatedValue").value("5.1"))
                .andExpect(jsonPath("$.chain[1].coefficientId").value(v2));
        java.math.BigDecimal firstComp = jdbc.queryForObject(
                "SELECT compensation_value FROM measurement WHERE measurement_key = 'E-V' AND version_no = 1",
                java.math.BigDecimal.class);
        long firstCoeff = jdbc.queryForObject(
                "SELECT coefficient_id FROM measurement WHERE measurement_key = 'E-V' AND version_no = 1",
                Long.class);
        assertEquals(v1, firstCoeff);
        assertEquals(0, new java.math.BigDecimal("0.100000").compareTo(firstComp));
        assertEquals(true, newId > 0);
    }

    @Test
    void recalcReassessesGateAndReleasedSnapshotNotRewritten() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0", "0", "0", "0", "40", "0", "100");
        // 已放行的测量：补偿后 5.0
        submit("E-R", "5", "0", "9", "25", "50", "0.2");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-R"],"uncertaintyLimit":"1"}"""))
                .andExpect(status().isOk());
        long releasedCoeff = ((Number) jdbc.queryForMap(
                "SELECT coefficient_id FROM measurement WHERE measurement_key='E-R'").get("coefficient_id")).longValue();

        publishCoeff("M-1", "2", "0", "0", "0", "40", "0", "100");
        // 已放行不得重算：409
        mvc.perform(post("/api/measurements/recalc").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"E-R","batchKeys":["E-R"]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
        // 已放行结果系数快照不改写
        long stillCoeff = ((Number) jdbc.queryForMap(
                "SELECT coefficient_id FROM measurement WHERE measurement_key='E-R'").get("coefficient_id")).longValue();
        assertEquals(releasedCoeff, stillCoeff);
        Integer versions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key='E-R'", Integer.class);
        assertEquals(1, versions, "已放行不允许产生新版本行");

        // 未放行测量重算后补偿后超规格 → 诊断不可放行，但重算本身 201（不自动放行）
        submit("E-G", "5", "0", "5.99", "25", "50", "0.2");
        mvc.perform(post("/api/measurements/recalc").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"E-G","batchKeys":["E-G"],"uncertaintyLimit":"1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurement.compensatedValue").value("7"))
                .andExpect(jsonPath("$.diagnostics[0].passable").value(false))
                .andExpect(jsonPath("$.diagnostics[0].reasons[0]").value("OUT_OF_SPEC_AFTER_COMP"));
        // 随后放行整批 422，且状态仍为 PENDING
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-G"],"uncertaintyLimit":"1"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].key").value("E-G"));
        assertEquals("PENDING", jdbc.queryForMap(
                "SELECT status FROM measurement WHERE measurement_key='E-G' AND version_no = 2").get("status"));
    }

    @Test
    void rejectThenRecalcProducesNewPendingVersion() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        submit("E-J", "5", "0", "9", "25", "50", "0.2");
        mvc.perform(post("/api/measurements/reject").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-J"],"reason":"数据待复核"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected[0]").value("E-J"));
        mvc.perform(get("/api/measurements/{key}", "E-J"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        // 已放行之外，已驳回可重算
        mvc.perform(post("/api/measurements/recalc").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"E-J","batchKeys":["E-J"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.measurement.status").value("PENDING"));
        // 重复驳回旧逻辑测量：当前最新版本已 PENDING，可再次驳回；旧放行版本不受影响验证略
    }

    @Test
    void rejectReleasedMeasurementRejected409() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        submit("E-K", "5", "0", "9", "25", "50", "0.2");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-K"],"uncertaintyLimit":"1"}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/measurements/reject").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-K"],"reason":"x"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ALREADY_RELEASED"));
    }

    @Test
    void calcKeyReplaysFirstResultAndFailureDoesNotOccupyKey() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        // 失败（超区间）不占键
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-IDEM", "INS-1", "M-1", "5", "0", "9",
                                "99", "50", null, "CK-1")))
                .andExpect(status().isUnprocessableEntity());
        Integer occupied = jdbc.queryForObject("SELECT COUNT(*) FROM calc_record", Integer.class);
        assertEquals(0, occupied, "失败不占 calcKey");
        // 同键合法请求成功占用
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-IDEM", "INS-1", "M-1", "5", "0", "9",
                                "25", "50", null, "CK-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementKey").value("E-IDEM"));
        // 同键同指纹重放首次结果（仍 200 语义，返回同一测量版本）
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-IDEM", "INS-1", "M-1", "5", "0", "9",
                                "25", "50", null, "CK-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1));
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key='E-IDEM'", Integer.class);
        assertEquals(1, rows, "重放不得产生新版本行");
        // 同键不同指纹（读数改变）→ 409
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content(measurementJson("E-IDEM", "INS-1", "M-1", "6", "0", "9",
                                "25", "50", null, "CK-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_FINGERPRINT_MISMATCH"));
    }

    @Test
    void concurrentSameCalcKeyExactlyOneInsertBothSucceed() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                                .content(measurementJson("E-CONC", "INS-1", "M-1", "5", "0", "9",
                                        "25", "50", null, "CK-CONC")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        for (Future<Integer> f : results) {
            int s = f.get(30, TimeUnit.SECONDS);
            if (s == 201) {
                ok.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
        }
        pool.shutdown();
        // 按事务提交顺序：首个提交占用键，其余回滚后重放首次结果（同为 201）
        assertEquals(threads, ok.get(), "同键并发全部以首次结果成功返回");
        assertEquals(0, other.get());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key='E-CONC'", Integer.class);
        Integer calcRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calc_record WHERE calc_key='CK-CONC'", Integer.class);
        assertEquals(1, rows, "只允许写入一条测量版本行");
        assertEquals(1, calcRows, "只允许占用一条 calcKey 记录");
    }

    @Test
    void releaseCalcKeyReplaysBatchAndDiagnosticsQueryWorks() throws Exception {
        createCert("INS-1", "1", "0");
        publishCoeff("M-1", "0.1", "0", "0", "0", "40", "0", "100");
        submit("E-D1", "5", "0", "9", "25", "50", "0.2");
        submit("E-D2", "6", "0", "9", "25", "50", "0.4");
        String body = """
                {"keys":["E-D2","E-D1"],"uncertaintyLimit":"1","calcKey":"REL-1"}""";
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // 同键重放：首次已放行，重放返回同一批次
        String second = mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstBatch = com.jayway.jsonpath.JsonPath.read(
                jdbc.queryForObject("SELECT response_json FROM calc_record WHERE calc_key='REL-1'",
                        String.class), "$.batchId");
        assertEquals(firstBatch, com.jayway.jsonpath.JsonPath.read(second, "$.batchId"));

        // 诊断查询（放行后）：稳定字典序、已放行原因可见，不改状态
        mvc.perform(post("/api/measurements/diagnostics").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"keys":["E-D2","E-D1"],"uncertaintyLimit":"1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].measurementKey").value("E-D1"))
                .andExpect(jsonPath("$[0].status").value("RELEASED"))
                .andExpect(jsonPath("$[0].reasons[0]").value("ALREADY_RELEASED"));
    }
}
