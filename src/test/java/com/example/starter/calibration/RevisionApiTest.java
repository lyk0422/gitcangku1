package com.example.starter.calibration;

import java.util.ArrayList;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测量修订与版本化放行测试：修订主流程（版本递增、仪器/时刻不变、重算、新版 PENDING）、
 * 失败回滚（不增号不切指针）、requestId 幂等重放、版本历史、旧入口 409、
 * 版本化放行逐项原因与整批回滚、同 expectedRevision 并发修订最多一次成功、
 * 放行与证书撤销按提交顺序裁决。基于真实 H2（MODE=MySQL）内存库。
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
        jdbc.update("DELETE FROM measurement_revision_request");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM measurement_head");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        return createCert(instrument, "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", a, b);
    }

    private long createCert(String instrument, String from, String to, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s"}
                                """.formatted(instrument, from, to, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String at, String reading, String lower,
                        String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, at, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    private String reviseJson(String key, int expected, String requestId, String reason,
                              String reading, String lower, String upper) {
        return """
                {"measurementKey":"%s","expectedRevision":%d,"requestId":"%s","reason":"%s",
                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s"}
                """.formatted(key, expected, requestId, reason, reading, lower, upper);
    }

    @Test
    void reviseCreatesPendingRevisionKeepingInstrumentAndTimeAndRecomputes() throws Exception {
        createCert("INS-1", "1.5", "0.123456");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "2.000001", "0", "9", "alice");

        // 修订读数与上下限：1.5 × 2.000001 + 0.123456 = 3.1234575，显示 3.1235
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "req-1", "探头重校",
                                "2.000001", "0", "3.123458")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.latest").value(true))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.instrumentId").value("INS-1"))
                .andExpect(jsonPath("$.measuredAt").isString())
                .andExpect(jsonPath("$.computedValue").value("3.1234575"))
                .andExpect(jsonPath("$.displayValue").value("3.1235"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.revisionReason").value("探头重校"))
                .andExpect(jsonPath("$.revisedBy").value("alice"))
                .andExpect(jsonPath("$.revisedAt").isString());

        // 默认明细返回最新版本并带 revision
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.latest").value(true));

        // 版本历史：两版，旧版 latest=false
        mvc.perform(get("/api/measurements/{key}/revisions", "M-1"))
                .andExpect(jsonPath("$.latestRevision").value(2))
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].revision").value(1))
                .andExpect(jsonPath("$.revisions[0].latest").value(false))
                .andExpect(jsonPath("$.revisions[0].revisionReason").doesNotExist())
                .andExpect(jsonPath("$.revisions[1].revision").value(2))
                .andExpect(jsonPath("$.revisions[1].revisionReason").value("探头重校"));

        // 指定版本明细
        mvc.perform(get("/api/measurements/{key}/revisions/{rev}", "M-1", 1))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.latest").value(false))
                .andExpect(jsonPath("$.reading").value("2.000001"));
    }

    @Test
    void revisionRematchesCertificateAtOriginalMeasurementTime() throws Exception {
        // 原证书系数 1，撤销后新建系数 2 的同区间证书；按原测量时刻重匹配应命中新证书
        long oldCert = createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        long v1Cert = ((Number) jdbc.queryForMap(
                "SELECT certificate_id c FROM measurement WHERE measurement_key='M-1' AND revision=1")
                .get("c")).longValue();
        assertEquals(oldCert, v1Cert);

        mvc.perform(post("/api/certificates/{id}/revoke", oldCert)).andExpect(status().isOk());
        long newCert = createCert("INS-1", "2", "0");

        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "req-x", "换证书", "3", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.certificateId").value((int) newCert))
                .andExpect(jsonPath("$.computedValue").value("6"));
    }

    @Test
    void newRevisionExcludesOldVersionFromUsableButKeepsReleaseHistory() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(1));

        // 修订提交：旧版立即失去当前可用资格，不因新版未放行而回退
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "req-r", "二次读数", "2", "0", "9")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));

        // 旧版原始值、计算结果与放行历史保留，版本 1 状态仍 RELEASED
        mvc.perform(get("/api/measurements/{key}/revisions/1", "M-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.computedValue").value("1"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));

        // 版本化放行新版后，每键至多一行最新版进入可用集合
        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released[0]").value("M-1"));
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].revision").value(2))
                .andExpect(jsonPath("$[0].computedValue").value("2"));
    }

    @Test
    void oldReleaseEndpointRejectsRevisedKeysWithExplicitVersionRequired() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "2026-06-01T00:00:00Z", "2", "0", "9", "alice");
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "req-a", "改", "1", "0", "9")))
                .andExpect(status().isCreated());

        // 仅含已修订键 → 409 并要求显式版本
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].key").value("M-1"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("EXPLICIT_VERSION_REQUIRED"));

        // 混批：已修订键 409，未修订的 M-2 不得部分放行
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\",\"M-2\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1')].reasons[0]")
                        .value("EXPLICIT_VERSION_REQUIRED"));
        mvc.perform(get("/api/measurements/{key}", "M-2"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);
    }

    @Test
    void revisionFailuresDoNotIncrementOrMovePointerAndRequestIdIsReusable() throws Exception {
        long certId = createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");

        // 不存在的键 → 404
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("NOPE", 1, "r0", "x", "1", "0", "9")))
                .andExpect(status().isNotFound());

        // 非原提交人 → 409
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "r1", "x", "1", "0", "9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_SUBMITTER"));

        // 过期 expectedRevision（先成功修一版制造冲突）
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "r2", "第一改", "1", "0", "9")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "r3", "旧基准", "1", "0", "9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"));

        // 非法参数：缺 expectedRevision、空原因、下限大于上限、超 6 位小数 → 400
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-1","requestId":"r4","reason":"x",
                                 "reading":"1","lowerLimit":"0","upperLimit":"9"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 2, "r5", "   ", "1", "0", "9")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 2, "r6", "x", "1", "9", "0")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 2, "r7", "x", "1.0000001", "0", "9")))
                .andExpect(status().isBadRequest());

        // 撤销证书后无未撤销证书可匹配 → 422；失败不增号、不切指针
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 2, "r-fail", "无证", "1", "0", "9")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
        assertEquals(2, ((Number) jdbc.queryForMap(
                "SELECT latest_revision v FROM measurement_head WHERE measurement_key='M-1'").get("v"))
                .intValue());
        Integer versionRows = jdbc.queryForObject(
                "SELECT COUNT(*) c FROM measurement WHERE measurement_key='M-1'", Integer.class);
        assertEquals(2, versionRows);

        // 新建同区间证书后，同一个失败 requestId 可成功复用 → 产生第 3 版
        createCert("INS-1", "1", "0");
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 2, "r-fail", "无证", "1", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(3));
    }

    @Test
    void revisionRequestIdIsIdempotentAndOldReplayDoesNotSwitchPointer() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");

        String firstRequest = reviseJson("M-1", 1, "idem-1", "首次", "2", "0", "9");
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(firstRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2));

        // 同键同 requestId 同参重放：返回首次结果（仍为第 2 版），不再增号
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(firstRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) c FROM measurement WHERE measurement_key='M-1'", Integer.class));

        // 改参重放 → 409
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "idem-1", "首次", "3", "0", "9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));

        // 推进到第 3 版后，重放第 2 版的旧响应不得切回旧版
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 2, "idem-2", "再次", "4", "0", "9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(3));
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(firstRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.latest").value(false));
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.latest").value(true));
    }

    @Test
    void versionedReleaseValidatesEachItemAndRejectsWholeBatch() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-OK", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        submit("M-FAIL", "INS-1", "2026-06-01T00:00:00Z", "100", "0", "9", "alice");
        submit("M-OLD", "INS-1", "2026-06-01T00:00:00Z", "2", "0", "9", "bob");
        submit("M-NOVER", "INS-1", "2026-06-01T00:00:00Z", "5", "0", "9", "alice");
        // M-OLD 修到第 2 版，使第 1 版过期
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-OLD", 1, "ro", "改", "2", "0", "9")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"measurementKey":"M-OK","revision":1},
                                  {"measurementKey":"M-FAIL","revision":1},
                                  {"measurementKey":"M-OLD","revision":1},
                                  {"measurementKey":"M-GONE","revision":1},
                                  {"measurementKey":"M-NOVER","revision":9}
                                ]}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-FAIL#1')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures[?(@.key=='M-OLD#1')].reasons[0]").value("VERSION_NOT_LATEST"))
                .andExpect(jsonPath("$.failures[?(@.key=='M-GONE#1')].reasons[0]")
                        .value("MEASUREMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.failures[?(@.key=='M-NOVER#9')].reasons[0]")
                        .value("VERSION_NOT_FOUND"));

        // 整批回滚：M-OK 未被放行
        mvc.perform(get("/api/measurements/{key}", "M-OK"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) c FROM release_record", Integer.class));

        // 放行人等于提交人 → SAME_ACTOR
        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-OK\",\"revision\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("SAME_ACTOR"));

        // 批次非法：空、超 50、重复键、缺 revision → 400
        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
        String big = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "{\"measurementKey\":\"K" + i + "\",\"revision\":1}").toList().toString();
        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":" + big + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"measurementKey":"M-OK","revision":1},
                                  {"measurementKey":"M-OK","revision":1}
                                ]}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-OK\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void versionedReleaseSucceedsThenRejectsAlreadyReleased() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson("M-1", 1, "rr", "改", "2", "0", "9")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedBy").value("carol"));

        mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ALREADY_RELEASED"));
    }

    @Test
    void concurrentRevisionsWithSameExpectedRevisionAtMostOneSucceeds() throws Exception {
        createCert("INS-C", "1", "0");
        submit("M-C", "INS-C", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reviseJson("M-C", 1, "c-" + index, "并发修订",
                                        String.valueOf(index + 1), "0", "99")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "同 expectedRevision 并发修订必须恰好一次成功");
        assertEquals(threads - 1, conflict.get(), "其余并发修订必须 409");

        // 只产生第 2 版，指针指向第 2 版，无第 3 版
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) c FROM measurement WHERE measurement_key='M-C'", Integer.class));
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT * FROM measurement_head WHERE measurement_key='M-C'");
        assertEquals(2, ((Number) head.get("latest_revision")).intValue());
        Integer idempotencyRows = jdbc.queryForObject(
                "SELECT COUNT(*) c FROM measurement_revision_request WHERE measurement_key='M-C'",
                Integer.class);
        assertEquals(1, idempotencyRows, "失败修订不得占用 requestId 记录");
    }

    @Test
    void versionedReleaseAndCertificateRevokeFollowCommitOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            final int r = round;
            long certId = createCert("INS-V" + r, "1", "0");
            submit("M-" + r, "INS-V" + r, "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
            mvc.perform(post("/api/measurements/revisions").header("X-Actor-Id", "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reviseJson("M-" + r, 1, "v-" + r, "改", "2", "0", "9")))
                    .andExpect(status().isCreated());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"items\":[{\"measurementKey\":\"M-" + r + "\",\"revision\":2}]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            Map<String, Object> v2 = jdbc.queryForMap(
                    "SELECT m.* FROM measurement m JOIN measurement_head h "
                            + "ON h.latest_measurement_id = m.id WHERE m.measurement_key = ?",
                    "M-" + r);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) c FROM release_record WHERE measurement_id = ?",
                    Integer.class, ((Number) v2.get("id")).longValue());
            Map<String, Object> cert = jdbc.queryForMap(
                    "SELECT * FROM calibration_certificate WHERE id = ?", certId);

            // 无论提交顺序如何，撤销证书的结果都不得进入当前可用集合
            mvc.perform(get("/api/measurements/usable"))
                    .andExpect(jsonPath("$.length()").value(0));

            if (releaseCount > 0) {
                // 放行先提交：允许成功，但撤销必须在其后；状态 RELEASED、历史保留
                assertEquals("RELEASED", v2.get("status"));
                Map<String, Object> release = jdbc.queryForMap(
                        "SELECT * FROM release_record WHERE measurement_id = ?",
                        ((Number) v2.get("id")).longValue());
                assertFalse(((java.sql.Timestamp) release.get("released_at"))
                                .after((java.sql.Timestamp) cert.get("revoked_at")),
                        "撤销提交后不得放行该证书下的结果");
            } else {
                // 撤销先提交：放行必须 409，新版保持 PENDING
                assertEquals(409, releaseStatus);
                assertEquals("PENDING", v2.get("status"));
            }
        }
    }
}
