package com.example.starter.cue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import com.example.starter.cue.dto.CreateSessionRequest;
import com.example.starter.cue.dto.CreateVersionRequest;
import com.example.starter.cue.dto.CuePointRequest;
import com.example.starter.cue.dto.ReportCueRequest;
import com.example.starter.cue.dto.ReportResponse;
import com.example.starter.cue.dto.SessionDetailResponse;
import com.example.starter.cue.dto.SessionResponse;
import com.example.starter.cue.dto.VersionDiffResponse;
import com.example.starter.cue.dto.VersionResponse;
import com.example.starter.cue.support.ClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2（MODE=MySQL）上的全链路测试：建版本、冻结、顺序回报、幂等、快照隔离、版本差异，
 * 通过 HTTP 接口断言状态码，并核对数据库最终数据，而不是只看打印输出。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CueRehearsalIntegrationTest {

    private static final String PLAY = "PLAY-IT";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ClockConfig.BUSINESS_ZONE);
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM cue_report");
        jdbcTemplate.update("DELETE FROM cue_snapshot");
        jdbcTemplate.update("DELETE FROM rehearsal_session");
        jdbcTemplate.update("DELETE FROM cue_item");
        jdbcTemplate.update("DELETE FROM cue_version");
    }

    private CuePointRequest cue(int seq, String type, String word, int duration) {
        return new CuePointRequest(seq, type, word, duration);
    }

    private VersionResponse createVersion(String play, List<CuePointRequest> cues) {
        ResponseEntity<VersionResponse> resp = rest.postForEntity(
                "/api/cue-versions", new CreateVersionRequest(play, cues), VersionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private SessionResponse createSession(String sessionCode, Integer versionNo) {
        ResponseEntity<SessionResponse> resp = rest.postForEntity(
                "/api/rehearsal-sessions",
                new CreateSessionRequest(PLAY, sessionCode, versionNo),
                SessionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private SessionResponse post(String path) {
        ResponseEntity<SessionResponse> resp = rest.postForEntity(path, null, SessionResponse.class);
        assertThat(resp.getStatusCode().value()).isBetween(200, 299);
        return resp.getBody();
    }

    private SessionDetailResponse detail(String sessionCode) {
        ResponseEntity<SessionDetailResponse> resp = rest.getForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/" + sessionCode,
                SessionDetailResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @Test
    @DisplayName("主流程：跳号被拒，失败记录原因不推进，顺序成功后自动结束，结果可查")
    void orderedReportsWithFailureThenAutoFinish() {
        VersionResponse v1 = createVersion(PLAY, List.of(
                cue(1, "LIGHT", "灯光一", 10),
                cue(2, "SOUND", "音效二", 20),
                cue(3, "LIGHT", "灯光三", 30)));
        SessionResponse session = createSession("S1", v1.versionNo());

        // 开始前不能回报
        ResponseEntity<ReportResponse> beforeStart = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports",
                new ReportCueRequest(1, true, null, "r-pre", null),
                ReportResponse.class);
        assertThat(beforeStart.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        post("/api/plays/" + PLAY + "/rehearsal-sessions/S1/start");

        // 先回报 2 必须被拒（跳号）
        ResponseEntity<ReportResponse> jump = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports",
                new ReportCueRequest(2, true, null, "r-jump", null),
                ReportResponse.class);
        assertThat(jump.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 回报 1 成功
        ReportCueRequest r1 = new ReportCueRequest(1, true, null, "req-1", "op-a");
        ResponseEntity<ReportResponse> ok1 = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports", r1, ReportResponse.class);
        assertThat(ok1.getStatusCode().value()).isBetween(200, 299);
        assertThat(ok1.getBody().replayed()).isFalse();
        assertThat(detail("S1").session().executedSeq()).isEqualTo(1);

        // 回报 2 失败：记录原因但不推进
        ReportCueRequest r2Fail = new ReportCueRequest(2, false, "音轨未加载", "req-2-fail", "op-b");
        ResponseEntity<ReportResponse> fail2 = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports", r2Fail, ReportResponse.class);
        assertThat(fail2.getStatusCode().value()).isBetween(200, 299);
        assertThat(fail2.getBody().success()).isFalse();
        assertThat(detail("S1").session().executedSeq()).isEqualTo(1);

        // 失败回报必须带原因（400）
        ResponseEntity<String> noReason = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports",
                new ReportCueRequest(2, false, "  ", "req-2-noreason", null),
                String.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 回报 2 成功，随后 3 成功，最后一条自动结束
        ReportCueRequest r2 = new ReportCueRequest(2, true, null, "req-2", "op-b");
        ResponseEntity<ReportResponse> ok2 = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports", r2, ReportResponse.class);
        assertThat(ok2.getStatusCode().value()).isBetween(200, 299);
        ReportCueRequest r3 = new ReportCueRequest(3, true, null, "req-3", "op-a");
        ResponseEntity<ReportResponse> ok3 = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports", r3, ReportResponse.class);
        assertThat(ok3.getStatusCode().value()).isBetween(200, 299);

        SessionDetailResponse detail = detail("S1");
        assertThat(detail.session().status()).isEqualTo("FINISHED");
        assertThat(detail.session().executedSeq()).isEqualTo(3);
        assertThat(detail.session().completedAt()).isEqualTo(LocalDateTime.parse("2026-10-01T20:00:00"));
        assertThat(detail.cueResults()).hasSize(3);
        assertThat(detail.cueResults()).extracting(SessionDetailResponse.CueResultView::state)
                .containsExactly("SUCCESS", "SUCCESS", "SUCCESS");
        // 序号 2 保留一条失败原因和一条成功
        SessionDetailResponse.CueResultView cue2 = detail.cueResults().get(1);
        assertThat(cue2.failReasons()).hasSize(1);
        assertThat(cue2.failReasons().get(0).failReason()).isEqualTo("音轨未加载");
        assertThat(cue2.successAt()).isNotNull();

        // 成功记录每个序号恰好一条
        Integer successRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cue_report WHERE session_id = ? AND success = TRUE",
                Integer.class, detail.session().id());
        assertThat(successRows).isEqualTo(3);

        // 结束后再回报被拒
        ResponseEntity<ReportResponse> afterFinish = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S1/reports",
                new ReportCueRequest(3, true, null, "req-late", null),
                ReportResponse.class);
        assertThat(afterFinish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("幂等：同 requestId 同参重放原回报，异参 409，被跳号拒绝不消耗幂等键")
    void idempotentReplayAndRejectedRequestDoesNotConsumeKey() {
        createVersion(PLAY, List.of(cue(1, "LIGHT", "一", 10), cue(2, "LIGHT", "二", 10)));
        createSession("S2", 1);
        post("/api/plays/" + PLAY + "/rehearsal-sessions/S2/start");

        // 同一 requestId 先用于跳号（序号 2），应被 409 拒绝
        ResponseEntity<ReportResponse> jump = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S2/reports",
                new ReportCueRequest(2, true, null, "shared-key", null),
                ReportResponse.class);
        assertThat(jump.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 幂等键未被消耗：同一 requestId 回报序号 1 成功
        ReportCueRequest first = new ReportCueRequest(1, true, null, "shared-key", "op-a");
        ResponseEntity<ReportResponse> ok = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S2/reports", first, ReportResponse.class);
        assertThat(ok.getStatusCode().value()).isBetween(200, 299);
        long firstId = ok.getBody().id();

        // 同参重放：返回原回报，replayed=TRUE，不新增记录
        ResponseEntity<ReportResponse> replay = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S2/reports", first, ReportResponse.class);
        assertThat(replay.getStatusCode().value()).isBetween(200, 299);
        assertThat(replay.getBody().replayed()).isTrue();
        assertThat(replay.getBody().id()).isEqualTo(firstId);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cue_report WHERE request_id = 'shared-key'", Integer.class);
        assertThat(rows).isEqualTo(1);

        // 异参重放（改序号）：409
        ReportCueRequest tampered = new ReportCueRequest(2, true, null, "shared-key", "op-a");
        ResponseEntity<ReportResponse> conflict = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S2/reports", tampered, ReportResponse.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 异参 409 同样不新增记录
        rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cue_report WHERE request_id = 'shared-key'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("编排修改只产新版本；已开始场次冻结快照不变；未开始场次可重新绑定最新版本")
    void newVersionDoesNotChangeFrozenSnapshot() {
        VersionResponse v1 = createVersion(PLAY, List.of(
                cue(1, "LIGHT", "旧口令", 10), cue(2, "LIGHT", "旧二", 10)));
        SessionResponse frozen = createSession("FROZEN", 1);
        post("/api/plays/" + PLAY + "/rehearsal-sessions/FROZEN/start");

        // 未开始场次（暂不绑定）
        SessionResponse pending = createSession("PENDING", null);
        assertThat(pending.boundVersionId()).isNull();

        VersionResponse v2 = createVersion(PLAY, List.of(
                cue(1, "SOUND", "新口令", 99), cue(2, "LIGHT", "新二", 10), cue(3, "LIGHT", "新增三", 10)));

        // 已开始场次快照仍是 v1 内容
        SessionDetailResponse frozenDetail = detail("FROZEN");
        assertThat(frozenDetail.session().frozenVersionId()).isEqualTo(v1.id());
        assertThat(frozenDetail.frozenSequence()).hasSize(2);
        assertThat(frozenDetail.frozenSequence().get(0).triggerWord()).isEqualTo("旧口令");
        assertThat(frozenDetail.frozenSequence().get(0).durationSeconds()).isEqualTo(10);
        // 运行中改绑被拒
        ResponseEntity<SessionResponse> rebindRunning = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/FROZEN/bind",
                new com.example.starter.cue.dto.BindVersionRequest(2), SessionResponse.class);
        assertThat(rebindRunning.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 未开始场次绑定最新版本 v2 并冻结，得到新序列
        ResponseEntity<SessionResponse> bindResp = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/PENDING/bind",
                new com.example.starter.cue.dto.BindVersionRequest(null), SessionResponse.class);
        assertThat(bindResp.getStatusCode().value()).isBetween(200, 299);
        assertThat(bindResp.getBody().boundVersionId()).isEqualTo(v2.id());
        post("/api/plays/" + PLAY + "/rehearsal-sessions/PENDING/start");
        SessionDetailResponse pendingDetail = detail("PENDING");
        assertThat(pendingDetail.frozenSequence()).hasSize(3);
        assertThat(pendingDetail.frozenSequence().get(0).triggerWord()).isEqualTo("新口令");
    }

    @Test
    @DisplayName("整版本校验：无效时长或重复序号使整个版本不能创建（真实库唯一约束同时生效）")
    void invalidVersionRejectedEntirely() {
        ResponseEntity<String> badDuration = rest.postForEntity("/api/cue-versions",
                new CreateVersionRequest(PLAY, List.of(cue(1, "LIGHT", "一", 601))), String.class);
        assertThat(badDuration.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> duplicateSeq = rest.postForEntity("/api/cue-versions",
                new CreateVersionRequest(PLAY, List.of(
                        cue(1, "LIGHT", "一", 10), cue(1, "SOUND", "重复", 10))), String.class);
        assertThat(duplicateSeq.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 失败后不允许残留半截版本或提示点
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cue_version WHERE play_code = '" + PLAY + "'", Integer.class);
        assertThat(versionCount).isZero();
    }

    @Test
    @DisplayName("手动结束：未完整执行 409，已结束幂等成功；版本差异可查询")
    void finishRulesAndVersionDiff() {
        createVersion(PLAY, List.of(cue(1, "LIGHT", "一", 10), cue(2, "LIGHT", "二", 10)));
        createSession("S3", 1);

        // 未开始不能结束
        ResponseEntity<SessionResponse> notStarted = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S3/finish", null, SessionResponse.class);
        assertThat(notStarted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        post("/api/plays/" + PLAY + "/rehearsal-sessions/S3/start");
        // 未执行完不能结束
        ResponseEntity<SessionResponse> incomplete = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S3/finish", null, SessionResponse.class);
        assertThat(incomplete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        rest.postForEntity("/api/plays/" + PLAY + "/rehearsal-sessions/S3/reports",
                new ReportCueRequest(1, true, null, "f-1", null), ReportResponse.class);
        rest.postForEntity("/api/plays/" + PLAY + "/rehearsal-sessions/S3/reports",
                new ReportCueRequest(2, true, null, "f-2", null), ReportResponse.class);
        // 末条成功已自动结束，再调结束幂等成功
        ResponseEntity<SessionResponse> finishAgain = rest.postForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/S3/finish", null, SessionResponse.class);
        assertThat(finishAgain.getStatusCode().value()).isBetween(200, 299);
        assertThat(finishAgain.getBody().status()).isEqualTo("FINISHED");

        // 新版本：1 改时长、2 不变、新增 3
        createVersion(PLAY, List.of(
                cue(1, "LIGHT", "一", 88), cue(2, "LIGHT", "二", 10), cue(3, "SOUND", "三", 10)));
        ResponseEntity<VersionDiffResponse> diffResp = rest.getForEntity(
                "/api/plays/" + PLAY + "/cue-versions/diff?from=1&to=2", VersionDiffResponse.class);
        assertThat(diffResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        VersionDiffResponse diff = diffResp.getBody();
        assertThat(diff.added()).extracting(VersionResponse.CuePointView::seqNo).containsExactly(3);
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.changed().get(0).seqNo()).isEqualTo(1);
        assertThat(diff.unchangedCount()).isEqualTo(1);
        assertThat(diff.removed()).isEmpty();
    }

    @Test
    @DisplayName("不存在资源返回 404")
    void notFound() {
        ResponseEntity<String> missingVersion = rest.getForEntity(
                "/api/plays/" + PLAY + "/cue-versions/99", String.class);
        assertThat(missingVersion.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> missingSession = rest.getForEntity(
                "/api/plays/" + PLAY + "/rehearsal-sessions/NOPE", String.class);
        assertThat(missingSession.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
