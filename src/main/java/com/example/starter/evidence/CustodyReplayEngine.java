package com.example.starter.evidence;

import com.example.starter.evidence.dto.ItemSummaryView;
import com.example.starter.evidence.dto.SealSnapshotView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 保管链截至时刻重放引擎：按 UTC 截至时刻重放单件证物的位置（保管人）、封签、
 * 待核验（待接收交接）与借还状态，并校验保管链连续性。
 *
 * <p>时间口径：借出列（loan_at/due_at/returned_at）按 UTC 存储；证物入库、交接与核验列按
 * Asia/Shanghai 墙钟存储。比较前统一换算为同一时刻的 {@link Instant}，
 * 取“截至时刻之前（含等于）”的记录。只追加的历史表按 id（发生顺序）重放。</p>
 */
@Component
public class CustodyReplayEngine {

    /**
     * Asia/Shanghai 历史墙钟列的固定偏移（既有 schema 与 Jackson 均按 Asia/Shanghai 写入）。
     */
    static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);

    private final TransferRecordRepository transferRepository;
    private final LoanRecordRepository loanRepository;
    private final SealInspectionRepository inspectionRepository;

    public CustodyReplayEngine(TransferRecordRepository transferRepository,
                               LoanRecordRepository loanRepository,
                               SealInspectionRepository inspectionRepository) {
        this.transferRepository = transferRepository;
        this.loanRepository = loanRepository;
        this.inspectionRepository = inspectionRepository;
    }

    /**
     * 按截至 UTC 时刻重放单件证物。调用前应已确认证物存在且属于目标案件。
     *
     * @param evidence 已锁定的证物行
     * @param asOfUtc  截至 UTC 时刻（按 UTC 解释的无时区时刻）
     * @return 重放结果；code 非 OK 时 sealSnapshot/summary 为空，message 为可区分诊断
     */
    public ReplayResult replay(Evidence evidence, LocalDateTime asOfUtc) {
        Instant asOf = asOfUtc.toInstant(ZoneOffset.UTC);
        String evidenceKey = evidence.evidenceKey();

        // 截至时刻早于入库：证物尚不存在，保管链无从谈起。
        if (toInstantShanghai(evidence.createdAt()).isAfter(asOf)) {
            return fail(evidenceKey, evidence.caseKey(), ItemDiagnosisCode.CHAIN_GAP,
                    "截至时刻早于证物入库时间，截至时刻证物尚未入库: " + evidenceKey, null);
        }

        List<TransferRecord> transfers = transferRepository.findByEvidenceKey(evidenceKey);
        List<LoanRecord> loans = loanRepository.findByEvidenceKey(evidenceKey);
        List<SealInspection> inspections = inspectionRepository.findByEvidenceKey(evidenceKey);

        // 入库保管人：首条交接的发起人即入库时保管人；从未交接过则当前保管人就是入库保管人。
        String intakeCustodian = transfers.stream()
                .map(TransferRecord::fromCustodian)
                .findFirst()
                .orElse(evidence.custodianId());

        // 1. 重放交接：校验链连续性，得到截至时刻位置与待核验状态。
        String custodian = intakeCustodian;
        boolean pendingTransfer = false;
        int transferCount = 0;
        int acceptedCount = 0;
        int cancelledCount = 0;
        for (TransferRecord transfer : transfers) {
            Instant initiatedAt = toInstantShanghai(transfer.createdAt());
            if (initiatedAt.isAfter(asOf)) {
                // 记录按发生顺序追加，晚于截至时刻的交接及其后记录均不计入。
                break;
            }
            transferCount++;
            if (!transfer.fromCustodian().equals(custodian)) {
                return fail(evidenceKey, evidence.caseKey(), ItemDiagnosisCode.CHAIN_GAP,
                        "保管链缺口：第 " + transferCount + " 次交接发起人 " + transfer.fromCustodian()
                                + " 与重放保管人 " + custodian + " 不一致: " + evidenceKey, custodian);
            }
            Instant decidedAt = transfer.decidedAt() == null
                    ? null : toInstantShanghai(transfer.decidedAt());
            if (decidedAt == null || decidedAt.isAfter(asOf)) {
                // 截至时刻已发起但未决定：待核验状态，位置停留在原保管人。
                pendingTransfer = true;
                break;
            }
            if (transfer.status() == TransferStatus.ACCEPTED) {
                custodian = transfer.toCustodian();
                acceptedCount++;
            } else if (transfer.status() == TransferStatus.CANCELLED) {
                cancelledCount++;
            }
        }

        // 2. 重放借出：按历史借还时刻而非记录当前状态判定截至时刻是否借出未归还。
        boolean activeLoan = false;
        String activeBorrower = null;
        int loanCount = 0;
        int returnedLoanCount = 0;
        for (LoanRecord loan : loans) {
            Instant loanAt = loan.loanAt().toInstant(ZoneOffset.UTC);
            if (loanAt.isAfter(asOf)) {
                break;
            }
            loanCount++;
            Instant returnedAt = loan.returnedAt() == null
                    ? null : loan.returnedAt().toInstant(ZoneOffset.UTC);
            if (returnedAt != null && !returnedAt.isAfter(asOf)) {
                returnedLoanCount++;
            } else {
                // 已借出且归还为空、或归还时刻晚于截至时刻：截至时刻借出未归还。
                activeLoan = true;
                activeBorrower = loan.borrowerId();
            }
        }

        // 3. 重放封签：截至时刻前任一失败核验即封签异常；最近核验进入快照。
        int inspectionCount = 0;
        int passedCount = 0;
        boolean sealBroken = false;
        SealInspection latestInspection = null;
        for (SealInspection inspection : inspections) {
            Instant inspectedAt = toInstantShanghai(inspection.createdAt());
            if (inspectedAt.isAfter(asOf)) {
                break;
            }
            inspectionCount++;
            latestInspection = inspection;
            if (inspection.passed()) {
                passedCount++;
            } else {
                sealBroken = true;
            }
        }

        // 4. 失败码优先级：链缺口（位置不可信）> 待核验 > 借出未归还 > 封签异常。
        if (pendingTransfer) {
            return fail(evidenceKey, evidence.caseKey(),
                    ItemDiagnosisCode.PENDING_TRANSFER_AT_AS_OF,
                    "截至时刻证物处于待核验状态（交接已发起未接受或取消）: " + evidenceKey, custodian);
        }
        if (activeLoan) {
            return fail(evidenceKey, evidence.caseKey(),
                    ItemDiagnosisCode.ACTIVE_LOAN_AT_AS_OF,
                    "截至时刻证物借出未归还，借用人: " + activeBorrower + ", 证物: " + evidenceKey,
                    custodian);
        }
        if (sealBroken) {
            return fail(evidenceKey, evidence.caseKey(),
                    ItemDiagnosisCode.SEAL_BROKEN_AT_AS_OF,
                    "截至时刻封签异常（截至时刻前存在失败封条核验）: " + evidenceKey, custodian);
        }

        int chainVersion = transferCount + loanCount + inspectionCount;
        SealSnapshotView sealSnapshot = new SealSnapshotView(
                evidence.sealNo(),
                true,
                latestInspection == null ? null : latestInspection.passed(),
                latestInspection == null ? null : latestInspection.createdAt(),
                inspectionCount);
        ItemSummaryView summary = new ItemSummaryView(
                chainVersion,
                transferCount,
                acceptedCount,
                cancelledCount,
                loanCount,
                returnedLoanCount,
                inspectionCount,
                passedCount,
                custodian,
                true,
                false,
                false,
                null);
        return new ReplayResult(evidenceKey, evidence.caseKey(), ItemDiagnosisCode.OK,
                "截至时刻重放通过", custodian, sealSnapshot, summary);
    }

    private ReplayResult fail(String evidenceKey, String caseKey, ItemDiagnosisCode code,
                              String message, String custodianAtAsOf) {
        return new ReplayResult(evidenceKey, caseKey, code, message, custodianAtAsOf, null, null);
    }

    private Instant toInstantShanghai(LocalDateTime shanghaiWallTime) {
        return shanghaiWallTime.toInstant(SHANGHAI_OFFSET);
    }

    /**
     * 单件重放结果。code 非 OK 时 sealSnapshot/summary 为空。
     *
     * @param evidenceKey     证物业务键
     * @param caseKey         所属案件键
     * @param code            诊断码
     * @param message         可区分诊断说明
     * @param custodianAtAsOf 截至时刻保管人（位置）
     * @param sealSnapshot    截至时刻封签快照（失败时为 null）
     * @param summary         逐件生成摘要（失败时为 null）
     */
    public record ReplayResult(
            String evidenceKey,
            String caseKey,
            ItemDiagnosisCode code,
            String message,
            String custodianAtAsOf,
            SealSnapshotView sealSnapshot,
            ItemSummaryView summary) {
    }
}
