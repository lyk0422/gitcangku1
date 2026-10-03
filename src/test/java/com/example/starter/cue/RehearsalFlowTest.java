package com.example.starter.cue;

import com.example.starter.cue.domain.CueExecution;
import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueVersion;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排练场次冻结、按序回报、幂等与快照隔离的真实 H2 测试。
 */
@SpringBootTest
@Import(CueTestConfig.class)
class RehearsalFlowTest {

    @Autowired
    private CueVersionService versionService;
    @Autowired
    private RehearsalService rehearsal;
    @Autowired
    private SessionRepository sessions;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private long versionId;
    private long sessionId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM cue_execution");
        jdbc.update("DELETE FROM session_cue");
        jdbc.update("DELETE FROM rehearsal_session");
        jdbc.update("DELETE FROM cue_item");
        jdbc.update("DELETE FROM cue_version");
        clock.set(CueTestConfig.T0);
        versionId = versionService.createVersion("茶馆", List.of(
                new CueItem(0, 0, 1, "LIGHT", "幕启", 30),
                new CueItem(0, 0, 2, "SOUND", "雷声", 10),
                new CueItem(0, 0, 3, "LIGHT", "幕落", 20)));
        versionService.freezeVersion(versionId);
        sessionId = rehearsal.createSession("茶馆", null);
    }

    @Test
    void outOfOrderReportRejectedThenAcceptedInOrder() {
        rehearsal.start(sessionId);
        BusinessException e = assertThrows(BusinessException.class, () ->
                rehearsal.report(sessionId, "r1", 2, "op-a", "SUCCESS", null));
        assertTrue(e.getMessage().contains("期望序号 1"), e.getMessage());
        rehearsal.report(sessionId, "r1", 1, "op-a", "SUCCESS", null);
        CueExecution second = rehearsal.report(sessionId, "r2", 2, "op-a", "SUCCESS", null);
        assertEquals(2, second.seq());
    }

    @Test
    void failedReportRecordsReasonButDoesNotAdvanceNorConsumeKey() {
        rehearsal.start(sessionId);
        CueExecution failed = rehearsal.report(sessionId, "r1", 1, "op-a", "FAILED", "灯控台无响应");
        assertEquals("FAILED", failed.result());
        assertEquals("灯控台无响应", failed.failReason());
        assertEquals(0, sessions.maxSuccessSeq(sessionId));
        // 失败不消耗幂等键：同一 requestId 可再次回报成功
        CueExecution success = rehearsal.report(sessionId, "r1", 1, "op-a", "SUCCESS", null);
        assertEquals("SUCCESS", success.result());
        assertEquals(1, sessions.maxSuccessSeq(sessionId));
    }

    @Test
    void sameRequestIdSameParamsReplaysDifferentParamsConflict() {
        rehearsal.start(sessionId);
        CueExecution first = rehearsal.report(sessionId, "r1", 1, "op-a", "SUCCESS", null);
        CueExecution replay = rehearsal.report(sessionId, "r1", 1, "op-a", "SUCCESS", null);
        assertEquals(first.id(), replay.id());
        assertEquals(1, sessions.countSuccess(sessionId));
        assertThrows(BusinessException.class, () ->
                rehearsal.report(sessionId, "r1", 2, "op-a", "SUCCESS", null));
        assertThrows(BusinessException.class, () ->
                rehearsal.report(sessionId, "r1", 1, "op-b", "SUCCESS", null));
    }

    @Test
    void snapshotFrozenAtStartAndRebindOnlyBeforeStart() {
        rehearsal.start(sessionId);
        // 新版本修改编排，不影响已开始的场次快照
        long v2 = versionService.createVersion("茶馆", List.of(
                new CueItem(0, 0, 1, "SOUND", "新口令", 99)));
        versionService.freezeVersion(v2);
        assertEquals(3, sessions.findSessionCues(sessionId).size());
        assertThrows(BusinessException.class, () -> rehearsal.rebind(sessionId));

        long pending = rehearsal.createSession("茶馆", versionId);
        rehearsal.rebind(pending);
        assertEquals(v2, sessions.findSession(pending).orElseThrow().versionId());
        rehearsal.start(pending);
        assertEquals(1, sessions.findSessionCues(pending).size());
        assertEquals("新口令", sessions.findSessionCues(pending).get(0).triggerPhrase());
    }

    @Test
    void endCompletenessAndTimeFromControlledClock() {
        rehearsal.start(sessionId);
        assertThrows(BusinessException.class, () -> rehearsal.end(sessionId + 999));
        rehearsal.report(sessionId, "r1", 1, "op-a", "SUCCESS", null);
        rehearsal.report(sessionId, "r2", 2, "op-a", "SUCCESS", null);
        clock.set(Instant.parse("2026-10-04T11:00:00Z"));
        RehearsalSession incomplete = rehearsal.end(sessionId);
        assertEquals(RehearsalSession.STATUS_ENDED, incomplete.status());
        assertFalse(incomplete.completed());
        assertEquals(incomplete.endedAt(), incomplete.endedAt());
        assertNotEquals(null, incomplete.endedAt());
        assertThrows(BusinessException.class, () ->
                rehearsal.report(sessionId, "r3", 3, "op-a", "SUCCESS", null));

        long full = rehearsal.createSession("茶馆", null);
        rehearsal.start(full);
        rehearsal.report(full, "a", 1, "op-a", "SUCCESS", null);
        rehearsal.report(full, "b", 2, "op-a", "SUCCESS", null);
        rehearsal.report(full, "c", 3, "op-a", "SUCCESS", null);
        assertTrue(rehearsal.end(full).completed());
    }

    @Test
    void describeSessionShowsFrozenSequenceAndResults() {
        rehearsal.start(sessionId);
        rehearsal.report(sessionId, "r1", 1, "op-a", "FAILED", "口令未触发");
        rehearsal.report(sessionId, "r1", 1, "op-a", "SUCCESS", null);
        Map<String, Object> view = rehearsal.describeSession(sessionId);
        List<?> cues = (List<?>) view.get("cues");
        assertEquals(3, cues.size());
        List<?> executions = (List<?>) ((Map<?, ?>) cues.get(0)).get("executions");
        assertEquals(2, executions.size());
        assertEquals(CueVersion.STATUS_FROZEN,
                versionService.describe(versionId).get("version") instanceof CueVersion v
                        ? v.status() : null);
    }
}