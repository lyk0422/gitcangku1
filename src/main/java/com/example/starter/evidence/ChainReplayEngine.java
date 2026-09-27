package com.example.starter.evidence;

import com.example.starter.evidence.dto.FrozenChainEventView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 按截至 UTC 时刻重放单件证物的保管链、位置、封签、待核验与借还状态。
 * 历史记录时间基准不一致：交接/核验为 Asia/Shanghai 墙钟时间，借出/归还为 UTC，
 * 统一换算为 UTC 时刻后再与 cutoff 比较，保证与运行 JVM 时区无关。
 */
@Component
public class ChainReplayEngine {

    static final ZoneId WALL_ZONE = ZoneId.of("Asia/Shanghai");
    static final ZoneOffset UTC = ZoneOffset.UTC;

    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;

    public ChainReplayEngine(TransferRecordRepository transferRepository,
                             SealInspectionRepository inspectionRepository,
                             LoanRecordRepository loanRepository) {
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
    }

    /**
     * 重放单件证物在 cutoff（UTC，含该时刻）的历史状态。
     */
    public ChainSnapshot replay(Evidence evidence, LocalDateTime cutoff) {
        long cutoffMillis = toUtcMillis(cutoff);

        List<TransferRecord> allTransfers = transferRepository.findByEvidenceKey(evidence.evidenceKey());

        // 全链连续性校验与入库锚点：沿全部已接受交接，从证物当前保管人反向回推，
        // 每环的接收人必须等于下一环节的在任保管人；真实流程中接受交接与保管人更新同事务，必然咬合。
        List<String> gaps = new ArrayList<>();
        String initialCustodian = reverseResolveInitialCustodian(evidence, allTransfers, gaps);

        // 入库锚点：入库时间为 Asia/Shanghai 墙钟，换算 UTC。
        long intakeMillis = toWallMillis(evidence.createdAt());

        List<Event> events = new ArrayList<>();
        if (intakeMillis <= cutoffMillis) {
            events.add(new Event(Kind.INTAKE.rank, intakeMillis, evidence.id(), Kind.INTAKE,
                    null, null, null));
        }
        for (TransferRecord transfer : allTransfers) {
            long initiatedMillis = toWallMillis(transfer.createdAt());
            if (initiatedMillis <= cutoffMillis) {
                events.add(new Event(Kind.TRANSFER_INITIATE.rank, initiatedMillis, transfer.id(),
                        Kind.TRANSFER_INITIATE, null, transfer, null));
                if (transfer.decidedAt() != null
                        && toWallMillis(transfer.decidedAt()) <= cutoffMillis) {
                    events.add(new Event(Kind.TRANSFER_DECIDED.rank, toWallMillis(transfer.decidedAt()),
                            transfer.id(), Kind.TRANSFER_DECIDED, null, transfer, null));
                }
            }
        }
        for (SealInspection inspection : inspectionRepository.findByEvidenceKey(evidence.evidenceKey())) {
            long at = toWallMillis(inspection.createdAt());
            if (at <= cutoffMillis) {
                events.add(new Event(Kind.INSPECTION.rank, at, inspection.id(), Kind.INSPECTION,
                        inspection, null, null));
            }
        }
        for (LoanRecord loan : loanRepository.findByEvidenceKey(evidence.evidenceKey())) {
            long loanMillis = toUtcMillis(loan.loanAt());
            if (loanMillis <= cutoffMillis) {
                events.add(new Event(Kind.LOAN_BORROW.rank, loanMillis, loan.id(),
                        Kind.LOAN_BORROW, null, null, loan));
            }
            if (loan.returnedAt() != null && toUtcMillis(loan.returnedAt()) <= cutoffMillis) {
                events.add(new Event(Kind.LOAN_RETURN.rank, toUtcMillis(loan.returnedAt()), loan.id(),
                        Kind.LOAN_RETURN, null, null, loan));
            }
        }
        events.sort(Comparator.comparingLong((Event e) -> e.atMillis)
                .thenComparingInt(e -> e.rank)
                .thenComparingLong(e -> e.recordId));

        List<FrozenChainEventView> views = new ArrayList<>();

        String custodian = null;
        EvidenceStatus status = null;
        boolean sealOk = true;
        Boolean latestInspectionPassed = null;
        TransferRecord pendingTransfer = null;
        LoanRecord activeLoan = null;
        int seq = 0;

        if (events.isEmpty() || events.get(0).kind != Kind.INTAKE) {
            gaps.add("截至时刻证物尚未入库，缺少保管链起点: " + evidence.evidenceKey());
        }

        for (Event event : events) {
            seq++;
            switch (event.kind) {
                case INTAKE -> {
                    custodian = initialCustodian;
                    status = EvidenceStatus.SEALED;
                    views.add(new FrozenChainEventView(seq, "INTAKE", evidence.createdAt(),
                            initialCustodian, null, null, null, null, null, null, null, null, null));
                }
                case TRANSFER_INITIATE -> {
                    TransferRecord transfer = event.transfer;
                    // 链连续性：发起交接时记录的原保管人必须等于重放在任保管人。
                    if (custodian == null || !custodian.equals(transfer.fromCustodian())) {
                        gaps.add("保管链缺口: 交接发起保管人 " + transfer.fromCustodian()
                                + " 与截至时刻在任保管人 " + custodian + " 不衔接");
                    }
                    custodian = transfer.fromCustodian();
                    status = EvidenceStatus.TRANSFER_PENDING;
                    pendingTransfer = transfer;
                    views.add(new FrozenChainEventView(seq, "TRANSFER", transfer.createdAt(),
                            transfer.initiatedBy(), transfer.fromCustodian(), transfer.toCustodian(),
                            TransferStatus.PENDING.name(), null, null, null, null, null, null));
                }
                case TRANSFER_DECIDED -> {
                    TransferRecord transfer = event.transfer;
                    if (transfer.status() == TransferStatus.ACCEPTED) {
                        custodian = transfer.toCustodian();
                    }
                    status = sealOk ? EvidenceStatus.SEALED : EvidenceStatus.SEAL_BROKEN;
                    pendingTransfer = null;
                    views.add(new FrozenChainEventView(seq, "TRANSFER", transfer.decidedAt(),
                            transfer.status() == TransferStatus.ACCEPTED
                                    ? transfer.toCustodian() : transfer.initiatedBy(),
                            transfer.fromCustodian(), transfer.toCustodian(),
                            transfer.status().name(), transfer.decidedAt(), null, null, null, null,
                            null));
                }
                case INSPECTION -> {
                    SealInspection inspection = event.inspection;
                    latestInspectionPassed = inspection.passed();
                    if (!inspection.passed()) {
                        sealOk = false;
                        status = EvidenceStatus.SEAL_BROKEN;
                    }
                    views.add(new FrozenChainEventView(seq, "INSPECTION", inspection.createdAt(),
                            inspection.inspectorId(), null, null, null, null, null, null, null,
                            null, inspection.passed()));
                }
                case LOAN_BORROW -> {
                    LoanRecord loan = event.loan;
                    activeLoan = loan;
                    status = EvidenceStatus.BORROWED;
                    views.add(new FrozenChainEventView(seq, "LOAN_BORROW", loan.loanAt(),
                            loan.custodianId(), null, null, null, null, loan.borrowerId(),
                            LoanStatus.ACTIVE.name(), loan.dueAt(), null, null));
                }
                case LOAN_RETURN -> {
                    LoanRecord loan = event.loan;
                    boolean passed = Boolean.TRUE.equals(loan.sealPassed());
                    if (!passed) {
                        sealOk = false;
                    }
                    latestInspectionPassed = passed;
                    status = passed ? EvidenceStatus.SEALED : EvidenceStatus.SEAL_BROKEN;
                    activeLoan = null;
                    views.add(new FrozenChainEventView(seq, "LOAN_RETURN", loan.returnedAt(),
                            loan.custodianId(), null, null, null, null, loan.borrowerId(),
                            LoanStatus.RETURNED.name(), loan.dueAt(), loan.returnedAt(), passed));
                }
            }
        }

        int chainVersion = seq;
        String chainHash = sha256(canonicalEvents(views));
        String itemDigest = sha256(String.join("|",
                String.valueOf(chainVersion),
                nullToEmpty(custodian),
                status == null ? "" : status.name(),
                evidence.sealNo(),
                String.valueOf(sealOk),
                latestInspectionPassed == null ? "" : latestInspectionPassed.toString(),
                chainHash));

        return new ChainSnapshot(
                evidence.evidenceKey(),
                evidence.caseKey(),
                evidence.sealNo(),
                chainVersion,
                chainHash,
                itemDigest,
                custodian,
                status,
                sealOk,
                latestInspectionPassed,
                gaps.isEmpty(),
                List.copyOf(gaps),
                pendingTransfer != null,
                activeLoan != null,
                List.copyOf(views));
    }

    /**
     * 沿全部已接受交接从证物当前保管人反向回推入库保管人，并逐环校验链连续性：
     * 每环交接的接收人必须等于回推到该环时的在任保管人。
     * 真实业务中接受交接与保管人更新在同一事务提交，正常链零缺口；不一致的历史数据逐环记录。
     */
    private String reverseResolveInitialCustodian(Evidence evidence, List<TransferRecord> transfers,
                                                  List<String> gaps) {
        String custodian = evidence.custodianId();
        List<TransferRecord> accepted = transfers.stream()
                .filter(t -> t.status() == TransferStatus.ACCEPTED)
                .toList();
        for (int i = accepted.size() - 1; i >= 0; i--) {
            TransferRecord transfer = accepted.get(i);
            if (!transfer.toCustodian().equals(custodian)) {
                gaps.add("保管链缺口: 已接受交接接收人 " + transfer.toCustodian()
                        + " 与在任保管人 " + custodian + " 不衔接");
            }
            custodian = transfer.fromCustodian();
        }
        return custodian;
    }

    private String canonicalEvents(List<FrozenChainEventView> views) {
        StringBuilder sb = new StringBuilder();
        for (FrozenChainEventView e : views) {
            sb.append(e.seq()).append('|')
                    .append(e.kind()).append('|')
                    .append(e.occurredAt()).append('|')
                    .append(nullToEmpty(e.actorId())).append('|')
                    .append(nullToEmpty(e.fromCustodian())).append('|')
                    .append(nullToEmpty(e.toCustodian())).append('|')
                    .append(nullToEmpty(e.transferStatus())).append('|')
                    .append(e.decidedAt()).append('|')
                    .append(nullToEmpty(e.borrowerId())).append('|')
                    .append(nullToEmpty(e.loanStatus())).append('|')
                    .append(e.dueAt()).append('|')
                    .append(e.returnedAt()).append('|')
                    .append(e.passed()).append('\n');
        }
        return sb.toString();
    }

    static long toWallMillis(LocalDateTime wallTime) {
        return wallTime.atZone(WALL_ZONE).toInstant().toEpochMilli();
    }

    static long toUtcMillis(LocalDateTime utcTime) {
        return utcTime.toInstant(UTC).toEpochMilli();
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum Kind {
        INTAKE(0),
        TRANSFER_INITIATE(1),
        LOAN_BORROW(2),
        LOAN_RETURN(3),
        INSPECTION(4),
        TRANSFER_DECIDED(5);

        private final int rank;

        Kind(int rank) {
            this.rank = rank;
        }
    }

    /**
     * 排序用的临时链事件；同毫秒按 rank、记录 id 二级排序。
     */
    private record Event(int rank, long atMillis, long recordId, Kind kind,
                         SealInspection inspection, TransferRecord transfer, LoanRecord loan) {
    }
}
