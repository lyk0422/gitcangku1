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
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 版本化放行测试：整批逐项原因、必须正是最新 PENDING 合格版本、放行人不同于提交人、
 * 旧放行入口对已修订键返回 409 REVISION_REQUIRED、修订与放行/证书撤销并发裁决、
 * 同 expectedRevision 并发修订最多一次成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionedReleaseApiTest {

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

    private void revise(String key, String actor, int expected, String requestId,
                        String reading, String lower, String upper) throws Exception {
        mvc.perform(post("/api/measurements/{key}/revisions", key).header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevision":%d,"requestId":"%s","reason":"修订-%s",
                                 "reading":"%s","lowerLimit":"%s","upperLimit":"%s"}"""
                                .formatted(expected, requestId, requestId, reading, lower, upper)))
                .andExpect(status().isCreated());
    }

    private ResultActions versionedRelease(String actor, String itemsJson) throws Exception {
        return mvc.perform(post("/api/measurements/release/versions").header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON).content(itemsJson));
    }

    @Test
    void versionedBatchReleasesLatestVersionsAtomically() throws Exception {
        createCert("INS-1", "1", "0");
        submit("W-1", "INS-1", "1", "0", "9", "alice");
        submit("W-2", "INS-1", "2", "0", "9", "bob");
        revise("W-1", "alice", 1, "rw1", "3", "0", "9");

        versionedRelease("carol",
                "{\"items\":[{\"measurementKey\":\"W-1\",\"revision\":2},"
                        + "{\"measurementKey\":\"W-2\",\"revision\":1}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isString())
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.released[0].measurementKey").value("W-1"))
                .andExpect(jsonPath("$.released[0].revision").value(2))
                .andExpect(jsonPath("$.released[1].measurementKey").value("W-2"))
                .andExpect(jsonPath("$.released[1].revision").value(1));

        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("[?(@.measurementKey=='W-1')].revision").value(2));

        // 重复版本化放行 → 整批 409，逐项 ALREADY_RELEASED
        versionedRelease("carol",
                "{\"items\":[{\"measurementKey\":\"W-1\",\"revision\":2}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].key").value("W-1"))
                .andExpect(jsonPath("$.failures[0].revision").value(2))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ALREADY_RELEASED"));
    }

    @Test
    void versionedReleaseRejectsStaleRevisionAndReportsEveryItemReason() throws Exception {
        createCert("INS-1", "1", "0");
        submit("W-3", "INS-1", "100", "0", "9", "alice"); // v1 不合格
        revise("W-3", "alice", 1, "rw3", "2", "0", "9");  // v2 合格、PENDING
        submit("W-4", "INS-1", "1", "0", "9", "bob");

        // 过期版本(v1) / 不合格 / 同提交人放行 / 不存在 同时出现 → 整批 409，无部分成功
        versionedRelease("bob",
                "{\"items\":["
                        + "{\"measurementKey\":\"W-3\",\"revision\":1},"
                        + "{\"measurementKey\":\"W-3X\",\"revision\":1},"
                        + "{\"measurementKey\":\"W-4\",\"revision\":1}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].key").value("W-3"))
                .andExpect(jsonPath("$.failures[0].revision").value(1))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("REVISION_MISMATCH"))
                .andExpect(jsonPath("$.failures[1].key").value("W-3X"))
                .andExpect(jsonPath("$.failures[1].reasons[0]").value("MEASUREMENT_NOT_FOUND"))
                // 放行人 bob == W-4 提交人
                .andExpect(jsonPath("$.failures[2].key").value("W-4"))
                .andExpect(jsonPath("$.failures[2].reasons[0]").value("SAME_ACTOR"))
                .andExpect(jsonPath("$.failures.length()").value(3));

        // 整批回滚：全部保持 PENDING，无放行历史
        mvc.perform(get("/api/measurements/W-3")).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/W-4")).andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 不合格最新版本 → NOT_PASSED
        submit("W-5", "INS-1", "100", "0", "9", "carol");
        versionedRelease("dave",
                "{\"items\":[{\"measurementKey\":\"W-5\",\"revision\":1}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("NOT_PASSED"));
    }

    @Test
    void versionedReleaseValidatesBatchShape() throws Exception {
        createCert("INS-1", "1", "0");
        submit("W-6", "INS-1", "1", "0", "9", "alice");

        // 空批次
        versionedRelease("carol", "{\"items\":[]}").andExpect(status().isBadRequest());
        // 超过 50 条
        String big = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "{\"measurementKey\":\"K%d\",\"revision\":1}".formatted(i)).toList().toString();
        versionedRelease("carol", "{\"items\":" + big + "}").andExpect(status().isBadRequest());
        // 同批重复键
        versionedRelease("carol",
                "{\"items\":[{\"measurementKey\":\"W-6\",\"revision\":1},"
                        + "{\"measurementKey\":\"W-6\",\"revision\":1}]}")
                .andExpect(status().isBadRequest());
        // 缺少 revision
        versionedRelease("carol",
                "{\"items\":[{\"measurementKey\":\"W-6\"}]}")
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id
        mvc.perform(post("/api/measurements/release/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"W-6\",\"revision\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legacyReleaseRejectsRevisedKeysWithRevisionRequired() throws Exception {
        createCert("INS-1", "1", "0");
        submit("W-7", "INS-1", "1", "0", "9", "alice");
        submit("W-8", "INS-1", "2", "0", "9", "bob");
        revise("W-7", "alice", 1, "rw7", "3", "0", "9");

        // 混有从未修订与已修订键 → 整批 409，已修订键要求显式版本
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"W-8\",\"W-7\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures.length()").value(1))
                .andExpect(jsonPath("$.failures[0].key").value("W-7"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("REVISION_REQUIRED"));
        // 整批拒绝：W-8 也未放行
        mvc.perform(get("/api/measurements/W-8")).andExpect(jsonPath("$.status").value("PENDING"));

        // 仅有从未修订键时旧入口仍可用
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"W-8\"]}"))
                .andExpect(status().isOk());

        // 显式版本放行修订后的键成功后进入当前可用集合
        versionedRelease("carol",
                "{\"items\":[{\"measurementKey\":\"W-7\",\"revision\":2}]}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void revisionExcludesPreviouslyReleasedOldVersionFromUsableImmediately() throws Exception {
        createCert("INS-1", "1", "0");
        submit("W-9", "INS-1", "1", "0", "9", "alice");
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"W-9\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(1));

        // v2 PENDING 提交即排除 v1，可用集合立即为空（不回退给 v1）
        revise("W-9", "alice", 1, "rw9", "2", "0", "9");
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));
        // v1 历史仍为 RELEASED 且保留放行记录
        mvc.perform(get("/api/measurements/W-9").param("revision", "1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releases.length()").value(1));
    }

    @Test
    void concurrentRevisionsWithSameExpectedRevisionAtMostOneSucceeds() throws Exception {
        createCert("INS-1", "1", "0");
        submit("W-10", "INS-1", "1", "0", "9", "alice");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/W-10/revisions").header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"expectedRevision":1,"requestId":"c-%d","reason":"并发修订",
                                         "reading":"2","lowerLimit":"0","upperLimit":"9"}""".formatted(index)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                conflicts.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "相同 expectedRevision 并发修订必须最多一次成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须为 409 REVISION_MISMATCH");
        assertEquals(2, jdbc.queryForObject(
                "SELECT revision FROM measurement_latest WHERE measurement_key = 'W-10'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'W-10'", Integer.class));
        // 只有一次成功占用 requestId 记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM revision_request WHERE measurement_key = 'W-10'", Integer.class));
    }

    @Test
    void sameRequestIdConcurrentRevisionsCreateExactlyOneVersion() throws Exception {
        createCert("INS-1", "1", "0");
        submit("W-11", "INS-1", "1", "0", "9", "alice");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/W-11/revisions").header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"expectedRevision":1,"requestId":"same-id","reason":"并发同参",
                                         "reading":"2","lowerLimit":"0","upperLimit":"9"}"""))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                ok.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
        }
        pool.shutdown();
        // 同参重放语义：全部返回 201（首个创建，其余重放首次结果），但只有一个新版本
        assertEquals(threads, ok.get() + 0, "同 requestId 同参并发都应得到首次成功结果");
        assertEquals(0, other.get());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'W-11'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT revision FROM measurement_latest WHERE measurement_key = 'W-11'",
                Integer.class));
    }

    @Test
    void revisionAndVersionedReleaseConcurrentFollowCommitOrder() throws Exception {
        // 多轮验证：放行与修订并发，不得放行非最新版本
        for (int round = 0; round < 10; round++) {
            int r = round;
            createCert("INS-RV%d".formatted(r), "1", "0");
            submit("W-R%d".formatted(r), "INS-RV%d".formatted(r), "1", "0", "9", "alice");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> reviseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/W-R{0}/revisions", r)
                                .header("X-Actor-Id", "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"expectedRevision":1,"requestId":"rr-%d","reason":"并发修订",
                                         "reading":"2","lowerLimit":"0","upperLimit":"9"}""".formatted(r)))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return versionedRelease("carol",
                        "{\"items\":[{\"measurementKey\":\"W-R%d\",\"revision\":1}]}".formatted(r))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int reviseStatus = reviseFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(201, reviseStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            Integer v2Releases = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record rr "
                            + "JOIN measurement m ON m.id = rr.measurement_id "
                            + "WHERE m.measurement_key = ? AND m.revision = 2",
                    Integer.class, "W-R%d".formatted(r));
            assertEquals(0, v2Releases, "v2 从未在该批次中被放行");
            if (releaseStatus == 200) {
                // 放行先提交：放行的是 v1；修订后 v1 已非最新，当前可用集合必须为空
                Integer v1Releases = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM release_record rr "
                                + "JOIN measurement m ON m.id = rr.measurement_id "
                                + "WHERE m.measurement_key = ? AND m.revision = 1",
                        Integer.class, "W-R%d".formatted(r));
                assertEquals(1, v1Releases);
            }
            mvc.perform(get("/api/measurements/usable")
                            .param("instrumentId", "INS-RV%d".formatted(r)))
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void revokeAndVersionedReleaseConcurrentFollowCommitOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            int r = round;
            long certId = createCert("INS-CV%d".formatted(r), "1", "0");
            submit("W-C%d".formatted(r), "INS-CV%d".formatted(r), "1", "0", "9", "alice");
            revise("W-C%d".formatted(r), "alice", 1, "rc-%d".formatted(r), "2", "0", "9");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return versionedRelease("carol",
                        "{\"items\":[{\"measurementKey\":\"W-C%d\",\"revision\":2}]}".formatted(r))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record rr "
                            + "JOIN measurement m ON m.id = rr.measurement_id "
                            + "WHERE m.measurement_key = ?",
                    Integer.class, "W-C%d".formatted(r));
            // 撤销已提交（本轮撤销先启动且必然提交）：撤销后放行的结果不得进入当前可用集合
            mvc.perform(get("/api/measurements/usable")
                            .param("instrumentId", "INS-CV%d".formatted(r)))
                    .andExpect(jsonPath("$.length()").value(0));
            if (releaseCount > 0) {
                // 放行先于撤销提交时仅可能为 200，v2 状态 RELEASED 但证书已撤销 → 不可用
                assertEquals(200, releaseStatus);
            } else {
                assertEquals(409, releaseStatus);
            }
        }
    }
}
