package com.example.starter.disclosure;

import com.example.starter.disclosure.dto.CutoffEvidenceSnapshot;
import com.example.starter.disclosure.dto.CutoffReplayCode;
import com.example.starter.evidence.Evidence;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.LoanRecord;
import com.example.starter.evidence.LoanRecordRepository;
import com.example.starter.evidence.SealInspection;
import com.example.starter.evidence.SealInspectionRepository;
import com.example.starter.evidence.TransferRecord;
import com.example.starter.evidence.TransferRecordRepository;
import com.example.starter.evidence.TransferStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 证物历史状态按截至时刻重放器。
 *
 * <p>披露包不采信证物当前状态，而是按 cutoff（UTC）重放历史：
 * 入库点、交接链连续性与待核验、借出未归还、封签状态全部取截至时刻之前（含）的记录判定。
 *
 * <p>时间语义沿用 schema 注释：交接/核验/入库列为 Asia/Shanghai 墙上时间，借出的 loan_at/
 * returned_at 为 UTC 时间；比较前统一换算到 UTC。
 */
@Service
public class CutoffReplayer {

    /**
     * 交接/核验/入库列的时区（Asia/Shanghai 墙上时间）。
     */
    static final ZoneId WALL_ZONE = ZoneId.of("Asia/Shanghai");

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final LoanRecordRepository loanRepository;
    private final SealInspectionRepository inspectionRepository;

    public CutoffReplayer(EvidenceRepository evidenceRepository,
                          TransferRecordRepository transferRepository,
                          LoanRecordRepository loanRepository,
                          SealInspectionRepository inspectionRepository) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.loanRepository = loanRepository;
        this.inspectionRepository = inspectionRepository;
    }

    /**
     * 重放单件证物在截至时刻的状态。
     *
     * @param expectedCaseKey 期望所属案件键；非空时校验证物必须属于该案件
     * @param evidenceKey     证物业务键
     * @param cutoffUtc       截至时刻（UTC）
     * @return 重放快照；失败时 ok=false 且携带可区分原因码
     */
    public CutoffEvidenceSnapshot replay(String expectedCaseKey, String evidenceKey,
                                         LocalDateTime cutoffUtc) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey).orElse(null);
        if (evidence == null) {
            return fail(evidenceKey, CutoffReplayCode.NOT_EXIST, cutoffUtc);
        }
        if (wallToUtc(evidence.createdAt()).isAfter(cutoffUtc)) {
            return fail(evidenceKey, CutoffReplayCode.INTAKE_AFTER_CUTOFF, cutoffUtc);
        }
        if (expectedCaseKey != null && !expectedCaseKey.equals(evidence.caseKey())) {
            return fail(evidenceKey, CutoffReplayCode.FOREIGN_CASE, cutoffUtc);
        }

        List<TransferRecord> transfers = transferRepository.findByEvidenceKey(evidenceKey).stream()
                .filter(t -> !wallToUtc(t.createdAt()).isAfter(cutoffUtc))
                .toList();
        List<LoanRecord> loans = loanRepository.findByEvidenceKey(evidenceKey).stream()
                .filter(l -> !l.loanAt().isAfter(cutoffUtc))
                .toList();
        List<SealInspection> inspections = inspectionRepository.findByEvidenceKey(evidenceKey).stream()
                .filter(i -> !wallToUtc(i.createdAt()).isAfter(cutoffUtc))
                .toList();

        String custodianAtCutoff = replayCustodian(evidence, transfers, cutoffUtc);
        if (custodianAtCutoff == null) {
            return fail(evidenceKey, CutoffReplayCode.CUSTODY_GAP_AT_CUTOFF, cutoffUtc);
        }

        boolean pendingTransfer = transfers.stream().anyMatch(t -> isUndecidedAtCutoff(t, cutoffUtc));
        boolean activeLoan = loans.stream().anyMatch(l -> isUnreturnedAtCutoff(l, cutoffUtc));
        Boolean lastInspectionPassed = inspections.isEmpty()
                ? null : inspections.get(inspections.size() - 1).passed();
        // 封签异常为终态：截至时刻之前（含）只要出现过一次失败核验即异常，后续复核不能恢复。
        boolean sealBroken = inspections.stream().anyMatch(i -> !i.passed());

        CutoffReplayCode code = null;
        if (pendingTransfer) {
            code = CutoffReplayCode.PENDING_TRANSFER_AT_CUTOFF;
        } else if (activeLoan) {
            code = CutoffReplayCode.ACTIVE_LOAN_AT_CUTOFF;
        } else if (sealBroken) {
            code = CutoffReplayCode.SEAL_BROKEN_AT_CUTOFF;
        }
        if (code != null) {
            return fail(evidenceKey, code, cutoffUtc);
        }

        String chainVersion = "T" + transfers.size() + "-L" + loans.size() + "-I" + inspections.size();
        return new CutoffEvidenceSnapshot(
                evidenceKey,
                true,
                null,
                chainVersion,
                custodianAtCutoff,
                CutoffEvidenceSnapshot.SealSnapshotView.INTACT,
                lastInspectionPassed,
                transfers.size(),
                loans.size(),
                inspections.size(),
                cutoffUtc);
    }

    /**
     * 按交接链重放截至时刻保管人位置；链上发起保管人与当前位置不连续时返回 null 表示缺口。
     */
    private String replayCustodian(Evidence evidence, List<TransferRecord> transfersBeforeCutoff,
                                   LocalDateTime cutoffUtc) {
        String position = initialCustodian(evidence);
        for (TransferRecord transfer : transfersBeforeCutoff) {
            if (!transfer.fromCustodian().equals(position)) {
                return null;
            }
            if (!isUndecidedAtCutoff(transfer, cutoffUtc)
                    && transfer.status() == TransferStatus.ACCEPTED) {
                // 截至时刻前已接受：保管人位置连续推进到接收人。
                position = transfer.toCustodian();
            }
            // 截至时刻未决（PENDING）或已取消：保管人位置保持为发起人。
        }
        return position;
    }

    /**
     * 入库保管人：无任何交接时取证物当前保管人；有交接时取最早一条交接的发起人。
     */
    private String initialCustodian(Evidence evidence) {
        List<TransferRecord> all = transferRepository.findByEvidenceKey(evidence.evidenceKey());
        return all.isEmpty() ? evidence.custodianId() : all.get(0).fromCustodian();
    }

    private boolean isUndecidedAtCutoff(TransferRecord transfer, LocalDateTime cutoffUtc) {
        return transfer.decidedAt() == null || wallToUtc(transfer.decidedAt()).isAfter(cutoffUtc);
    }

    private boolean isUnreturnedAtCutoff(LoanRecord loan, LocalDateTime cutoffUtc) {
        return loan.returnedAt() == null || loan.returnedAt().isAfter(cutoffUtc);
    }

    private CutoffEvidenceSnapshot fail(String evidenceKey, CutoffReplayCode code,
                                        LocalDateTime cutoffUtc) {
        return new CutoffEvidenceSnapshot(evidenceKey, false, code, null, null, null,
                null, 0, 0, 0, cutoffUtc);
    }

    /**
     * Asia/Shanghai 墙上时间换算为 UTC 本地时间（按 UTC 解释的 LocalDateTime）。
     */
    static LocalDateTime wallToUtc(LocalDateTime wallTime) {
        return LocalDateTime.ofInstant(wallTime.atZone(WALL_ZONE).toInstant(), ZoneOffset.UTC);
    }
}
