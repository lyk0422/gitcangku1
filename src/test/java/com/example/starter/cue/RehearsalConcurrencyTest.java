package com.example.starter.cue;

import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.RehearsalSession;
import com.example.starter.cue.repo.SessionRepository;
import com.example.starter.cue.service.BusinessException;
import com.example.starter.cue.service.CueVersionService;
import com.example.starter.cue.service.RehearsalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发回报与并发结束的真实 H2 测试：行锁串行化，结果必须一致。
 */
@SpringBootTest
@Import(CueTestConfig.class)
class RehearsalConcurrencyTest {

    @Autowired
    private CueVersionService versionService;
    @Autowired
    private RehearsalService rehearsal;
    @Autowired
    private SessionRepository sessions;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cue_execution");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
    }

    private long runningSession(int cueCount) {
        List<CueItem> cues = new java.util.ArrayList<>();
        for (int i = 1; i <= cueCount; i++) {
            cues.add(new CueItem(0, 0, i, "LIGHT", "口令" + i, 10));
        }
        long versionId = versionService.createVersion("茶馆", cues);
        versionService.freezeVersion(versionId);
        long sessionId = rehearsal.createSession("茶馆", null);
        rehearsal.start(sessionId);
        return sessionId;
    }

    @Test
    void concurrentReportsOnSameCueLeaveExactlyOneSuccess() throws Exception {
        long sessionId = runningSession(3);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "req-" + i;
            String operator = "op-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    rehearsal.report(sessionId, requestId, 1, operator, "SUCCESS", null);
                    succeeded.incrementAndGet();
                } catch (BusinessException e) {
                    conflicted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, succeeded.get(), "只能有一个操作员回报成功");
        assertEquals(threads - 1, conflicted.get());
        assertEquals(1, sessions.countSuccess(sessionId));
        assertEquals(1, sessions.maxSuccessSeq(sessionId));
    }

    @Test
    void concurrentLastReportAndEndAreConsistent() throws Exception {
        long sessionId = runningSession(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger reportSucceeded = new AtomicInteger();
        Future<?> reporter = pool.submit(() -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
                rehearsal.report(sessionId, "r1", 1, "op-a", "SUCCESS", null);
                reportSucceeded.incrementAndGet();
            } catch (BusinessException e) {
                // 场次先结束则回报被拒，属于合法结果
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Future<RehearsalSession> ender = pool.submit(() -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return rehearsal.end(sessionId);
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        reporter.get(15, TimeUnit.SECONDS);
        RehearsalSession ended = ender.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        int committed = sessions.countSuccess(sessionId);
        assertEquals(reportSucceeded.get(), committed, "已提交序号必须与成功回报一致");
        assertEquals(committed == 1, ended.completed(),
                "结束结论只能按已提交序号和完整性决定");
        assertEquals(RehearsalSession.STATUS_ENDED, ended.status());
    }
}