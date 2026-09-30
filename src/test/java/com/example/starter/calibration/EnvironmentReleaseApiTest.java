package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 环境补偿放行 H2 集成测试（真实建表与数据写入）：
 * 成功计算（25℃ 边界可放行）、25.1℃ 越界 422 携带实际值且不写放行记录、
 * 系数未生效/多版本命中 422、requestId 重放快照与改读数 409、
 * 系数版本左闭右开（可控时钟）、放行结果随后续系数更新冻结不重算、
 * 状态类冲突 409、请求校验 400、查询并列原读数/补偿值/最终值/系数版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FixedClockTestConfig.class)
class EnvironmentReleaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ControllableBusinessClock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM release_request");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM coefficient_version");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        clock.setTime(FixedClockTestConfig.INITIAL_TIME);
    }

    /** 创建带环境范围（20～25℃、40～60%RH）的证书，a=1,b=0。 */
    private long createEnvCertificate(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0",
                                 "tempMin":"20","tempMax":"25","humidityMin":"40","humidityMax":"60"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private long createVersion(String instrument, String from, String refTemp, String refHum,
                               String tempCoeff, String humCoeff) throws Exception {
        String body = mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","effectiveFrom":"%s","refTemperature":"%s",
                                 "refHumidity":"%s","temperatureCoeff":"%s","humidityCoeff":"%s"}
                                """.formatted(instrument, from, refTemp, refHum, tempCoeff, humCoeff)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String reading, String lower,
                        String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private String releaseBody(String requestId, String... triples) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < triples.length; i += 3) {
            if (i > 0) {
                items.append(',');
            }
            items.append("""
                    {"measurementKey":"%s","temperature":"%s","humidity":"%s"}"""
                    .formatted(triples[i], triples[i + 1], triples[i + 2]));
        }
        return "{\"requestId\":\"%s\",\"items\":[%s]}".formatted(requestId, items);
    }

    private int envRelease(String requestId, String actor, String... triples) throws Exception {
        return mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody(requestId, triples)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void releaseAtUpperTemperatureBoundaryComputesFourDecimalFinalValue() throws Exception {
        createEnvCertificate("INS-1");
        long versionId = createVersion("INS-1", "2026-01-01T00:00:00Z",
                "20", "50", "0.01", "0.001");
        // 读数 10 → 计算值 10；(25−20)×0.01+(55−50)×0.001=0.055；最终 10.0550
        submit("M-1", "INS-1", "10", "0", "20", "alice");

        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-1", "M-1", "25", "55")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("RQ-1"))
                .andExpect(jsonPath("$.batchId").isString())
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.results[0].measurementKey").value("M-1"))
                .andExpect(jsonPath("$.results[0].reading").value("10"))
                .andExpect(jsonPath("$.results[0].temperature").value("25"))
                .andExpect(jsonPath("$.results[0].humidity").value("55"))
                .andExpect(jsonPath("$.results[0].compensationValue").value("0.055"))
                .andExpect(jsonPath("$.results[0].finalValue").value("10.0550"))
                .andExpect(jsonPath("$.results[0].coeffVersionId").value((int) versionId));

        // 查询并列原读数、补偿值、最终值与系数版本
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.reading").value("10"))
                .andExpect(jsonPath("$.temperature").value("25"))
                .andExpect(jsonPath("$.humidity").value("55"))
                .andExpect(jsonPath("$.compensationValue").value("0.055"))
                .andExpect(jsonPath("$.finalValue").value("10.0550"))
                .andExpect(jsonPath("$.coeffVersionId").value((int) versionId))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));

        // 数据库实际固化（真实 H2 写入核对，而非仅看响应）
        var row = jdbc.queryForMap("SELECT * FROM measurement WHERE measurement_key = 'M-1'");
        assertEquals(versionId, ((Number) row.get("coeff_version_id")).longValue());
        assertEquals("10.0550", row.get("final_value").toString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_request", Integer.class));
    }

    @Test
    void temperature251Rejected422WithActualValuesAndNoReleaseRecord() throws Exception {
        createEnvCertificate("INS-1");
        createVersion("INS-1", "2026-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        submit("M-OUT", "INS-1", "10", "0", "20", "alice");

        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-2", "M-OUT", "25.1", "55")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ENVIRONMENT_RELEASE_REJECTED"))
                .andExpect(jsonPath("$.failures[0].key").value("M-OUT"))
                .andExpect(jsonPath("$.failures[0].temperature").value("25.1"))
                .andExpect(jsonPath("$.failures[0].humidity").value("55"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ENVIRONMENT_OUT_OF_RANGE"));

        // 不生成放行记录与请求快照，测量保持待放行且环境列为 NULL
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_request", Integer.class));
        var row = jdbc.queryForMap("SELECT * FROM measurement WHERE measurement_key = 'M-OUT'");
        assertEquals("PENDING", row.get("status"));
        assertNullRow(row, "temperature", "humidity", "coeff_version_id",
                "compensation_value", "final_value");

        // 仅湿度越界同样 422
        submit("M-HUM", "INS-1", "10", "0", "20", "alice");
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-2B", "M-HUM", "25", "60.1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ENVIRONMENT_OUT_OF_RANGE"));
    }

    @Test
    void batchWithOneOutOfRangeRejectsWholeBatch422() throws Exception {
        createEnvCertificate("INS-1");
        createVersion("INS-1", "2026-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        submit("M-OK", "INS-1", "10", "0", "20", "alice");
        submit("M-BAD", "INS-1", "10", "0", "20", "alice");

        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-3", "M-OK", "22", "50", "M-BAD", "19.9", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[?(@.key=='M-BAD')].reasons[0]")
                        .value("ENVIRONMENT_OUT_OF_RANGE"));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals("PENDING",
                jdbc.queryForMap("SELECT * FROM measurement WHERE measurement_key = 'M-OK'").get("status"));
    }

    @Test
    void coefficientNotEffectiveReturns422AndUndefinedRangeReturns422() throws Exception {
        // 证书未定义环境范围
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-NORANGE","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        submit("M-NORANGE", "INS-NORANGE", "10", "0", "20", "alice");
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-NR", "M-NORANGE", "22", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]")
                        .value("CERTIFICATE_ENV_RANGE_UNDEFINED"));

        // 有范围与版本，但版本在未来才生效（时钟固定 2026-06-01）
        createEnvCertificate("INS-FUTURE");
        createVersion("INS-FUTURE", "2027-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        submit("M-FUTURE", "INS-FUTURE", "10", "0", "20", "alice");
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-F", "M-FUTURE", "22", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("COEFFICIENT_NOT_EFFECTIVE"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void multipleEffectiveVersionsReturns422() throws Exception {
        // 直接构造异常数据：同一仪器两个开放版本（绕过服务的闭合逻辑），验证 H2 上的真实 COUNT 判定
        createEnvCertificate("INS-DUP");
        jdbc.update("INSERT INTO coefficient_version (instrument_id, effective_from, effective_to, "
                        + "ref_temperature, ref_humidity, temperature_coeff, humidity_coeff, created_at) "
                        + "VALUES ('INS-DUP', '2026-01-01 00:00:00', NULL, 20, 50, 0.01, 0.001, '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO coefficient_version (instrument_id, effective_from, effective_to, "
                        + "ref_temperature, ref_humidity, temperature_coeff, humidity_coeff, created_at) "
                        + "VALUES ('INS-DUP', '2026-03-01 00:00:00', NULL, 20, 50, 0.02, 0.002, '2026-03-01 00:00:00')");
        submit("M-DUP", "INS-DUP", "10", "0", "20", "alice");

        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-DUP", "M-DUP", "22", "50")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("MULTIPLE_COEFFICIENT_VERSIONS"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void replaySameRequestIdReturnsFirstSnapshotAndChangedReadingReturns409() throws Exception {
        createEnvCertificate("INS-1");
        long v1 = createVersion("INS-1", "2026-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        submit("M-1", "INS-1", "10", "0", "20", "alice");

        String first = mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-PLAY", "M-1", "25", "55")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String batchId = com.jayway.jsonpath.JsonPath.read(first, "$.batchId");

        // 同 requestId 同读数重放：返回首次批次快照（即使测量已 RELEASED 也不报错）
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-PLAY", "M-1", "25", "55")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.results[0].finalValue").value("10.0550"))
                .andExpect(jsonPath("$.results[0].coeffVersionId").value((int) v1));

        // 只落一条快照与一条放行历史
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_request", Integer.class));

        // 同 requestId 修改环境读数 → 409，不重算、不新增记录
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-PLAY", "M-1", "24", "55")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_REQUEST_MISMATCH"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        var row = jdbc.queryForMap("SELECT * FROM measurement WHERE measurement_key = 'M-1'");
        assertEquals(0, new java.math.BigDecimal("25").compareTo(
                new java.math.BigDecimal(row.get("temperature").toString())));
        assertEquals("10.0550", row.get("final_value").toString());

        // 同 requestId 换放行人同样 409
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-PLAY", "M-1", "25", "55")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_REQUEST_MISMATCH"));
    }

    @Test
    void laterCoefficientUpdateDoesNotRecomputeReleasedCertificate() throws Exception {
        createEnvCertificate("INS-1");
        long v1 = createVersion("INS-1", "2026-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        submit("M-1", "INS-1", "10", "0", "20", "alice");
        assertEquals(200, envRelease("RQ-FREEZE", "carol", "M-1", "25", "55"));

        // 系数更新：v2 自 2026-09-01 起温度系数翻倍
        long v2 = createVersion("INS-1", "2026-09-01T00:00:00Z", "20", "50", "0.02", "0.001");
        mvc.perform(get("/api/coefficient-versions").param("instrumentId", "INS-1"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].effectiveTo").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$[1].id").value((int) v2))
                .andExpect(jsonPath("$[1].effectiveTo").doesNotExist());

        // 时钟推进到 v2 生效之后：已放行证书仍展示 v1 与当时四位小数结果，不重算
        clock.setTime(java.time.Instant.parse("2026-09-02T00:00:00Z"));
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.coeffVersionId").value((int) v1))
                .andExpect(jsonPath("$.finalValue").value("10.0550"))
                .andExpect(jsonPath("$.compensationValue").value("0.055"))
                .andExpect(jsonPath("$.temperature").value("25"));
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$[0].finalValue").value("10.0550"))
                .andExpect(jsonPath("$[0].coeffVersionId").value((int) v1));
    }

    @Test
    void coefficientIntervalIsLeftClosedRightOpen() throws Exception {
        createEnvCertificate("INS-1");
        long v1 = createVersion("INS-1", "2026-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        // v2 起点恰为固定时钟当前时刻：v1 被闭合同一时刻（右开）
        long v2 = createVersion("INS-1", "2026-06-01T00:00:00Z", "20", "50", "0.02", "0.002");

        // 当前时刻恰好 2026-06-01T00:00:00Z（左闭）→ 命中 v2
        submit("M-AT", "INS-1", "10", "0", "20", "alice");
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-AT", "M-AT", "25", "55")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].coeffVersionId").value((int) v2));

        // 时钟回到边界前 1 秒 → 仍命中 v1（v1 终点右开不含边界，边界前在区间内）
        clock.setTime(java.time.Instant.parse("2026-05-31T23:59:59Z"));
        submit("M-BEFORE", "INS-1", "10", "0", "20", "alice");
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-BEFORE", "M-BEFORE", "25", "55")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].coeffVersionId").value((int) v1));
    }

    @Test
    void stateConflictsReturn409AndEnvChecksDoNotOverrideThem() throws Exception {
        createEnvCertificate("INS-1");
        createVersion("INS-1", "2026-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        submit("M-1", "INS-1", "10", "0", "20", "alice");

        // 提交人=放行人 → 409 BATCH_REJECTED + SAME_ACTOR（即使环境也越界，状态校验优先）
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-S", "M-1", "99", "99")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SAME_ACTOR"));

        // 测量不存在 → 409
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-MISS", "M-GHOST", "22", "50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("MEASUREMENT_NOT_FOUND"));

        // 首次放行成功后，新 requestId 再放行同一测量 → 409 ALREADY_RELEASED
        assertEquals(200, envRelease("RQ-OK", "carol", "M-1", "22", "50"));
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(releaseBody("RQ-NEW", "M-1", "22", "50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ALREADY_RELEASED"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void invalidRequestsReturn400() throws Exception {
        // 空 items
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RQ-X\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
        // 缺少 requestId
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M\",\"temperature\":\"22\",\"humidity\":\"50\"}]}"))
                .andExpect(status().isBadRequest());
        // 温度不是合法十进制
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RQ-X\",\"items\":["
                                + "{\"measurementKey\":\"M\",\"temperature\":\"hot\",\"humidity\":\"50\"}]}"))
                .andExpect(status().isBadRequest());
        // 批次内重复测量键
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RQ-X\",\"items\":["
                                + "{\"measurementKey\":\"M\",\"temperature\":\"22\",\"humidity\":\"50\"},"
                                + "{\"measurementKey\":\"M\",\"temperature\":\"23\",\"humidity\":\"51\"}]}"))
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id
        mvc.perform(post("/api/measurements/environment-release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RQ-X\",\"items\":["
                                + "{\"measurementKey\":\"M\",\"temperature\":\"22\",\"humidity\":\"50\"}]}"))
                .andExpect(status().isBadRequest());
        // 超过 50 条
        String big = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "{\"measurementKey\":\"K%d\",\"temperature\":\"22\",\"humidity\":\"50\"}"
                        .formatted(i))
                .reduce((a, b) -> a + "," + b).orElseThrow();
        mvc.perform(post("/api/measurements/environment-release")
                        .header("X-Actor-Id", "carol").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"RQ-X\",\"items\":[" + big + "]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void coefficientUpdateClosesOpenVersionAndRejectsInvalidBoundary() throws Exception {
        long v1 = createVersion("INS-1", "2026-01-01T00:00:00Z", "20", "50", "0.01", "0.001");
        // 新生效起点不晚于开放版本起点 → 400
        mvc.perform(post("/api/coefficient-versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","effectiveFrom":"2025-12-01T00:00:00Z",
                                 "refTemperature":"20","refHumidity":"50",
                                 "temperatureCoeff":"0.02","humidityCoeff":"0.002"}"""))
                .andExpect(status().isBadRequest());

        long v2 = createVersion("INS-1", "2026-06-01T00:00:00Z", "20", "50", "0.02", "0.002");
        var first = jdbc.queryForMap("SELECT * FROM coefficient_version WHERE id = ?", v1);
        assertNotNull(first.get("effective_to"));
        assertEquals("2026-06-01 00:00:00.0", first.get("effective_to").toString());
        var second = jdbc.queryForMap("SELECT * FROM coefficient_version WHERE id = ?", v2);
        assertEquals(null, second.get("effective_to"));
    }

    private void assertNullRow(java.util.Map<String, Object> row, String... columns) {
        for (String column : columns) {
            assertEquals(null, row.get(column), "列 " + column + " 应为 NULL");
        }
    }
}
