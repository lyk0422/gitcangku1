package com.example.starter.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保管链截至时刻重放引擎的确定性测试：直接写入固定时间戳，不依赖真实等待。
 * 交接/核验为 Asia/Shanghai 墙钟时间，借出/归还为 UTC；验证统一换算后的边界（含该时刻）判定。
 */
@SpringBootTest
class ChainReplayEngineTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChainReplayEngine replayEngine;

    @Autowired
    private EvidenceRepository evidenceRepository;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /**
     * 构造一条时间线（均为 2026-01-01）：
     * 入库 10:00 上海(=02:00 UTC) → 交接发起 12:00 上海(=04:00 UTC) → 接受 13:00 上海(=05:00 UTC)
     * → 核验通过 14:00 上海(=06:00 UTC) → 借出 07:00 UTC → 归还 08:00 UTC。
     */
    private String setupTimeline() {
        String ev = uniqueKey("EV");
        LocalDateTime intakeWall = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        // 已接受交接 alice→bob，证物当前保管人必须为链终点 bob（真实流程接受交接与保管人更新同事务）
        jdbcTemplate.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
                        VALUES (?, 'CASE-X', 'DOCUMENT', 'SEAL-1', 'bob', 'SEALED', ?, ?)
                        """,
                ev, intakeWall, intakeWall);
        LocalDateTime initiateWall = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        LocalDateTime decideWall = LocalDateTime.of(2026, 1, 1, 13, 0, 0);
        jdbcTemplate.update("""
                        INSERT INTO transfer_record
                            (evidence_key, from_custodian, to_custodian, status, initiated_by, created_at, decided_at)
                        VALUES (?, 'alice', 'bob', 'ACCEPTED', 'alice', ?, ?)
                        """,
                ev, initiateWall, decideWall);
        LocalDateTime inspectWall = LocalDateTime.of(2026, 1, 1, 14, 0, 0);
        jdbcTemplate.update("""
                        INSERT INTO seal_inspection
                            (evidence_key, inspector_id, passed, note, created_at)
                        VALUES (?, 'bob', 1, 'ok', ?)
                        """,
                ev, inspectWall);
        LocalDateTime loanAt = LocalDateTime.of(2026, 1, 1, 7, 0, 0);
        LocalDateTime dueAt = LocalDateTime.of(2026, 1, 1, 19, 0, 0);
        LocalDateTime returnedAt = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
        jdbcTemplate.update("""
                        INSERT INTO loan_record
                            (loan_key, evidence_key, custodian_id, borrower_id, purpose,
                             loan_at, due_at, status, seal_passed, return_note, returned_at, created_at)
                        VALUES (?, ?, 'bob', 'carol', 'review', ?, ?, 'RETURNED', 1, 'intact', ?, ?)
                        """,
                uniqueKey("LOAN"), ev, loanAt, dueAt, returnedAt, intakeWall);
        return ev;
    }

    private ChainSnapshot replayAt(String ev, int hour, int minute) {
        Evidence evidence = evidenceRepository.findByKey(ev).orElseThrow();
        return replayEngine.replay(evidence, LocalDateTime.of(2026, 1, 1, hour, minute, 0));
    }

    @Test
    void cutoffBeforeTransferUsesIntakeOnlyState() {
        String ev = setupTimeline();
        // 03:59 UTC：交接（04:00 UTC 发起）尚未发生
        ChainSnapshot snapshot = replayAt(ev, 3, 59);
        assertThat(snapshot.chainVersion()).isEqualTo(1);
        assertThat(snapshot.custodianId()).isEqualTo("alice");
        assertThat(snapshot.status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(snapshot.pendingTransfer()).isFalse();
        assertThat(snapshot.chainContinuous()).isTrue();
    }

    @Test
    void cutoffAtInitiateBoundarySeesPendingTransfer() {
        String ev = setupTimeline();
        // 04:00 UTC 恰好等于交接发起时刻（含边界）：待核验
        ChainSnapshot snapshot = replayAt(ev, 4, 0);
        assertThat(snapshot.pendingTransfer()).isTrue();
        assertThat(snapshot.status()).isEqualTo(EvidenceStatus.TRANSFER_PENDING);
        assertThat(snapshot.custodianId()).isEqualTo("alice");
        assertThat(snapshot.chainVersion()).isEqualTo(2);
    }

    @Test
    void cutoffAtAcceptBoundaryMovesCustodian() {
        String ev = setupTimeline();
        // 05:00 UTC 恰好等于接受时刻（墙钟 13:00 上海，含边界）：保管人切换为 bob
        ChainSnapshot snapshot = replayAt(ev, 5, 0);
        assertThat(snapshot.pendingTransfer()).isFalse();
        assertThat(snapshot.status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(snapshot.custodianId()).isEqualTo("bob");
        assertThat(snapshot.chainVersion()).isEqualTo(3);
        assertThat(snapshot.chainContinuous()).isTrue();
    }

    @Test
    void cutoffDuringLoanSeesBorrowedUnreturned() {
        String ev = setupTimeline();
        // 07:30 UTC：已借出（07:00）未归还（08:00）
        ChainSnapshot snapshot = replayAt(ev, 7, 30);
        assertThat(snapshot.status()).isEqualTo(EvidenceStatus.BORROWED);
        assertThat(snapshot.borrowedUnreturned()).isTrue();
        assertThat(snapshot.custodianId()).isEqualTo("bob");
        assertThat(snapshot.chainVersion()).isEqualTo(5);
    }

    @Test
    void cutoffAtReturnBoundarySealsAgainAndIncludesAllEvents() {
        String ev = setupTimeline();
        // 08:00 UTC 恰好等于归还时刻（含边界）：回到 SEALED，全链 6 个事件
        ChainSnapshot snapshot = replayAt(ev, 8, 0);
        assertThat(snapshot.borrowedUnreturned()).isFalse();
        assertThat(snapshot.status()).isEqualTo(EvidenceStatus.SEALED);
        assertThat(snapshot.sealOk()).isTrue();
        assertThat(snapshot.latestInspectionPassed()).isTrue();
        assertThat(snapshot.chainVersion()).isEqualTo(6);
        assertThat(snapshot.events().stream().map(e -> e.kind()).toList())
                .containsExactly("INTAKE", "TRANSFER", "TRANSFER", "INSPECTION",
                        "LOAN_BORROW", "LOAN_RETURN");
    }

    @Test
    void inconsistentTransferCustodianProducesChainGap() {
        String ev = uniqueKey("EV");
        LocalDateTime intakeWall = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        // 历史交接 alice→bob 已接受，但证物当前保管人为 carol（链终点应为 bob），反向回推发现缺口
        jdbcTemplate.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
                        VALUES (?, 'CASE-X', 'DOCUMENT', 'SEAL-1', 'carol', 'SEALED', ?, ?)
                        """,
                ev, intakeWall, intakeWall);
        jdbcTemplate.update("""
                        INSERT INTO transfer_record
                            (evidence_key, from_custodian, to_custodian, status, initiated_by, created_at, decided_at)
                        VALUES (?, 'alice', 'bob', 'ACCEPTED', 'alice', ?, ?)
                        """,
                ev, LocalDateTime.of(2026, 1, 1, 12, 0, 0),
                LocalDateTime.of(2026, 1, 1, 13, 0, 0));

        Evidence evidence = evidenceRepository.findByKey(ev).orElseThrow();
        ChainSnapshot snapshot = replayEngine.replay(evidence,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertThat(snapshot.chainContinuous()).isFalse();
        assertThat(snapshot.gaps()).isNotEmpty();
        assertThat(String.join(";", snapshot.gaps())).contains("bob", "carol");
    }
}
