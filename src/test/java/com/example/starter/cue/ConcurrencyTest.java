package com.example.starter.cue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 并发回报与并发结束的真实H2事务测试。 */
class ConcurrencyTest extends CueTestSupport {

    @Test
    void twoOperatorsReportingSameCueLeaveExactlyOneSuccess() throws Exception {
        long versionId = frozenVersion(List.of(
                cue(1, CueType.LIGHT, "开幕", 30),
                cue(2, CueType.SOUND, "雷声", 5)));
        long sessionId = runningSession(versionId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<CueDtos.ReportResponse> a = pool.submit(() -> {
                ready.countDown();
                go.await();
                return sessionService.report(sessionId, new CueDtos.ReportRequest("op-a", 1, true, null));
            });
            Future<CueDtos.ReportResponse> b = pool.submit(() -> {
                ready.countDown();
                go.await();
                return sessionService.report(sessionId, new CueDtos.ReportRequest("op-b", 1, true, null));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int conflicts = 0;
            int recorded = 0;
            for (Future<CueDtos.ReportResponse> f : List.of(a, b)) {
                try {
                    CueDtos.ReportResponse r = f.get(15, TimeUnit.SECONDS);
                    if ("recorded".equals(r.outcome())) {
                        recorded++;
                    }
                } catch (java.util.concurrent.ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(ConflictException.class);
                    conflicts++;
                }
            }

            // 两个操作员同时回报同一提示点，只能留下一个成功记录
            assertThat(recorded).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cue_execution WHERE session_id = ? AND seq = 1",
                    Integer.class, sessionId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_executed_seq FROM rehearsal_session WHERE id = ?",
                    Integer.class, sessionId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentNextCueAndFinishSettleByCommittedSeqAndCompleteness() throws Exception {
        long versionId = frozenVersion(List.of(cue(1, CueType.LIGHT, "唯一提示点", 10)));
        long sessionId = runningSession(versionId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<CueDtos.ReportResponse> report = pool.submit(() -> {
                ready.countDown();
                go.await();
                return sessionService.report(sessionId, new CueDtos.ReportRequest("r-1", 1, true, null));
            });
            Future<RehearsalSession> finish = pool.submit(() -> {
                ready.countDown();
                go.await();
                return sessionService.finish(sessionId);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            // 回报总能成功：结束只可能在序号1已提交后发生
            CueDtos.ReportResponse reportResult = report.get(15, TimeUnit.SECONDS);
            assertThat(reportResult.outcome()).isEqualTo("recorded");

            boolean finishRejected = false;
            try {
                RehearsalSession finished = finish.get(15, TimeUnit.SECONDS);
                assertThat(finished.status()).isEqualTo(SessionStatus.FINISHED);
            } catch (java.util.concurrent.ExecutionException ex) {
                assertThat(ex.getCause()).isInstanceOf(ConflictException.class);
                finishRejected = true;
            }

            // 最终状态必须一致：已结束则序号完整；未结束则结束请求被409拒绝且序号已提交
            RehearsalSession current = sessionService.detail(sessionId).session();
            assertThat(current.lastExecutedSeq()).isEqualTo(1);
            if (finishRejected) {
                assertThat(current.status()).isEqualTo(SessionStatus.RUNNING);
                sessionService.finish(sessionId);
                assertThat(sessionService.detail(sessionId).session().status())
                        .isEqualTo(SessionStatus.FINISHED);
            } else {
                assertThat(current.status()).isEqualTo(SessionStatus.FINISHED);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}