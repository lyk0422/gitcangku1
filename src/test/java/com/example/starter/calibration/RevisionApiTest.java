package com.example.starter.calibration;

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
 * 测量修订接口测试：新版本原子落库与最新指针切换、旧版本与放行历史保留、
 * 按原测量时刻重匹配证书、400/404/409/422 失败语义、requestId 同参重放与改参冲突、失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevisionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM revision_request");
        jdbc.update("DELETE FROM measurement_latest");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
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

    private String reviseJson(Integer expected, String requestId, String reason,
                              String reading, String lower, String upper) {
        return """
                {"expectedRevision":%s,"requestId":"%s","reason":"%s","reading":"%s",
                 "lowerLimit":"%s","upperLimit":"%s"}"""
                .formatted(expected, requestId, reason, reading, lower, upper);
    }

    @Test
    void revisionCreatesNewPendingVersionAndSwitchesLatestAtomically() throws Exception {
        createCert("INS-1", "2", "1");
        submit("V-1", "INS-1", "1", "0", "9", "alice");
        // v1: 2×1+1=3，先放行旧版
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"V-1\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(1));

        // 修订：读数 2 → 2×2+1=5
        mvc.perform(post("/api/measurements/V-1/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "req-1", "读数登记错误更正", "2", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.latestRevision").value(2))
                .andExpect(jsonPath("$.reading").value("2"))
                .andExpect(jsonPath("$.computedValue").value("5"))
                .andExpect(jsonPath("$.displayValue").value("5.0000"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.revisionReason").value("读数登记错误更正"))
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.revisedBy").value("alice"))
                .andExpect(jsonPath("$.revisedAt").isString())
                // 仪器与测量时刻不变
                .andExpect(jsonPath("$.instrumentId").value("INS-1"))
                .andExpect(jsonPath("$.measuredAt").value("2026-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.submittedBy").value("alice"));

        // 新版提交即排除旧版当前可用资格，不因新版未放行而回退
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));

        // 旧版原值、计算结果与放行历史保留
        mvc.perform(get("/api/measurements/V-1").param("revision", "1"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.latestRevision").value(2))
                .andExpect(jsonPath("$.reading").value("1"))
                .andExpect(jsonPath("$.computedValue").value("3"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.revisionReason").doesNotExist())
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));

        // 明细默认最新
        mvc.perform(get("/api/measurements/V-1"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 指针表与版本行数正确
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'V-1'", Integer.class);
        assertEquals(2, versionCount);
        MapLike latest = latestPointer("V-1");
        assertEquals(2, latest.revision());
    }

    private record MapLike(int revision) {
    }

    private MapLike latestPointer(String key) {
        return new MapLike(jdbc.queryForObject(
                "SELECT revision FROM measurement_latest WHERE measurement_key = ?",
                Integer.class, key));
    }

    @Test
    void revisionReMatchesCertificateAtOriginalMeasuredInstant() throws Exception {
        long cert1 = createCert("INS-1", "1", "0");
        submit("V-2", "INS-1", "1", "0", "9", "alice");

        // 撤销原证书后修订：无未撤销证书覆盖原测量时刻 → 422，不增号不切指针
        mvc.perform(post("/api/certificates/{id}/revoke", cert1)).andExpect(status().isOk());
        mvc.perform(post("/api/measurements/V-2/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "req-422", "证书变化后重算", "2", "0", "9")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
        assertEquals(1, latestPointer("V-2").revision());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'V-2'", Integer.class));
        // 失败不占键：同 requestId 稍后可用于成功修订
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-1","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"3","b":"0"}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/V-2/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "req-422", "证书变化后重算", "2", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.computedValue").value("6"))
                .andExpect(jsonPath("$.certificateId").value(cert1 + 1));
    }

    @Test
    void nonSubmitterAndStaleExpectedRevisionReturn409WithoutNewVersion() throws Exception {
        createCert("INS-1", "1", "0");
        submit("V-3", "INS-1", "1", "0", "9", "alice");

        // 非原提交人 → 409
        mvc.perform(post("/api/measurements/V-3/revisions").header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "req-a", "他人修订", "2", "0", "9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ACTOR"));

        // 期望版本过期 → 409
        mvc.perform(post("/api/measurements/V-3/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(7, "req-b", "错误期望号", "2", "0", "9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_MISMATCH"));

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/measurements/V-3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "req-c", "无头", "2", "0", "9")))
                .andExpect(status().isBadRequest());

        assertEquals(1, latestPointer("V-3").revision());
    }

    @Test
    void invalidArgumentsReturn400AndUnknownKeyReturns404() throws Exception {
        createCert("INS-1", "1", "0");
        submit("V-4", "INS-1", "1", "0", "9", "alice");

        // expectedRevision 缺失
        mvc.perform(post("/api/measurements/V-4/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r","reason":"x","reading":"2","lowerLimit":"0","upperLimit":"9"}"""))
                .andExpect(status().isBadRequest());
        // 原因为空
        mvc.perform(post("/api/measurements/V-4/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "r", "  ", "2", "0", "9")))
                .andExpect(status().isBadRequest());
        // requestId 为空
        mvc.perform(post("/api/measurements/V-4/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, " ", "原因", "2", "0", "9")))
                .andExpect(status().isBadRequest());
        // 读数超过 6 位小数
        mvc.perform(post("/api/measurements/V-4/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "r", "原因", "2.0000001", "0", "9")))
                .andExpect(status().isBadRequest());
        // 下限大于上限
        mvc.perform(post("/api/measurements/V-4/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "r", "原因", "2", "9", "0")))
                .andExpect(status().isBadRequest());

        // 不存在的键 → 404
        mvc.perform(post("/api/measurements/NO-SUCH/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "r", "原因", "2", "0", "9")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameRequestIdReplaysFirstResultButChangedParamsConflict() throws Exception {
        createCert("INS-1", "2", "1");
        submit("V-5", "INS-1", "1", "0", "9", "alice");

        String first = mvc.perform(post("/api/measurements/V-5/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "idem-1", "首次修订", "2", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn().getResponse().getContentAsString();
        long firstId = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();

        // 同键同 requestId 同参重放 → 返回首次结果（同一版本 ID），不增号
        String replay = mvc.perform(post("/api/measurements/V-5/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "idem-1", "首次修订", "2", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn().getResponse().getContentAsString();
        assertEquals(firstId, ((Number) com.jayway.jsonpath.JsonPath.read(replay, "$.id")).longValue());
        assertEquals(2, latestPointer("V-5").revision());

        // 同 requestId 改参 → 409，不产生新版本
        mvc.perform(post("/api/measurements/V-5/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "idem-1", "首次修订", "3", "0", "9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post("/api/measurements/V-5/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(2, "idem-1", "首次修订", "2", "0", "9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(2, latestPointer("V-5").revision());

        // 继续正常修订到 v3；旧 requestId 重放返回 v2，绝不切回旧版
        mvc.perform(post("/api/measurements/V-5/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(2, "idem-2", "再次修订", "4", "0", "20")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.computedValue").value("9"));
        mvc.perform(post("/api/measurements/V-5/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "idem-1", "首次修订", "2", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.latestRevision").value(3));
        assertEquals(3, latestPointer("V-5").revision());
        mvc.perform(get("/api/measurements/V-5")).andExpect(jsonPath("$.revision").value(3));
    }

    @Test
    void revisionHistoryListsAllVersionsInOrder() throws Exception {
        createCert("INS-1", "1", "0");
        submit("V-6", "INS-1", "1", "0", "9", "alice");
        mvc.perform(post("/api/measurements/V-6/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(1, "h-1", "第二次", "2", "0", "9")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/V-6/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(2, "h-2", "第三次", "3", "0", "9")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/measurements/V-6/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurementKey").value("V-6"))
                .andExpect(jsonPath("$.latestRevision").value(3))
                .andExpect(jsonPath("$.revisions.length()").value(3))
                .andExpect(jsonPath("$.revisions[0].revision").value(1))
                .andExpect(jsonPath("$.revisions[1].revision").value(2))
                .andExpect(jsonPath("$.revisions[2].revision").value(3))
                .andExpect(jsonPath("$.revisions[0].revisionReason").doesNotExist())
                .andExpect(jsonPath("$.revisions[1].revisionReason").value("第二次"));

        // 旧版本可查；越界版本与未知键 404
        mvc.perform(get("/api/measurements/V-6").param("revision", "2"))
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(get("/api/measurements/V-6").param("revision", "9"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/measurements/NO-SUCH/revisions"))
                .andExpect(status().isNotFound());
    }
}
