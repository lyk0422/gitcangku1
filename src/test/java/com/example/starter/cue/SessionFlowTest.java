package com.example.starter.cue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 排练场次主流程：冻结快照、按序回报、失败原因、幂等重放、结束条件。 */
class SessionFlowTest extends CueTestSupport {

    private long standardSession() {
        long versionId = frozenVersion(List.of(
                cue(1, CueType.LIGHT, "开幕", 30),
                cue(2, CueType.SOUND, "雷声", 5),
                cue(3, CueType.LIGHT, "谢幕", 20)));
        return runningSession(versionId);
    }

    @Test
    void outOfOrderReportRejectedThenAcceptedInOrder() {
        long sessionId = standardSession();

        // 顺序1、2、3，先回报2应被拒
        assertThatThrownBy(() -> sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-skip", 2, true, null)))
                .isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT last_executed_seq FROM rehearsal_session WHERE id = ?",
                Integer.class, sessionId)).isZero();

        // 回报1后才可执行2
        CueDtos.ReportResponse first = sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-1", 1, true, null));
        assertThat(first.outcome()).isEqualTo("recorded");
        CueDtos.ReportResponse second = sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-2", 2, true, null));
        assertThat(second.lastExecutedSeq()).isEqualTo(2);
    }

    @Test
    void sameRequestIdReplayReturnsOriginalAndDifferentParamsConflict() {
        long sessionId = standardSession();
        sessionService.report(sessionId, new CueDtos.ReportRequest("r-idem", 1, true, null));

        CueDtos.ReportResponse replay = sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-idem", 1, true, null));
        assertThat(replay.outcome()).isEqualTo("replayed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cue_execution WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(1);

        assertThatThrownBy(() -> sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-idem", 2, true, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("参数不一致");
    }

    @Test
    void failureRecordsReasonWithoutAdvancingAndDoesNotConsumeRequestId() {
        long sessionId = standardSession();
        sessionService.report(sessionId, new CueDtos.ReportRequest("r-1", 1, true, null));

        CueDtos.ReportResponse failed = sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-2", 2, false, "音响未就位"));
        assertThat(failed.outcome()).isEqualTo("failed");
        assertThat(failed.lastExecutedSeq()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reason FROM cue_failure WHERE session_id = ?",
                String.class, sessionId)).isEqualTo("音响未就位");

        // 失败不消耗幂等键：同一requestId随后可用于成功回报
        CueDtos.ReportResponse success = sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-2", 2, true, null));
        assertThat(success.outcome()).isEqualTo("recorded");
        assertThat(success.lastExecutedSeq()).isEqualTo(2);
    }

    @Test
    void failureWithoutReasonRejected() {
        long sessionId = standardSession();
        assertThatThrownBy(() -> sessionService.report(sessionId,
                new CueDtos.ReportRequest("r-x", 1, false, " ")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void versionEditAfterStartDoesNotChangeFrozenSnapshot() {
        long sessionId = standardSession();
        long v2 = frozenVersion(List.of(cue(1, CueType.SOUND, "全新编排", 99)));

        CueDtos.SessionDetail detail = sessionService.detail(sessionId);

        assertThat(detail.cues()).hasSize(3);
        assertThat(detail.cues().get(0).cue().triggerPhrase()).isEqualTo("开幕");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_cue WHERE session_id = ?",
                Integer.class, sessionId)).isEqualTo(3);
        assertThat(v2).isNotEqualTo(detail.session().versionId());
    }

    @Test
    void finishRequiresAllCuesExecuted() {
        long sessionId = standardSession();
        sessionService.report(sessionId, new CueDtos.ReportRequest("r-1", 1, true, null));

        assertThatThrownBy(() -> sessionService.finish(sessionId))
                .isInstanceOf(ConflictException.class);

        sessionService.report(sessionId, new CueDtos.ReportRequest("r-2", 2, true, null));
        sessionService.report(sessionId, new CueDtos.ReportRequest("r-3", 3, true, null));
        RehearsalSession finished = sessionService.finish(sessionId);

        assertThat(finished.status()).isEqualTo(SessionStatus.FINISHED);
        assertThat(finished.finishedAt()).isNotNull();
    }

    @Test
    void rebindOnlyBeforeStartAndRevalidatesAllCues() {
        long v1 = frozenVersion(List.of(cue(1, CueType.LIGHT, "开幕", 30)));
        long sessionId = sessionService.createSession(new CueDtos.CreateSessionRequest(v1));
        long v2 = frozenVersion(List.of(cue(1, CueType.SOUND, "新版", 10)));

        RehearsalSession rebound = sessionService.rebindToLatest(sessionId);
        assertThat(rebound.versionId()).isEqualTo(v2);

        sessionService.start(sessionId);
        assertThatThrownBy(() -> sessionService.rebindToLatest(sessionId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createSessionRejectsInvalidVersion() {
        long badVersion = cueService.createVersion(new CueDtos.CreateVersionRequest(List.of(
                cue(1, CueType.LIGHT, "超时", 700))));

        assertThatThrownBy(() -> sessionService.createSession(new CueDtos.CreateSessionRequest(badVersion)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void detailShowsExecutionAndFailurePerCue() {
        long sessionId = standardSession();
        sessionService.report(sessionId, new CueDtos.ReportRequest("r-1", 1, true, null));
        sessionService.report(sessionId, new CueDtos.ReportRequest("r-2", 2, false, "设备故障"));

        CueDtos.SessionDetail detail = sessionService.detail(sessionId);

        assertThat(detail.cues().get(0).execution()).isNotNull();
        assertThat(detail.cues().get(1).execution()).isNull();
        assertThat(detail.cues().get(1).failures()).hasSize(1);
        assertThat(detail.cues().get(1).failures().get(0).reason()).isEqualTo("设备故障");
        assertThat(detail.cues().get(2).execution()).isNull();
    }
}