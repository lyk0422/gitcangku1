package com.example.starter.race;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 并发与幂等边界测试：真实并发请求打到 H2，验证版本串行化、请求去重与封榜不丢已提交变更。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RaceConcurrencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM race_snapshot");
        jdbc.update("DELETE FROM penalty");
        jdbc.update("DELETE FROM participant");
        jdbc.update("DELETE FROM race");
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未及时关闭");
    }

    private void createRace(String requestId, String raceId) throws Exception {
        mvc.perform(post("/api/races").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"raceId\":\"" + raceId
                                + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }

    private MvcResult register(String raceId, String requestId, String bib, long expectedVersion)
            throws Exception {
        return mvc.perform(post("/api/races/{raceId}/participants", raceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"bib\":\"" + bib
                        + "\",\"expectedVersion\":" + expectedVersion + "}")).andReturn();
    }

    /**
     * 并发提交一批任务并等待全部完成（带超时），返回各请求 HTTP 状态码与响应体。
     */
    private List<MvcResult> runConcurrently(List<java.util.concurrent.Callable<MvcResult>> tasks)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (java.util.concurrent.Callable<MvcResult> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS), "并发起点等待超时");
                return task.call();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "并发任务未就绪");
        start.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    void concurrentWritesWithSameExpectedVersion_exactlyOneSucceeds() throws Exception {
        createRace("req-cc1", "RC1");
        int threads = 8;
        List<java.util.concurrent.Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String bib = "B" + i;
            String reqId = "req-cw-" + i;
            tasks.add(() -> register("RC1", reqId, bib, 1));
        }
        List<MvcResult> results = runConcurrently(tasks);

        long ok = results.stream().filter(r -> r.getResponse().getStatus() == 200).count();
        long conflict = results.stream().filter(r -> r.getResponse().getStatus() == 409).count();
        assertEquals(1, ok, "同一期望版本下应只有一个写成功");
        assertEquals(threads - 1, conflict, "其余写应因版本冲突返回409");

        Integer version = jdbc.queryForObject(
                "SELECT version FROM race WHERE race_id = 'RC1'", Integer.class);
        assertEquals(2, version, "版本只应前进一次");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM participant WHERE race_id = 'RC1'", Integer.class);
        assertEquals(1, count, "只应登记一名选手");
    }

    @Test
    void concurrentSameRequestId_replayedExactlyOnce() throws Exception {
        createRace("req-cc2", "RC2");
        int threads = 8;
        List<java.util.concurrent.Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> register("RC2", "req-dup", "A", 1));
        }
        List<MvcResult> results = runConcurrently(tasks);

        List<String> bodies = new ArrayList<>();
        for (MvcResult r : results) {
            assertEquals(200, r.getResponse().getStatus(), "同键同参应全部重放成功");
            bodies.add(r.getResponse().getContentAsString());
        }
        assertEquals(1, bodies.stream().distinct().count(), "重放响应应与首次成功响应一致");

        Integer version = jdbc.queryForObject(
                "SELECT version FROM race WHERE race_id = 'RC2'", Integer.class);
        assertEquals(2, version, "业务变更只应生效一次");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM participant WHERE race_id = 'RC2'", Integer.class);
        assertEquals(1, count, "选手只应被登记一次");
        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-dup'", Integer.class);
        assertEquals(1, logs, "去重记录只应有一条");
    }

    @Test
    void sealConcurrentWithPenalty_noCommittedChangeLost() throws Exception {
        createRace("req-cc3", "RC3");
        register("RC3", "req-cr1", "A", 1);
        register("RC3", "req-cr2", "B", 2);

        // 版本3上并发：封榜 vs 对B取消资格，二者只能成功其一，且先提交者不得被覆盖
        List<java.util.concurrent.Callable<MvcResult>> tasks = List.of(
                () -> mvc.perform(post("/api/races/{raceId}/seal", "RC3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-seal\",\"expectedVersion\":3}"))
                        .andReturn(),
                () -> mvc.perform(post("/api/races/{raceId}/penalties", "RC3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-pen\",\"penaltyId\":\"PX\",\"bib\":\"B\","
                                + "\"type\":\"DISQUALIFY\",\"expectedVersion\":3}"))
                        .andReturn());
        List<MvcResult> results = runConcurrently(tasks);
        int sealStatus = results.get(0).getResponse().getStatus();
        int penaltyStatus = results.get(1).getResponse().getStatus();
        assertTrue(sealStatus == 200 || sealStatus == 409, "封榜应成功或冲突");
        assertTrue(penaltyStatus == 200 || penaltyStatus == 409, "处罚应成功或冲突");
        assertTrue(sealStatus == 200 ^ penaltyStatus == 200,
                "同一版本上封榜与处罚应恰好一个成功");

        String raceStatus = jdbc.queryForObject(
                "SELECT status FROM race WHERE race_id = 'RC3'", String.class);
        long version = jdbc.queryForObject(
                "SELECT version FROM race WHERE race_id = 'RC3'", Long.class);

        if (penaltyStatus == 200) {
            // 处罚先提交：封榜因版本冲突失败，赛事仍 OPEN；用新版本封榜后快照必须包含取消资格
            assertEquals("OPEN", raceStatus);
            assertEquals(4, version);
            mvc.perform(post("/api/races/{raceId}/seal", "RC3")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestId\":\"req-seal2\",\"expectedVersion\":4}"));
            String bStatus = jdbc.queryForObject(
                    "SELECT status FROM race_snapshot WHERE race_id = 'RC3' AND bib = 'B'",
                    String.class);
            assertEquals("DISQUALIFIED", bStatus, "已提交的取消资格不得被遗漏");
        } else {
            // 封榜先提交：处罚被拒绝（已封榜或版本冲突），快照中B仍正常排名
            assertEquals("SEALED", raceStatus);
            assertEquals(3, version);
            String bStatus = jdbc.queryForObject(
                    "SELECT status FROM race_snapshot WHERE race_id = 'RC3' AND bib = 'B'",
                    String.class);
            assertEquals("RANKED", bStatus);
            Integer penaltyCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM penalty WHERE penalty_id = 'PX'", Integer.class);
            assertEquals(0, penaltyCount, "失败的处罚不得落库");
        }
    }

    @Test
    void standingsQueryDuringWrites_staysConsistent() throws Exception {
        createRace("req-cc4", "RC4");
        register("RC4", "req-cq1", "A", 1);
        // 读查询在写入后应看到一致的版本与成绩
        mvc.perform(get("/api/races/{raceId}/standings", "RC4"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.version").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.standings[0].status").value("UNTIMED"));
    }
}
