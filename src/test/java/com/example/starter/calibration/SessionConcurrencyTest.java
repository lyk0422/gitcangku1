package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 会话并发边界测试：并发同版本追加恰好一条成功；并发同 requestId 创建只产生一个会话且不泄露唯一键异常；
 * 关闭与追加竞态最终状态一致；会话放行与证书撤销按提交顺序生效。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM session_entry");
        jdbc.update("DELETE FROM calibration_session");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates/hysteresis").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","slope":"1","upOffset":"1","downOffset":"-1"}
                                """.formatted(UUID.randomUUID(), instrument)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long createSession(String instrument, long certId) throws Exception {
        String body = mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","instrumentId":"%s","certificateId":%d}
                                """.formatted(UUID.randomUUID(), instrument, certId)))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private String appendJson(String requestId, long version, String raw) {
        return """
                {"requestId":"%s","expectedSessionVersion":%d,"rawValue":"%s","direction":"UP",
                 "lowerLimit":"0","upperLimit":"1000","submittedBy":"alice"}
                """.formatted(requestId, version, raw);
    }

    @Test
    void concurrentAppendsSameVersionExactlyOneWins() throws Exception {
        long sessionId = createSession("INS-C", createCert("INS-C"));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/sessions/{id}/entries", sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(appendJson(UUID.randomUUID().toString(), 0, String.valueOf(10 + index))))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok.incrementAndGet();
            } else if (status == 409) {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, ok.get(), "并发同版本追加必须恰好一条成功");
        assertEquals(threads - 1, conflict.get(), "其余必须为 409 版本冲突");
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM calibration_session WHERE id = ?", Integer.class, sessionId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_entry WHERE session_id = ?", Integer.class, sessionId));
    }

    @Test
    void concurrentSameRequestIdCreateYieldsSingleSession() throws Exception {
        long certId = createCert("INS-K");
        String requestId = UUID.randomUUID().toString();
        String json = """
                {"requestId":"%s","instrumentId":"INS-K","certificateId":%d}
                """.formatted(requestId, certId);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                var response = mvc.perform(post("/api/sessions")
                                .contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn().getResponse();
                return response.getStatus() + "|" + response.getContentAsString();
            }));
        }
        start.countDown();
        Set<Long> sessionIds = new HashSet<>();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (Future<String> future : results) {
            String[] parts = future.get(30, TimeUnit.SECONDS).split("\\|", 2);
            int status = Integer.parseInt(parts[0]);
            assertTrue(status == 201 || status == 409, "并发同键只允许 201 或 409，不得泄露唯一键异常: " + status);
            if (status == 201) {
                ok.incrementAndGet();
                sessionIds.add(((Number) JsonPath.read(parts[1], "$.id")).longValue());
            } else {
                conflict.incrementAndGet();
            }
        }
        pool.shutdown();
        assertTrue(ok.get() >= 1, "至少一个请求成功");
        assertEquals(1, sessionIds.size(), "所有成功响应必须指向同一会话");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM calibration_session", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = ?", Integer.class, requestId));
    }

    @Test
    void concurrentCloseAndAppendSettleConsistently() throws Exception {
        for (int round = 0; round < 10; round++) {
            long sessionId = createSession("INS-R" + round, createCert("INS-R" + round));
            mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                            .content(appendJson(UUID.randomUUID().toString(), 0, "10")))
                    .andReturn().getResponse().getStatus();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> appendFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/sessions/{id}/entries", sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(appendJson(UUID.randomUUID().toString(), 1, "12")))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> closeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/sessions/{id}/close", sessionId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"" + UUID.randomUUID()
                                        + "\",\"expectedSessionVersion\":1}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int appendStatus = appendFuture.get(30, TimeUnit.SECONDS);
            int closeStatus = closeFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            // 关闭先提交则追加 409 SESSION_CLOSED；追加先提交则关闭 409 VERSION_CONFLICT；二者必居其一
            assertTrue((appendStatus == 409 && closeStatus == 200) || (appendStatus == 200 && closeStatus == 409),
                    "关闭与追加竞态必须恰好一个成功: append=" + appendStatus + " close=" + closeStatus);
            var row = jdbc.queryForMap("SELECT status, version FROM calibration_session WHERE id = ?", sessionId);
            Integer entryCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM session_entry WHERE session_id = ?", Integer.class, sessionId);
            if (closeStatus == 200) {
                // 关闭先生效：1 条条目，会话已关闭
                assertEquals("CLOSED", row.get("status"));
                assertEquals(1, ((Number) row.get("version")).intValue());
            } else {
                // 追加先生效：2 条条目，会话仍开放（关闭因版本冲突失败）
                assertEquals("OPEN", row.get("status"));
                assertEquals(2, ((Number) row.get("version")).intValue());
            }
            assertEquals(((Number) row.get("version")).intValue(), entryCount.intValue(),
                    "版本必须等于实际条目数");
        }
    }

    @Test
    void concurrentSessionReleaseAndRevokeFollowCommitOrder() throws Exception {
        for (int round = 0; round < 10; round++) {
            long certId = createCert("INS-V" + round);
            long sessionId = createSession("INS-V" + round, certId);
            mvc.perform(post("/api/sessions/{id}/entries", sessionId).contentType(MediaType.APPLICATION_JSON)
                            .content(appendJson(UUID.randomUUID().toString(), 0, "10")))
                    .andReturn().getResponse().getStatus();
            mvc.perform(post("/api/sessions/{id}/close", sessionId).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"" + UUID.randomUUID()
                                    + "\",\"expectedSessionVersion\":1}"))
                    .andReturn().getResponse().getStatus();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/certificates/{id}/revoke", certId))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/sessions/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requestId\":\"" + UUID.randomUUID()
                                        + "\",\"sessionIds\":[" + sessionId + "]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 422,
                    "放行只能成功或整批 422: " + releaseStatus);

            var session = jdbc.queryForMap("SELECT status FROM calibration_session WHERE id = ?", sessionId);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                            + "WHERE m.session_id = ?", Integer.class, sessionId);
            if (releaseStatus == 200) {
                // 放行先提交：会话与成员已放行，撤销在其后生效
                assertEquals("RELEASED", session.get("status"));
                assertEquals(1, releaseCount);
            } else {
                // 撤销先提交：整批 422，会话保持已关闭，无放行历史
                assertEquals("CLOSED", session.get("status"));
                assertEquals(0, releaseCount);
            }
        }
    }
}
