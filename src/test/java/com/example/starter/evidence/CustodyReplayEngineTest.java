package com.example.starter.evidence;

import com.example.starter.evidence.dto.ItemSummaryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 保管链截至时刻重放引擎单元测试：不启动 Spring、不连数据库，
 * 用 Mockito 仓储返回合成历史记录，验证位置重放、链缺口、待核验、借还边界、
 * 封签历史与 Asia/Shanghai 墙钟到 UTC 的换算。
 */
class CustodyReplayEngineTest {

    // 证物入库：2026-09-22 16:00 Shanghai == 08:00 UTC。
    private static final LocalDateTime INTAKE_SH = LocalDateTime.parse("2026-09-22T16:00:00");
    private static final LocalDateTime AS_OF = LocalDateTime.parse("2026-09-22T12:00:00");

    private TransferRecordRepository transferRepository;
    private LoanRecordRepository loanRepository;
    private SealInspectionRepository inspectionRepository;
    private CustodyReplayEngine engine;

    @BeforeEach
    void setUp() {
        transferRepository = mock(TransferRecordRepository.class);
        loanRepository = mock(LoanRecordRepository.class);
        inspectionRepository = mock(SealInspectionRepository.class);
        engine = new CustodyReplayEngine(transferRepository, loanRepository, inspectionRepository);
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of());
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of());
    }

    private Evidence evidence(String custodian) {
        return new Evidence(1L, "EV", "CASE-1", "DOCUMENT", "SEAL-1", custodian,
                EvidenceStatus.SEALED, INTAKE_SH, INTAKE_SH);
    }

    private TransferRecord transfer(long id, String from, String to, TransferStatus status,
                                    String createdAtSh, String decidedAtSh) {
        return new TransferRecord(id, "EV", from, to, status, from,
                LocalDateTime.parse(createdAtSh),
                decidedAtSh == null ? null : LocalDateTime.parse(decidedAtSh));
    }

    private LoanRecord loan(long id, String custodian, String borrower, String loanAtUtc,
                            String returnedAtUtc) {
        LocalDateTime loanAt = LocalDateTime.parse(loanAtUtc);
        return new LoanRecord(id, "LOAN-" + id, "EV", custodian, borrower, "鉴定",
                loanAt, loanAt.plusHours(2),
                returnedAtUtc == null ? LoanStatus.ACTIVE : LoanStatus.RETURNED,
                returnedAtUtc == null ? null : Boolean.TRUE,
                returnedAtUtc == null ? null : "归还",
                returnedAtUtc == null ? null : LocalDateTime.parse(returnedAtUtc),
                INTAKE_SH);
    }

    private SealInspection inspection(long id, boolean passed, String createdAtSh) {
        return new SealInspection(id, "EV", "alice", passed, "备注", LocalDateTime.parse(createdAtSh));
    }

    @Test
    void sealedEvidenceWithoutHistoryReplaysAtIntakeCustodian() {
        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        assertThat(result.custodianAtAsOf()).isEqualTo("alice");
        assertThat(result.summary().chainVersion()).isZero();
        assertThat(result.sealSnapshot().intactAtAsOf()).isTrue();
        assertThat(result.sealSnapshot().latestPassed()).isNull();
    }

    @Test
    void asOfBeforeIntakeIsChainGap() {
        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"),
                LocalDateTime.parse("2026-09-22T07:59:59"));

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.CHAIN_GAP);
    }

    @Test
    void acceptedTransfersReplayCustodianAcrossTimeZones() {
        // 16:30/17:30 Shanghai == 08:30/09:30 UTC，均早于 asOf 12:00 UTC。
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1, "alice", "bob", TransferStatus.ACCEPTED,
                        "2026-09-22T16:30:00", "2026-09-22T16:35:00"),
                transfer(2, "bob", "carol", TransferStatus.ACCEPTED,
                        "2026-09-22T17:30:00", "2026-09-22T17:40:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("carol"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        assertThat(result.custodianAtAsOf()).isEqualTo("carol");
        ItemSummaryView summary = result.summary();
        assertThat(summary.transferCountUpToAsOf()).isEqualTo(2);
        assertThat(summary.acceptedTransferCount()).isEqualTo(2);
        assertThat(summary.chainVersion()).isEqualTo(2);
    }

    @Test
    void transferInitiatedAfterAsOfIsNotReplayed() {
        // 21:00 Shanghai == 13:00 UTC，晚于 asOf 12:00 UTC：截至时刻证物仍在 alice 手中且已封存。
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1, "alice", "bob", TransferStatus.PENDING,
                        "2026-09-22T21:00:00", null)));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        assertThat(result.custodianAtAsOf()).isEqualTo("alice");
        assertThat(result.summary().transferCountUpToAsOf()).isZero();
    }

    @Test
    void pendingTransferAtAsOfIsDistinctFailure() {
        // 16:30 Shanghai == 08:30 UTC 发起；当前仍 PENDING。
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1, "alice", "bob", TransferStatus.PENDING,
                        "2026-09-22T16:30:00", null)));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.PENDING_TRANSFER_AT_AS_OF);
        assertThat(result.custodianAtAsOf()).isEqualTo("alice");
    }

    @Test
    void transferDecidedAfterAsOfStillPendingAtAsOf() {
        // 08:30 UTC 发起，13:00 UTC（21:00 Shanghai）才接受：asOf 12:00 UTC 时仍待核验，
        // 即使记录当前状态已是 ACCEPTED（按历史而非生成时状态判定）。
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1, "alice", "bob", TransferStatus.ACCEPTED,
                        "2026-09-22T16:30:00", "2026-09-22T21:00:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("bob"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.PENDING_TRANSFER_AT_AS_OF);
        assertThat(result.custodianAtAsOf()).isEqualTo("alice");
    }

    @Test
    void custodianMismatchInClosedTransferIsChainGap() {
        // alice->bob 已接受后，第二条闭环交接应由 bob 发起，实际由 alice 发起 -> 链缺口。
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1, "alice", "bob", TransferStatus.ACCEPTED,
                        "2026-09-22T16:30:00", "2026-09-22T16:35:00"),
                transfer(2, "alice", "carol", TransferStatus.ACCEPTED,
                        "2026-09-22T17:30:00", "2026-09-22T17:35:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("carol"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.CHAIN_GAP);
    }

    @Test
    void activeLoanAtAsOfIsDistinctFailure() {
        // 10:00 UTC 借出未归还，asOf 12:00 UTC。
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                loan(1, "alice", "bob", "2026-09-22T10:00:00", null)));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.ACTIVE_LOAN_AT_AS_OF);
        assertThat(result.message()).contains("bob");
    }

    @Test
    void loanReturnedAfterAsOfStillBorrowedAtAsOf() {
        // 记录当前已 RETURNED（13:00 UTC 归还），但 asOf 12:00 UTC 时仍借出未归还。
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                loan(1, "alice", "bob", "2026-09-22T10:00:00", "2026-09-22T13:00:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.ACTIVE_LOAN_AT_AS_OF);
    }

    @Test
    void loanReturnedBeforeAsOfReplaysOkAndCountsHistory() {
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                loan(1, "alice", "bob", "2026-09-22T09:00:00", "2026-09-22T11:00:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        ItemSummaryView summary = result.summary();
        assertThat(summary.loanCountUpToAsOf()).isEqualTo(1);
        assertThat(summary.returnedLoanCountUpToAsOf()).isEqualTo(1);
        assertThat(summary.activeLoanAtAsOf()).isFalse();
        assertThat(summary.chainVersion()).isEqualTo(1);
    }

    @Test
    void loanStartingAfterAsOfIsIgnored() {
        when(loanRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                loan(1, "alice", "bob", "2026-09-22T13:00:00", null)));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        assertThat(result.summary().loanCountUpToAsOf()).isZero();
    }

    @Test
    void failedInspectionAfterAsOfDoesNotBreakSeal() {
        // 失败核验发生在 21:00 Shanghai == 13:00 UTC，晚于 asOf 12:00 UTC：封签快照仍正常，
        // 即使证物当前已是 SEAL_BROKEN（按历史而非生成时状态判定）。
        Evidence broken = new Evidence(1L, "EV", "CASE-1", "DOCUMENT", "SEAL-1", "alice",
                EvidenceStatus.SEAL_BROKEN, INTAKE_SH, LocalDateTime.parse("2026-09-22T21:00:00"));
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                inspection(1, false, "2026-09-22T21:00:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(broken, AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        assertThat(result.sealSnapshot().intactAtAsOf()).isTrue();
        assertThat(result.sealSnapshot().inspectionsUpToAsOf()).isZero();
    }

    @Test
    void failedInspectionBeforeAsOfIsDistinctFailureWithSnapshotCounts() {
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                inspection(1, true, "2026-09-22T16:30:00"),
                inspection(2, false, "2026-09-22T17:30:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.SEAL_BROKEN_AT_AS_OF);
    }

    @Test
    void eventExactlyAtAsOfIsInclusive() {
        // 核验时刻 20:00 Shanghai == 12:00 UTC == asOf：边界含等于，计入历史且通过。
        when(inspectionRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                inspection(1, true, "2026-09-22T20:00:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("alice"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        assertThat(result.sealSnapshot().inspectionsUpToAsOf()).isEqualTo(1);
        assertThat(result.sealSnapshot().latestPassed()).isTrue();
    }

    @Test
    void cancelledTransferKeepsCustodianAndCountsSeparately() {
        when(transferRepository.findByEvidenceKey("EV")).thenReturn(List.of(
                transfer(1, "alice", "bob", TransferStatus.CANCELLED,
                        "2026-09-22T16:30:00", "2026-09-22T16:40:00"),
                transfer(2, "alice", "carol", TransferStatus.ACCEPTED,
                        "2026-09-22T17:30:00", "2026-09-22T17:45:00")));

        CustodyReplayEngine.ReplayResult result = engine.replay(evidence("carol"), AS_OF);

        assertThat(result.code()).isEqualTo(ItemDiagnosisCode.OK);
        assertThat(result.custodianAtAsOf()).isEqualTo("carol");
        ItemSummaryView summary = result.summary();
        assertThat(summary.cancelledTransferCount()).isEqualTo(1);
        assertThat(summary.acceptedTransferCount()).isEqualTo(1);
        assertThat(summary.transferCountUpToAsOf()).isEqualTo(2);
    }
}
