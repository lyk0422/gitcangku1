package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferInitiateRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.ChainEventView;
import com.example.starter.evidence.dto.SnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 证物跨案移交核心服务。
 * 一次移交在同一事务内完成全部前置校验、来源案移出链、目标案移入链与同一 transferId 的双案封存快照，
 * 任一证物不满足前置条件则整批回滚，不留任何链记录。并发移交按证物行锁与提交顺序裁决，同一证物最多一笔成功。
 * 时间统一使用 {@link EvidenceClock} 的 UTC 时刻（微秒精度），令版本区间左闭右开。
 */
@Service
public class CaseTransferService {

    private final EvidenceRepository evidenceRepository;
    private final CaseTransferRepository caseTransferRepository;
    private final CaseCustodianRepository caseCustodianRepository;
    private final TransferOrderRepository transferOrderRepository;
    private final TransferRecordRepository transferRepository;
    private final LoanRecordRepository loanRepository;
    private final SealInspectionRepository inspectionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public CaseTransferService(EvidenceRepository evidenceRepository,
                               CaseTransferRepository caseTransferRepository,
                               CaseCustodianRepository caseCustodianRepository,
                               TransferOrderRepository transferOrderRepository,
                               TransferRecordRepository transferRepository,
                               LoanRecordRepository loanRepository,
                               SealInspectionRepository inspectionRepository,
                               CommandLogRepository commandLogRepository,
                               ObjectMapper objectMapper,
                               EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.caseTransferRepository = caseTransferRepository;
        this.caseCustodianRepository = caseCustodianRepository;
        this.transferOrderRepository = transferOrderRepository;
        this.transferRepository = transferRepository;
        this.loanRepository = loanRepository;
        this.inspectionRepository = inspectionRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 发起跨案移交：请求证物先去重并按证物键规范化排序；
     * 先在行锁上校验整批证物的完整最终状态与授权/令版本，全部通过后再写入，任一失败整批回滚。
     */
    @Transactional
    public StoredResponse initiate(String actorId, CaseTransferInitiateRequest request,
                                   String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> requested = request.evidenceKeys();
        List<String> normalized = requested.stream().distinct().sorted().toList();
        if (normalized.size() != requested.size()) {
            throw ApiException.badRequest("DUPLICATE_EVIDENCE", "移交证物列表存在重复键，要求数量 "
                    + requested.size() + "，去重后数量 " + normalized.size());
        }
        if (request.fromCaseKey().equals(request.toCaseKey())) {
            throw ApiException.badRequest("SAME_CASE", "来源案件与目标案件不得相同: "
                    + request.fromCaseKey());
        }
        if (!request.fromCustodian().equals(actorId)) {
            throw ApiException.conflict("NOT_FROM_CUSTODIAN",
                    "操作人不是提交的来源案件保管人: " + actorId);
        }
        if (request.fromCustodian().equals(request.toCustodian())) {
            throw ApiException.conflict("CUSTODIANS_SAME",
                    "来源与目标案件保管人不得相同: " + request.fromCustodian());
        }

        // 统一按证物键排序加锁，保证并发移交的锁序一致、按提交顺序裁决且不死锁。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdateOrdered(normalized);
        // 并发下可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<String, Evidence> evidenceByKey = locked.stream()
                .collect(Collectors.toMap(Evidence::evidenceKey, Function.identity()));

        // 先校验整批的完整最终状态：收集全部违例后一次性拒绝，失败原因可区分，整批不写入。
        List<String> violations = new ArrayList<>();
        for (String key : normalized) {
            Evidence evidence = evidenceByKey.get(key);
            if (evidence == null) {
                violations.add(key + ": 证物不存在或不属于来源案件 " + request.fromCaseKey());
                continue;
            }
            if (!evidence.caseKey().equals(request.fromCaseKey())) {
                violations.add(key + ": 不属于来源案件 " + request.fromCaseKey()
                        + "（当前案件 " + evidence.caseKey() + "）");
            }
            if (evidence.status() == EvidenceStatus.BORROWED
                    || loanRepository.findActiveByEvidenceKey(key).isPresent()) {
                violations.add(key + ": 借出未归还");
            }
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                violations.add(key + ": 封签异常");
            }
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                violations.add(key + ": 存在进行中的保管链交接");
            }
        }
        // 并发移交由证物行锁（统一排序加锁）按提交顺序裁决：后提交事务持锁后会看到
        // 已被先提交事务改派的案件归属，在上方归属校验中得到 422，同一证物最多一笔成功。
        if (!violations.isEmpty()) {
            throw ApiException.unprocessable("EVIDENCE_NOT_TRANSFERABLE",
                    "整批移交被拒绝，实际可移交数量 " + (normalized.size() - violations.size())
                            + "，要求数量 " + normalized.size() + "；不符项: "
                            + String.join("; ", violations));
        }

        // 校验与后续全部写入共用同一个 UTC 时刻，保证令版本左闭右开边界与落库时间戳严格一致。
        LocalDateTime now = clock.nowUtc();
        validateOrderAndPermissions(request, now);

        long transferId = caseTransferRepository.insert(request.fromCaseKey(), request.toCaseKey(),
                request.orderKey(), request.orderVersion(), request.fromCustodian(),
                request.toCustodian(), actorId, normalized.size(), now);
        int seqNo = 0;
        for (String key : normalized) {
            Evidence evidence = evidenceByKey.get(key);
            Long lastLoanId = loanRepository.findMaxIdByEvidenceKey(key);
            Long lastInspectionId = inspectionRepository.findMaxIdByEvidenceKey(key);
            Long lastHandoffId = transferRepository.findMaxIdByEvidenceKey(key);
            caseTransferRepository.insertItem(transferId, key, seqNo++);
            // 双案快照固化双方案件、原位置（含 null 语义）、封签版本与令版本。
            caseTransferRepository.insertSnapshot(transferId, key, SnapshotSide.SOURCE,
                    request.fromCaseKey(), evidence.location(), evidence.sealNo(),
                    request.orderVersion(), lastLoanId, lastInspectionId, lastHandoffId, now);
            caseTransferRepository.insertSnapshot(transferId, key, SnapshotSide.TARGET,
                    request.toCaseKey(), evidence.location(), evidence.sealNo(),
                    request.orderVersion(), lastLoanId, lastInspectionId, lastHandoffId, now);
            // 同一事务内先写来源案移出链，再写目标案移入链。
            caseTransferRepository.insertEvent(transferId, key, request.fromCaseKey(),
                    ChainEventType.CASE_TRANSFER_OUT, request.fromCustodian(), null, now);
            caseTransferRepository.insertEvent(transferId, key, request.toCaseKey(),
                    ChainEventType.CASE_TRANSFER_IN, request.toCustodian(), null, now);
            // 仅在链与快照固化后切换当前归属；历史链保留旧案件归属，不得借改归属改写历史。
            evidenceRepository.reassignCase(key, request.toCaseKey(), request.toCustodian(),
                    evidence.location(), now);
        }
        CaseTransferView view = toView(transferId);
        return record(request.commandKey(), EvidenceService.OP_CASE_TRANSFER_INITIATE, actorId,
                requestHash, 200, view);
    }

    /**
     * 撤销跨案移交：仅 COMPLETED 且每件证物在目标案件均未发生后续借出、封签复核或保管链交接；
     * 由双方不同保管人确认（操作人为其中一方），追加 REVOKE_OUT/REVOKE_IN 反向链并移回来源案，
     * 原移交与快照不删除。
     */
    @Transactional
    public StoredResponse revoke(String actorId, long transferId,
                                 CaseTransferRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        CaseTransfer transfer = caseTransferRepository.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "跨案移交不存在: " + transferId));
        if (transfer.status() != CaseTransferStatus.COMPLETED) {
            throw ApiException.conflict("TRANSFER_ALREADY_REVOKED",
                    "跨案移交已撤销，撤销不可重复: " + transferId);
        }
        if (!request.fromCustodian().equals(transfer.fromCustodian())
                || !request.toCustodian().equals(transfer.toCustodian())) {
            throw ApiException.conflict("CUSTODIAN_MISMATCH",
                    "撤销确认人与移交单双方保管人不一致");
        }
        if (request.fromCustodian().equals(request.toCustodian())) {
            throw ApiException.conflict("CUSTODIANS_SAME", "撤销须由双方不同保管人确认");
        }
        if (!actorId.equals(request.fromCustodian()) && !actorId.equals(request.toCustodian())) {
            throw ApiException.conflict("NOT_CONFIRMING_CUSTODIAN",
                    "操作人不是移交单任一方保管人: " + actorId);
        }

        List<CaseTransferItem> items = caseTransferRepository.findItems(transferId);
        List<String> keys = items.stream().map(CaseTransferItem::evidenceKey).sorted().toList();
        Map<String, Evidence> evidenceByKey = evidenceRepository.findByKeysForUpdateOrdered(keys)
                .stream().collect(Collectors.toMap(Evidence::evidenceKey, Function.identity()));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        List<String> blockers = new ArrayList<>();
        for (CaseTransferItem item : items) {
            String key = item.evidenceKey();
            Evidence evidence = evidenceByKey.get(key);
            CaseTransferSnapshot target = requireTargetSnapshot(transferId, key);
            if (evidence == null) {
                blockers.add(key + ": 证物数据缺失");
                continue;
            }
            if (!evidence.caseKey().equals(transfer.toCaseKey())) {
                blockers.add(key + ": 已被后续跨案移交移出目标案件（当前案件 "
                        + evidence.caseKey() + "）");
            }
            if (caseTransferRepository.existsLaterTransfer(key, transferId)) {
                blockers.add(key + ": 移交后又发生过其他跨案移交");
            }
            if (evidence.status() == EvidenceStatus.BORROWED
                    || loanRepository.findActiveByEvidenceKey(key).isPresent()) {
                blockers.add(key + ": 目标案件已发生借出且未归还");
            }
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                blockers.add(key + ": 目标案件存在进行中的保管链交接");
            }
            if (loanRepository.existsAfterId(key, target.lastLoanId())) {
                blockers.add(key + ": 目标案件已发生后续借出");
            }
            if (inspectionRepository.existsAfterId(key, target.lastInspectionId())) {
                blockers.add(key + ": 目标案件已发生后续封签复核");
            }
            if (transferRepository.existsAfterId(key, target.lastHandoffId())) {
                blockers.add(key + ": 目标案件已发生后续保管链交接");
            }
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                blockers.add(key + ": 封签已异常，不可撤销移回");
            }
        }
        if (!blockers.isEmpty()) {
            throw ApiException.conflict("REVOKE_BLOCKED_BY_SUBSEQUENT_HANDLING",
                    "目标案件已发生后续交接，移交不可撤销；阻塞项: " + String.join("; ", blockers));
        }

        LocalDateTime now = clock.nowUtc();
        if (!caseTransferRepository.markRevoked(transferId, request.fromCustodian(),
                request.toCustodian(), now)) {
            throw ApiException.conflict("TRANSFER_ALREADY_REVOKED",
                    "跨案移交已被并发撤销: " + transferId);
        }
        for (CaseTransferItem item : items) {
            String key = item.evidenceKey();
            Evidence evidence = evidenceByKey.get(key);
            // 追加反向链：先目标案撤销移出，再来源案撤销移回；不删除原移出/移入链。
            caseTransferRepository.insertEvent(transferId, key, transfer.toCaseKey(),
                    ChainEventType.CASE_TRANSFER_REVOKE_OUT, request.toCustodian(), null, now);
            caseTransferRepository.insertEvent(transferId, key, transfer.fromCaseKey(),
                    ChainEventType.CASE_TRANSFER_REVOKE_IN, request.fromCustodian(), null, now);
            evidenceRepository.reassignCase(key, transfer.fromCaseKey(),
                    transfer.fromCustodian(), evidence.location(), now);
        }
        CaseTransferView view = toView(transferId);
        return record(request.commandKey(), EvidenceService.OP_CASE_TRANSFER_REVOKE, actorId,
                requestHash, 200, view);
    }

    /**
     * 移交明细：主单 + 规范化排序证物 + 要求/实际数量；只读。
     */
    @Transactional(readOnly = true)
    public CaseTransferView getTransfer(long transferId) {
        caseTransferRepository.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "跨案移交不存在: " + transferId));
        return toView(transferId);
    }

    /**
     * 双案封存快照明细（按证物排序、SOURCE/TARGET 顺序）；只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public List<SnapshotView> listSnapshots(long transferId) {
        caseTransferRepository.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "跨案移交不存在: " + transferId));
        return caseTransferRepository.findSnapshots(transferId).stream()
                .map(s -> new SnapshotView(s.transferId(), s.evidenceKey(), s.side(), s.caseKey(),
                        s.location(), s.sealNo(), s.orderVersion(), s.createdAt()))
                .toList();
    }

    /**
     * 案件保管链历史（可限定单件证物），按提交顺序；只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public List<ChainEventView> listCaseEvents(String caseKey, String evidenceKey) {
        return caseTransferRepository.findEventsByCase(caseKey, evidenceKey).stream()
                .map(this::toChainEventView).toList();
    }

    /**
     * 撤销诊断：逐件证物给出当前案件/状态/保管人与阻塞原因，汇总是否可撤销及差额（总数-阻塞数）；只读。
     */
    @Transactional(readOnly = true)
    public TransferDiagnostics diagnose(long transferId) {
        CaseTransfer transfer = caseTransferRepository.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "跨案移交不存在: " + transferId));
        List<CaseTransferItem> items = caseTransferRepository.findItems(transferId);
        List<CaseTransferSnapshot> targetSnapshots = caseTransferRepository.findSnapshots(transferId)
                .stream().filter(s -> s.side() == SnapshotSide.TARGET).toList();
        Map<String, CaseTransferSnapshot> targetByKey = targetSnapshots.stream()
                .collect(Collectors.toMap(CaseTransferSnapshot::evidenceKey, Function.identity()));
        List<ItemDiagnostics> itemDiagnostics = new ArrayList<>();
        int blockedCount = 0;
        for (CaseTransferItem item : items) {
            String key = item.evidenceKey();
            CaseTransferSnapshot target = targetByKey.get(key);
            List<String> reasons = new ArrayList<>();
            Evidence evidence = evidenceRepository.findByKey(key).orElse(null);
            if (transfer.status() != CaseTransferStatus.COMPLETED) {
                reasons.add("移交已撤销");
            }
            if (evidence == null) {
                reasons.add("证物数据缺失");
            } else {
                if (!evidence.caseKey().equals(transfer.toCaseKey())) {
                    reasons.add("已被后续跨案移交移出目标案件");
                }
                if (caseTransferRepository.existsLaterTransfer(key, transferId)) {
                    reasons.add("移交后又发生过其他跨案移交");
                }
                if (evidence.status() == EvidenceStatus.BORROWED
                        || loanRepository.findActiveByEvidenceKey(key).isPresent()
                        || loanRepository.existsAfterId(key, target.lastLoanId())) {
                    reasons.add("目标案件已发生后续借出");
                }
                if (inspectionRepository.existsAfterId(key, target.lastInspectionId())) {
                    reasons.add("目标案件已发生后续封签复核");
                }
                if (transferRepository.existsAfterId(key, target.lastHandoffId())) {
                    reasons.add("目标案件已发生后续保管链交接");
                }
                if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                    reasons.add("封签已异常");
                }
            }
            if (!reasons.isEmpty()) {
                blockedCount++;
            }
            itemDiagnostics.add(new ItemDiagnostics(key,
                    evidence == null ? null : evidence.caseKey(),
                    evidence == null ? null : evidence.custodianId(),
                    evidence == null ? null : evidence.status(),
                    reasons.isEmpty(), reasons));
        }
        boolean revocable = transfer.status() == CaseTransferStatus.COMPLETED && blockedCount == 0;
        return new TransferDiagnostics(transferId, transfer.status(), items.size(),
                items.size() - blockedCount, blockedCount, revocable, itemDiagnostics);
    }

    private void validateOrderAndPermissions(CaseTransferInitiateRequest request, LocalDateTime now) {
        if (!caseCustodianRepository.hasPermission(request.fromCaseKey(), request.fromCustodian())) {
            throw ApiException.forbidden("NO_FROM_CASE_PERMISSION",
                    "来源保管人 " + request.fromCustodian() + " 不具备案件 "
                            + request.fromCaseKey() + " 权限");
        }
        if (!caseCustodianRepository.hasPermission(request.toCaseKey(), request.toCustodian())) {
            throw ApiException.forbidden("NO_TO_CASE_PERMISSION",
                    "目标保管人 " + request.toCustodian() + " 不具备案件 "
                            + request.toCaseKey() + " 权限");
        }
        TransferOrder order = transferOrderRepository
                .findVersion(request.orderKey(), request.orderVersion())
                .orElseThrow(() -> ApiException.forbidden("ORDER_VERSION_INVALID",
                        "移交令版本不存在或已失效: " + request.orderKey()
                                + "@" + request.orderVersion()));
        if (!order.fromCaseKey().equals(request.fromCaseKey())
                || !order.toCaseKey().equals(request.toCaseKey())) {
            throw ApiException.forbidden("ORDER_CASE_MISMATCH",
                    "移交令核准案件与请求不符: 令 " + order.fromCaseKey() + "->" + order.toCaseKey()
                            + "，请求 " + request.fromCaseKey() + "->" + request.toCaseKey());
        }
        // [effectiveFrom, effectiveTo) 左闭右开：等于生效时刻有效，等于失效时刻无效。
        if (now.isBefore(order.effectiveFrom())) {
            throw ApiException.forbidden("ORDER_VERSION_NOT_EFFECTIVE",
                    "移交令版本尚未生效，要求时刻不早于 " + order.effectiveFrom() + "，实际 " + now);
        }
        if (order.effectiveTo() != null && !now.isBefore(order.effectiveTo())) {
            throw ApiException.forbidden("ORDER_VERSION_EXPIRED",
                    "移交令版本已失效，要求时刻早于 " + order.effectiveTo() + "，实际 " + now);
        }
    }

    private CaseTransferSnapshot requireTargetSnapshot(long transferId, String evidenceKey) {
        return caseTransferRepository.findSnapshots(transferId).stream()
                .filter(s -> s.side() == SnapshotSide.TARGET && s.evidenceKey().equals(evidenceKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("目标案快照缺失: " + transferId
                        + "/" + evidenceKey));
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                        "幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private CaseTransferView toView(long transferId) {
        CaseTransfer transfer = caseTransferRepository.findById(transferId).orElseThrow();
        List<String> keys = caseTransferRepository.findItems(transferId).stream()
                .map(CaseTransferItem::evidenceKey).toList();
        return new CaseTransferView(transfer.id(), transfer.fromCaseKey(), transfer.toCaseKey(),
                transfer.orderKey(), transfer.orderVersion(), transfer.fromCustodian(),
                transfer.toCustodian(), transfer.status(), transfer.initiatedBy(), keys,
                keys.size(), transfer.evidenceCount(), transfer.createdAt(), transfer.decidedAt(),
                transfer.revokedByFrom(), transfer.revokedByTo());
    }

    private ChainEventView toChainEventView(CustodyChainEvent event) {
        return new ChainEventView(event.id(), event.transferId(), event.evidenceKey(),
                event.caseKey(), event.eventType(), event.actorId(), event.detail(),
                event.eventTime());
    }

    /**
     * 单件证物撤销诊断项。
     *
     * @param evidenceKey      证物业务键
     * @param currentCaseKey   当前所属案件；null 表示证物缺失
     * @param currentCustodian 当前保管人；null 表示证物缺失
     * @param currentStatus    当前状态；null 表示证物缺失
     * @param revocable        该件是否满足撤销前置条件
     * @param blockingReasons  阻塞原因列表（空表示无阻塞）
     */
    public record ItemDiagnostics(
            String evidenceKey,
            String currentCaseKey,
            String currentCustodian,
            EvidenceStatus currentStatus,
            boolean revocable,
            List<String> blockingReasons) {
    }

    /**
     * 移交撤销诊断结果：实际可撤销数量、要求数量（全部证物）与阻塞数量差额。
     *
     * @param transferId        移交主键
     * @param status            移交当前状态
     * @param requestedCount    要求覆盖数量（移交证物总数）
     * @param revocableCount    实际满足撤销条件数量
     * @param blockedCount      阻塞数量（差额 = 要求值 - 实际值）
     * @param revocable         整单是否可撤销
     * @param itemDiagnostics   逐件诊断
     */
    public record TransferDiagnostics(
            long transferId,
            CaseTransferStatus status,
            int requestedCount,
            int revocableCount,
            int blockedCount,
            boolean revocable,
            List<ItemDiagnostics> itemDiagnostics) {
    }
}
