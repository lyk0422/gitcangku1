package com.example.starter.cue;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.cue.dto.CreateSessionRequest;
import com.example.starter.cue.dto.CreateVersionRequest;
import com.example.starter.cue.dto.CuePointRequest;
import com.example.starter.cue.dto.ReportCueRequest;
import com.example.starter.cue.dto.SessionDetailResponse;
import com.example.starter.cue.exception.CueConflictException;
import com.example.starter.cue.service.CueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 上的并发与事务测试：两个真实操作员线程经屏障同时发起操作，
 * 用 FOR UPDATE 行锁与唯一约束串行化；所有断言带超时并核对最终数据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CueConcurrencyTest {

    private static final String PLAY = "PLAY-CONC";

    @Autowired
    private CueService cueService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        jdbcTemplate.update("DELETE FROM cue_report");
        jdbcTemplate.update("DELETE FROM cue_snapshot");
        jdbcTemplate.update("DELETE FROM rehearsal_session");
        jdbcTemplate.update("DELETE FROM cue_item");
        jdbcTemplate.update("DELETE FROM cue_version");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private CuePointRequest cue(int seq) {
        return new CuePointRequest(seq, "LIGHT", "口令" + seq, seq * 10);
    }

    private String prepareRunningSession(String sessionCode, int cueCount) {
        List<CuePointRequest> cues = java.util.stream.IntStream.rangeClosed(1, cueCount)
                .mapToObj(this::cue)
                .toList();
        cueService.createVersion(new CreateVersionRequest(PLAY, cues));
        cueService.createSession(new CreateSessionRequest(PLAY, sessionCode, 1));
        cueService.startSession(PLAY, sessionCode);
        return sessionCode;
    }

    /**
     * 并发结果：accepted=TRUE 表示业务受理成功；conflictMessage 非空表示收到 409 及其原因。
     */
    private record RaceOutcome(boolean accepted, String conflictMessage) {
    }

    private RaceOutcome race(Runnable action, CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            action.run();
            return new RaceOutcome(true, null);
        } catch (CueConflictException e) {
            return new RaceOutcome(false, e.getMessage());
        } catch (Exception e) {
            throw new AssertionError("并发回报出现非预期异常: " + e, e);
        }
    }
    @Test
    @DisplayName("两个操作员同时回报同一提示点：恰好一条成功，另一条 409，失败不消耗幂等键")
    void concurrentSameCueOnlyOneSucceeds() throws Exception {
        String code = prepareRunningSession("C1", 2);

        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<RaceOutcome> opA = pool.submit(() -> race(
                () -> cueService.report(PLAY, code,
                        new ReportCueRequest(1, true, null, "key-a", "op-a")),
                barrier));
        Future<RaceOutcome> opB = pool.submit(() -> race(
                () -> cueService.report(PLAY, code,
                        new ReportCueRequest(1, true, null, "key-b", "op-b")),
                barrier));

        RaceOutcome ra = opA.get(15, TimeUnit.SECONDS);
        RaceOutcome rb = opB.get(15, TimeUnit.SECONDS);

        assertThat(ra.accepted()).as("恰好一方成功").isNotEqualTo(rb.accepted());
        RaceOutcome loser = ra.accepted() ? rb : ra;
        String loserKey = ra.accepted() ? "key-b" : "key-a";
        assertThat(loser.conflictMessage()).contains("1");

        // 数据库最终事实：序号 1 仅一条成功记录，序号只推进 1，场次仍运行
        Integer successForSeq1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cue_report cr JOIN rehearsal_session s ON cr.session_id = s.id "
                        + "WHERE s.play_code = ? AND s.session_code = ? AND cr.seq_no = 1 AND cr.success = TRUE",
                Integer.class, PLAY, code);
        assertThat(successForSeq1).isEqualTo(1);

        SessionDetailResponse detail = cueService.getSessionDetail(PLAY, code);
        assertThat(detail.session().executedSeq()).isEqualTo(1);
        assertThat(detail.session().status()).isEqualTo("RUNNING");

        // 失败方幂等键未被消耗：用它回报下一序号（异于原参数）可以成功，场次正常结束
        cueService.report(PLAY, code, new ReportCueRequest(2, true, null, loserKey, "op-retry"));
        detail = cueService.getSessionDetail(PLAY, code);
        assertThat(detail.session().status()).isEqualTo("FINISHED");
        assertThat(detail.session().executedSeq()).isEqualTo(2);

        Integer totalSuccess = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cue_report cr JOIN rehearsal_session s ON cr.session_id = s.id "
                        + "WHERE s.play_code = ? AND s.session_code = ? AND cr.success = TRUE",
                Integer.class, PLAY, code);
        assertThat(totalSuccess).isEqualTo(2);
    }

    @Test
    @DisplayName("下一提示点执行与本场结束并发：只按已提交序号与完整性决定，最终一致结束")
    void concurrentLastCueAndFinish() throws Exception {
        String code = prepareRunningSession("C2", 2);
        cueService.report(PLAY, code, new ReportCueRequest(1, true, null, "key-1", "op-a"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<RaceOutcome> lastCue = pool.submit(() -> race(
                () -> cueService.report(PLAY, code,
                        new ReportCueRequest(2, true, null, "key-2", "op-a")),
                barrier));
        Future<RaceOutcome> finish = pool.submit(() -> race(
                () -> cueService.finishSession(PLAY, code),
                barrier));

        RaceOutcome cueResult = lastCue.get(15, TimeUnit.SECONDS);
        RaceOutcome finishResult = finish.get(15, TimeUnit.SECONDS);

        // 末条提示点必须成功；结束方要么看到完整序列而成功，要么先于末条提交而 409
        assertThat(cueResult.accepted()).isTrue();
        if (!finishResult.accepted()) {
            assertThat(finishResult.conflictMessage()).contains("不能结束");
        }

        // 无论两者谁先拿到锁，最终状态唯一：已结束、执行到 2、恰好两条成功、有结束时间
        SessionDetailResponse detail = cueService.getSessionDetail(PLAY, code);
        assertThat(detail.session().status()).isEqualTo("FINISHED");
        assertThat(detail.session().executedSeq()).isEqualTo(2);
        assertThat(detail.session().completedAt()).isNotNull();
        assertThat(detail.cueResults()).extracting(SessionDetailResponse.CueResultView::state)
                .containsExactly("SUCCESS", "SUCCESS");

        // 末条成功已自动结束后，结束操作再调用一次必须幂等成功
        assertThat(cueService.finishSession(PLAY, code).status()).isEqualTo("FINISHED");
    }

    @Test
    @DisplayName("失败回报事务独立提交并保留原因，随后同序号成功后两种记录都可查")
    void failureThenSuccessBothPersisted() {
        String code = prepareRunningSession("C3", 1);

        cueService.report(PLAY, code, new ReportCueRequest(1, false, "设备掉线", "key-f1", "op-a"));
        cueService.report(PLAY, code, new ReportCueRequest(1, false, "口令未听清", "key-f2", "op-b"));

        SessionDetailResponse failing = cueService.getSessionDetail(PLAY, code);
        assertThat(failing.session().executedSeq()).isZero();
        assertThat(failing.session().status()).isEqualTo("RUNNING");
        assertThat(failing.cueResults().get(0).state()).isEqualTo("FAILED");
        assertThat(failing.cueResults().get(0).failReasons()).hasSize(2);

        cueService.report(PLAY, code, new ReportCueRequest(1, true, null, "key-ok", "op-a"));
        SessionDetailResponse done = cueService.getSessionDetail(PLAY, code);
        assertThat(done.cueResults().get(0).state()).isEqualTo("SUCCESS");
        assertThat(done.cueResults().get(0).failReasons()).hasSize(2);
        assertThat(done.session().status()).isEqualTo("FINISHED");
    }
}
