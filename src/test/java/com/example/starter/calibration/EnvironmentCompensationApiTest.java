package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import com.example.starter.calibration.service.DomainClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 环境补偿专项测试（真实 H2，MODE=MySQL）：
 * 补偿公式与四位小数 HALF_UP、温湿度越界 422（含证书上限端点）、
 * 系数版本未生效/多版本命中 422、生效区间左闭右开、requestId 重放快照与 409、
 * 系数版本与放行并发的一致性、后续系数调整不重算已放行证书。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvironmentCompensationApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DomainClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM coefficient_version");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM coefficient_lock");
        clock.reset();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** 创建证书：a=1、b=0，温湿度范围 20～25℃、30～70%RH。 */
    private void createCertificate(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0",
                                 "tempMin":"20","tempMax":"25","humMin":"30","humMax":"70"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument, String reading) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"0","upperLimit":"99","submittedBy":"alice"}
                                """.formatted(key, instrument, reading)))
                .andExpect(status().isCreated());
    }

    private String coefficientJson(String tag, String refTemp, String refHum,
                                   String tempCoeff, String humCoeff,
                                   String from, String to) {
        return """
                {"versionTag":"%s","referenceTemperature":"%s","referenceHumidity":"%s",
                 "temperatureCoefficient":"%s","humidityCoefficient":"%s",
                 "effectiveFrom":"%s","effectiveTo":"%s"}
                """.formatted(tag, refTemp, refHum, tempCoeff, humCoeff, from, to);
    }

    private String releaseJson(String requestId, String key, String temp, String hum) {
        return """
                {"requestId":"%s","keys":["%s"],"temperature":"%s","humidity":"%s"}
                """.formatted(requestId, key, temp, hum);
    }

    @Test
    void compensationAt25DegreesComputedAndStoredWithFourDecimals() throws Exception {
        // v1：参考 20℃/40%RH，温度系数 0.1，湿度系数 0.01
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-1", "INS-1", "10");

        // 25℃、50%RH：ΔT=5 → 0.5；ΔH=10 → 0.1；补偿 0.6；最终 10 + 0.6 = 10.6000
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-1", "M-1", "25", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coefficientVersion").value("v1"))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.reading").value("10"))
                .andExpect(jsonPath("$.computedValue").value("10"))
                .andExpect(jsonPath("$.compensationValue").value("0.6"))
                .andExpect(jsonPath("$.finalValue").value("10.6000"))
                .andExpect(jsonPath("$.temperature").value("25"))
                .andExpect(jsonPath("$.humidity").value("50"))
                .andExpect(jsonPath("$.coefficientVersion").value("v1"))
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // 数据库行内固化补偿快照
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key='M-1' "
                        + "AND temperature=25 AND humidity=50 AND compensation_value=0.6 "
                        + "AND final_value=10.6000 AND coefficient_version_tag='v1'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void temperature25_1RejectedWith422AndActualValuesBut25EndpointAllowed() throws Exception {
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-OUT", "INS-1", "10");

        // 25.1℃ 越出上限 25℃ → 422，错误体给出实际值与越界项
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-OUT", "M-OUT", "25.1", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENVIRONMENT_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.details.temperature").value("25.1"))
                .andExpect(jsonPath("$.details.humidity").value("50"))
                .andExpect(jsonPath("$.details.matchedVersions[0]").value("v1"))
                .andExpect(jsonPath("$.details.failures[0].key").value("M-OUT"))
                .andExpect(jsonPath("$.details.failures[0].reasons[0]")
                        .value("TEMPERATURE_OUT_OF_RANGE"));

        // 不生成放行记录与幂等快照，测量保持待放行，环境字段为空
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot", Integer.class));
        mvc.perform(get("/api/measurements/{key}", "M-OUT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andExpect(jsonPath("$.finalValue").doesNotExist());

        // 恰好等于温度上限 25℃（以及湿度端点）视为范围内 → 放行成功
        submit("M-EDGE", "INS-1", "10");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-EDGE", "M-EDGE", "25", "70")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/{key}", "M-EDGE"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.finalValue").value("10.8000"));
    }

    @Test
    void humidityOutOfRangeAlso422AndReleasesAreAtomic() throws Exception {
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-A", "INS-1", "10");
        submit("M-B", "INS-1", "11");

        // 同批两条共用环境读数，湿度 29.9 越界 → 整批 422，无部分成功
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-H","keys":["M-A","M-B"],
                                 "temperature":"22","humidity":"29.9"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENVIRONMENT_OUT_OF_RANGE"))
                .andExpect(jsonPath("$.details.failures.length()").value(2))
                .andExpect(jsonPath("$.details.failures[?(@.key=='M-A')].reasons[0]")
                        .value("HUMIDITY_OUT_OF_RANGE"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot", Integer.class));
    }

    @Test
    void finalValueUsesHalfUpFourDecimals() throws Exception {
        // 温度系数 0.00001，ΔT=5 → 补偿 0.00005；读数 10.00005 → 10.00010 → HALF_UP 4 位 = 10.0001
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "50", "0.00001", "0",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-HALF", "INS-1", "10.00005");

        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-HALF", "M-HALF", "25", "50")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/{key}", "M-HALF"))
                .andExpect(jsonPath("$.computedValue").value("10.00005"))
                .andExpect(jsonPath("$.compensationValue").value("0.00005"))
                .andExpect(jsonPath("$.finalValue").value("10.0001"));
    }

    @Test
    void noEffectiveCoefficientReturns422() throws Exception {
        createCertificate("INS-1");
        submit("M-NONE", "INS-1", "10");

        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-NONE", "M-NONE", "22", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_EFFECTIVE_COEFFICIENT"))
                .andExpect(jsonPath("$.details.temperature").value("22"))
                .andExpect(jsonPath("$.details.humidity").value("50"))
                .andExpect(jsonPath("$.details.matchedVersions.length()").value(0));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void multipleEffectiveCoefficientsReturn422WithMatchedTags() throws Exception {
        // v1、v2 的生效区间在固定时刻 2026-06-01 重叠
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v2", "20", "40", "0.2", "0.02",
                                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-MULTI", "INS-1", "10");

        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-MULTI", "M-MULTI", "22", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MULTIPLE_COEFFICIENT_VERSIONS"))
                .andExpect(jsonPath("$.details.matchedVersions.length()").value(2))
                .andExpect(jsonPath("$.details.matchedVersions[0]").value("v1"))
                .andExpect(jsonPath("$.details.matchedVersions[1]").value("v2"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void coefficientEffectiveIntervalIsLeftClosedRightOpen() throws Exception {
        clock.setInstant(java.time.Instant.parse("2026-06-01T00:00:00Z"));
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-LC", "INS-1", "10");

        // 放行时刻恰好生效起点（左闭）→ 命中成功
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-LC", "M-LC", "22", "50")))
                .andExpect(status().isOk());

        // 新测量，放行时刻恰好生效终点（右开）→ 未命中 422
        submit("M-RO", "INS-1", "10");
        clock.setInstant(java.time.Instant.parse("2026-07-01T00:00:00Z"));
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-RO", "M-RO", "22", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_EFFECTIVE_COEFFICIENT"));
    }

    @Test
    void replayReturnsSnapshotAndChangedEnvironmentReturns409() throws Exception {
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-IDEM", "INS-1", "10");

        String first = mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-IDEM", "M-IDEM", "25", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        String batchId = (String) com.jayway.jsonpath.JsonPath.read(first, "$.batchId");

        // 完全相同的 requestId 与环境读数重放 → 200 + 首次快照（replayed=true，batchId 一致）
        String replay = mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-IDEM", "M-IDEM", "25", "50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.temperature").value("25"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(batchId, com.jayway.jsonpath.JsonPath.read(replay, "$.batchId"));

        // 同一 requestId 但环境读数被修改 → 409，错误信息给出首次与本次实际值
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-IDEM", "M-IDEM", "24", "50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPLAY_CONFLICT"));

        // 同一 requestId 但测量键被修改 → 409
        submit("M-OTHER", "INS-1", "10");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-IDEM", "M-OTHER", "25", "50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPLAY_CONFLICT"));

        // 重放不新增放行历史，仅一条
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id=r.measurement_id "
                        + "WHERE m.measurement_key='M-IDEM'", Integer.class));
    }

    @Test
    void concurrentSameRequestIdProducesSingleSnapshot() throws Exception {
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-CIDEM", "INS-1", "10");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(releaseJson("REQ-CIDEM", "M-CIDEM", "25", "50")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        for (Future<Integer> future : results) {
            // 同一 requestId 并发：全部 200（首个执行 + 其余重放），不得出现 5xx 或重复写入
            assertEquals(200, future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE request_id='REQ-CIDEM'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id=r.measurement_id "
                        + "WHERE m.measurement_key='M-CIDEM'", Integer.class));
    }

    @Test
    void coefficientCreationAndReleaseAreSerializedToCompleteVersions() throws Exception {
        // 初始无系数版本；并发“创建 v1”与“放行”，结果只能是两种完整状态之一
        createCertificate("INS-1");
        submit("M-SER", "INS-1", "10");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> createFuture = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                            .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                    "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                    .andReturn().getResponse().getStatus();
        });
        Future<Integer> releaseFuture = pool.submit(() -> {
            start.await();
            return mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(releaseJson("REQ-SER", "M-SER", "25", "50")))
                    .andReturn().getResponse().getStatus();
        });
        start.countDown();
        int createStatus = createFuture.get(30, TimeUnit.SECONDS);
        int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(201, createStatus);
        assertTrue(releaseStatus == 200 || releaseStatus == 422,
                "放行只能成功（引用完整 v1）或因无生效版本 422，实际: " + releaseStatus);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, coefficient_version_tag, final_value FROM measurement "
                        + "WHERE measurement_key='M-SER'");
        if (releaseStatus == 200) {
            // 放行引用完整新版本
            assertEquals("RELEASED", row.get("status"));
            assertEquals("v1", row.get("coefficient_version_tag"));
        } else {
            // 放行先于版本创建提交：无版本可用，未写入任何放行数据
            assertEquals("PENDING", row.get("status"));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record", Integer.class));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM coefficient_version WHERE version_tag='v1'", Integer.class));
    }

    @Test
    void laterCoefficientAdjustmentDoesNotRecomputeReleasedCertificate() throws Exception {
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
        createCertificate("INS-1");
        submit("M-FIXED", "INS-1", "10");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseJson("REQ-FIXED", "M-FIXED", "25", "50")))
                .andExpect(status().isOk());

        // 后续新增系数版本 v2（与 v1 生效区间重叠），不修改、不重算已放行结果
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v2", "20", "40", "9.9", "8.8",
                                "2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/measurements/{key}", "M-FIXED"))
                .andExpect(jsonPath("$.finalValue").value("10.6000"))
                .andExpect(jsonPath("$.coefficientVersion").value("v1"));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT coefficient_version_tag, final_value, compensation_value FROM measurement "
                        + "WHERE measurement_key='M-FIXED'");
        assertEquals("v1", row.get("coefficient_version_tag"));
        assertEquals(0, new java.math.BigDecimal("10.6000")
                .compareTo((java.math.BigDecimal) row.get("final_value")));
    }

    @Test
    void coefficientVersionCrudAndDuplicateTag() throws Exception {
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.1", "0.01",
                                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionTag").value("v1"))
                .andExpect(jsonPath("$.referenceTemperature").value("20"))
                .andExpect(jsonPath("$.temperatureCoefficient").value("0.1"));

        // 标签重复 → 409
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("v1", "20", "40", "0.2", "0.02",
                                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_COEFFICIENT_VERSION"));

        // 区间非法 → 400
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content(coefficientJson("vbad", "20", "40", "0.1", "0.01",
                                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest());

        // 未知版本 → 404
        mvc.perform(get("/api/coefficient-versions/{id}", 999999))
                .andExpect(status().isNotFound());
    }
}
