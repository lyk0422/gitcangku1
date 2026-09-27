package com.example.starter.disclosure;

import com.example.starter.disclosure.dto.CutoffEvidenceSnapshot;
import com.example.starter.evidence.Evidence;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.LoanRecord;
import com.example.starter.evidence.LoanRecordRepository;
import com.example.starter.evidence.LoanStatus;
import com.example.starter.evidence.SealInspection;
import com.example.starter.evidence.SealInspectionRepository;
import com.example.starter.evidence.TransferRecord;
import com.example.starter.evidence.TransferRecordRepository;
import com.example.starter.evidence.TransferStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 截至时刻重放器纯单元测试：事件时刻等于 cutoff 的边界（含）、时区换算、
 * 链连续性推进、封签终态与各失败原因码。真实 SQL/回滚/并发结论由 H2 集成测试验证。
 */
class CutoffReplayerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 1, 1, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 1, 2, 0, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 9, 1, 3, 0, 0);

    private final EvidenceRepository evidenceRepository = mock(EvidenceRepository.class);
    private final TransferRecordRepository transferRepository = mock(TransferRecordRepository.class);
    private final LoanRecordRepository loanRepository = mock(LoanRecordRepository.class);
    private final SealInspectionRepository inspectionRepository = mock(SealInspectionRepository.class);

    private final CutoffReplayer replayer = new CutoffReplayer(
            evidenceRepository, transferRepository, loanRepository, inspectionRepository);

    private void givenEvidence(String key, String caseKey, String custodian,
                               LocalDateTime createdAtWall) {
        when(evidenceRepository.findByKey(key)).thenReturn(Optional.of(new Evidence(
                1L, key, caseKey, "DOCUMENT", "SEAL-1", custodian,
                EvidenceStatus.SEALED, createdAtWall, createdAtWall)));
    }

    private void noHistory(String key) {
        when(transferRepository.findByEvidenceKey(key)).thenReturn(List.of());
        when(loanRepository.findByEvidenceKey(key)).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey(key)).thenReturn(List.of());
    }

    private TransferRecord transfer(long id, String from, String to, TransferStatus status,
                                    LocalDateTime createdWall, LocalDateTime decidedWall) {
        return new TransferRecord(id, "EV", from, to, status, from, createdWall, decidedWall);
    }

    @Test
    void wallTimeIsConvertedToUtcForCutoffComparison() {
        // 2026-09-01 16:00 上海墙时间 == 08:00 UTC。
        LocalDateTime wall = LocalDateTime.of(2026, 9, 1, 16, 0, 0);
        assertThat(CutoffReplayer.wallToUtc(wall))
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 8, 0, 0));
    }

    @Test
    void missingEvidenceReturnsNotExist() {
        when(evidenceRepository.findByKey("EV")).thenReturn(Optional.empty());
        CutoffEvidenceSnapshot snapshot = replayer.replay("CASE", "EV", T3);
        assertThat(snapshot.ok()).isFalse();
        assertThat(snapshot.reasonCode()).isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.NOT_EXIST);
    }

    @Test
    void intakeAfterCutoffFailsAndForeignCaseFails() {
        givenEvidence("EV", "CASE", "alice",
                LocalDateTime.of(2026, 9, 1, 12, 0, 0)); // 04:00 UTC
        noHistory("EV");
        assertThat(replayer.replay("CASE", "EV", T3).reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.INTAKE_AFTER_CUTOFF);

        assertThat(replayer.replay("OTHER-CASE", "EV", T3).reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.INTAKE_AFTER_CUTOFF);

        // 入库早于 cutoff 但案件不符时才报 FOREIGN_CASE。
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8)); // 00:00 UTC < T3
        noHistory("EV");
        assertThat(replayer.replay("OTHER-CASE", "EV", T3).reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.FOREIGN_CASE);
    }

    @Test
    void eventExactlyAtCutoffIsInclusive() {
        // 入库墙时间 T0+8h == T0 UTC；cutoff == T0 时入库必须算发生（含边界）。
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        noHistory("EV");
        CutoffEvidenceSnapshot snapshot = replayer.replay("CASE", "EV", T0);
        assertThat(snapshot.ok()).isTrue();
        assertThat(snapshot.custodianAtCutoff()).isEqualTo("alice");
    }

    @Test
    void acceptedTransferExactlyDecidedAtCutoffIsCompleteAndPositionAdvanced() {
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1L, "alice", "bob", TransferStatus.ACCEPTED,
                        T1.plusHours(8), T2.plusHours(8)))); // decided == cutoff T2
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of());

        CutoffEvidenceSnapshot snapshot = replayer.replay("CASE", "EV", T2);
        assertThat(snapshot.ok()).isTrue();
        assertThat(snapshot.custodianAtCutoff()).isEqualTo("bob");
        assertThat(snapshot.transferCount()).isEqualTo(1);
    }

    @Test
    void pendingTransferExactlyInitiatedAtCutoffFailsAsPending() {
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1L, "alice", "bob", TransferStatus.PENDING, T2.plusHours(8), null)));
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of());

        assertThat(replayer.replay("CASE", "EV", T2).reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.PENDING_TRANSFER_AT_CUTOFF);
    }

    @Test
    void pendingAcceptedAfterCutoffStillPendingAtCutoffButPositionStaysContinuous() {
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1L, "alice", "bob", TransferStatus.ACCEPTED,
                        T1.plusHours(8), T3.plusHours(8))));
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of());

        // 截至 T2 尚未决定，位置保持 alice 且连续，不算缺口但属于待核验状态。
        CutoffEvidenceSnapshot snapshot = replayer.replay("CASE", "EV", T2);
        assertThat(snapshot.ok()).isFalse();
        assertThat(snapshot.reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.PENDING_TRANSFER_AT_CUTOFF);
    }

    @Test
    void activeLoanExactlyAtCutoffFailsButReturnedExactlyAtCutoffPasses() {
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of());

        LoanRecord active = new LoanRecord(1L, "L", "EV", "alice", "bob", "用途",
                T1, T2, LoanStatus.ACTIVE, null, null, null, T1);
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of(active));
        // cutoff == T2（dueAt，无意义）但未归还：ACTIVE。
        assertThat(replayer.replay("CASE", "EV", T2).reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.ACTIVE_LOAN_AT_CUTOFF);

        // 归还时刻恰等于 cutoff：视为已归还（含边界），同时归还核验未在该测试注入 → loan 计数 1。
        LoanRecord returned = new LoanRecord(1L, "L", "EV", "alice", "bob", "用途",
                T1, T2, LoanStatus.RETURNED, true, "ok", T2, T1);
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of(returned));
        CutoffEvidenceSnapshot snapshot = replayer.replay("CASE", "EV", T2);
        assertThat(snapshot.ok()).isTrue();
        assertThat(snapshot.loanCount()).isEqualTo(1);
    }

    @Test
    void failedInspectionIsTerminalEvenWhenLaterInspectionPasses() {
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                new SealInspection(1L, "EV", "alice", false, "破损", T1.plusHours(8)),
                new SealInspection(2L, "EV", "alice", true, "试图恢复", T2.plusHours(8))));

        CutoffEvidenceSnapshot snapshot = replayer.replay("CASE", "EV", T3);
        assertThat(snapshot.ok()).isFalse();
        assertThat(snapshot.reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.SEAL_BROKEN_AT_CUTOFF);
    }

    @Test
    void continuousChainWithPendingTailKeepsPositionAndFailsOnlyOnPending() {
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1L, "alice", "bob", TransferStatus.ACCEPTED,
                        T1.plusHours(8), T1.plusHours(8).plusMinutes(30)),
                transfer(2L, "bob", "carol", TransferStatus.PENDING, T2.plusHours(8), null)));
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of());

        // bob->carol 未决，但发起人 bob 与当前位置连续：不报链缺口，只报待核验。
        assertThat(replayer.replay("CASE", "EV", T3).reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.PENDING_TRANSFER_AT_CUTOFF);
    }

    @Test
    void discontinuousChainReturnsCustodyGap() {
        givenEvidence("EV", "CASE", "alice", T0.plusHours(8));
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1L, "alice", "bob", TransferStatus.ACCEPTED,
                        T1.plusHours(8), T1.plusHours(8).plusMinutes(30)),
                transfer(2L, "alice", "carol", TransferStatus.ACCEPTED,
                        T2.plusHours(8), T2.plusHours(8).plusMinutes(30))));
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of());

        assertThat(replayer.replay("CASE", "EV", T3).reasonCode())
                .isEqualTo(com.example.starter.disclosure.dto.CutoffReplayCode.CUSTODY_GAP_AT_CUTOFF);
    }
}
