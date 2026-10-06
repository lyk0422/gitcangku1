package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.example.starter.calibration.SessionTestSupport.append;
import static com.example.starter.calibration.SessionTestSupport.close;
import static com.example.starter.calibration.SessionTestSupport.createHysteresisCert;
import static com.example.starter.calibration.SessionTestSupport.createSession;
import static com.example.starter.calibration.SessionTestSupport.releaseSessions;
import static com.example.starter.calibration.SessionTestSupport.sessionJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 迟滞会话并发测试：并发追加按版本恰一个成功；同 requestId 并发重放不泄露唯一键异常；
 * 关闭与追加原子互斥；并发会话放行恰一批成功。
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
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_session");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private List<Future<Integer>> runConcurrent(int threads, ThrowingCall call) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            results.add(pool.submit(() -> {
                start.await();
                return call.run(index);
            }));
        }
        start.countDown();
        pool.shutdown();
        return results;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        int run(int index) throws Exception;
    }

    @Test
    void concurrentAppendsWithSameVersionExactlyOneWins() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());

        int threads = 6;
        int created = 0;
        int conflict = 0;
        for (Future<Integer> f : runConcurrent(threads,
                i -> append(mvc, "REQ-A" + i, "S-1", 0, "10", "UP", "alice")
                        .andReturn().getResponse().getStatus())) {
            int status = f.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflict++;
            }
        }
        assertEquals(1, created, "同版本并发追加必须恰一个成功");
        assertEquals(threads - 1, conflict, "其余必须为 409 版本冲突");
        Integer version = jdbc.queryForObject(
                "SELECT version FROM calibration_session WHERE session_key = 'S-1'", Integer.class);
        assertEquals(1, version);
        Integer entries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key LIKE 'S-1#%'", Integer.class);
        assertEquals(1, entries);
    }

    @Test
    void concurrentSameRequestIdReplaysWithoutLeakingUniqueKey() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        String json = sessionJson("REQ-SAME", "S-1", "INS-H", certId, "0", "100", "alice");

        int threads = 6;
        List<String> bodies = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                var result = mvc.perform(post("/api/sessions")
                                .contentType(APPLICATION_JSON).content(json))
                        .andReturn().getResponse();
                return result.getStatus() + "|" + result.getContentAsString();
            }));
        }
        start.countDown();
        for (Future<String> f : futures) {
            bodies.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        // 全部得到首次完整响应（201 + 相同响应体），无 409/500 泄露
        assertEquals(threads, bodies.stream().filter(b -> b.startsWith("201|")).count());
        assertEquals(1, bodies.stream().distinct().count(), "并发同键同参必须重放同一响应");
        Integer sessions = jdbc.queryForObject("SELECT COUNT(*) FROM calibration_session", Integer.class);
        assertEquals(1, sessions);
        Integer idem = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Integer.class);
        assertEquals(1, idem);
    }

    @Test
    void concurrentCloseAndAppendAreAtomic() throws Exception {
        for (int round = 0; round < 10; round++) {
            String key = "S-R" + round;
            long certId = createHysteresisCert(mvc, "INS-H" + round, "1", "1", "-1");
            createSession(mvc, "REQ-C" + round, key, "INS-H" + round, certId, "0", "100", "alice")
                    .andExpect(status().isCreated());
            append(mvc, "REQ-A0-" + round, key, 0, "10", "UP", "alice").andExpect(status().isCreated());

            final int r = round;
            List<Future<Integer>> futures = runConcurrent(2, i -> i == 0
                    ? close(mvc, "REQ-CL" + r, key, 1, "alice").andReturn().getResponse().getStatus()
                    : append(mvc, "REQ-A1-" + r, key, 1, "12", null, "alice")
                            .andReturn().getResponse().getStatus());
            int closeStatus = futures.get(0).get(30, TimeUnit.SECONDS);
            int appendStatus = futures.get(1).get(30, TimeUnit.SECONDS);

            var session = jdbc.queryForMap(
                    "SELECT status, version FROM calibration_session WHERE session_key = ?", key);
            if (closeStatus == 200) {
                // 关闭先生效：追加必须 409，会话冻结在版本 1
                assertEquals(409, appendStatus);
                assertEquals("CLOSED", session.get("status"));
                assertEquals(1, ((Number) session.get("version")).intValue());
            } else {
                // 追加先生效：关闭因版本不符 409，会话保持 OPEN 版本 2
                assertEquals(409, closeStatus);
                assertEquals(201, appendStatus);
                assertEquals("OPEN", session.get("status"));
                assertEquals(2, ((Number) session.get("version")).intValue());
            }
        }
    }

    @Test
    void concurrentSessionReleaseExactlyOneWins() throws Exception {
        long certId = createHysteresisCert(mvc, "INS-H", "1", "1", "-1");
        createSession(mvc, "REQ-C1", "S-1", "INS-H", certId, "0", "100", "alice")
                .andExpect(status().isCreated());
        append(mvc, "REQ-A1", "S-1", 0, "10", "UP", "alice").andExpect(status().isCreated());
        append(mvc, "REQ-A2", "S-1", 1, "12", null, "alice").andExpect(status().isCreated());
        close(mvc, "REQ-CL1", "S-1", 2, "alice").andExpect(status().isOk());

        int threads = 4;
        int ok = 0;
        int rejected = 0;
        for (Future<Integer> f : runConcurrent(threads,
                i -> releaseSessions(mvc, "REQ-R" + i, "carol", "S-1")
                        .andReturn().getResponse().getStatus())) {
            int status = f.get(30, TimeUnit.SECONDS);
            if (status == 200) {
                ok++;
            } else if (status == 422) {
                rejected++;
            }
        }
        assertEquals(1, ok, "并发会话放行必须恰一批成功");
        assertEquals(threads - 1, rejected);
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(2, releaseCount, "两条成员各一条放行历史，不得重复");
        Integer released = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE session_id IS NOT NULL AND status = 'RELEASED'",
                Integer.class);
        assertTrue(released == 2);
    }
}
