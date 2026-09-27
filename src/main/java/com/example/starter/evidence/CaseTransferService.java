package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferDiagnosticsView;
import com.example.starter.evidence.dto.CaseTransferExecuteRequest;
import com.example.starter.evidence.dto.CaseTransferItemView;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.ChainEntryView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.TransferOrderRegisterRequest;
import com.example.starter.evidence.dto.TransferOrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 证物跨案移交核心服务。一次请求可包含规范排序（证物键升序）的多件证物：
 * 先校验完整最终状态，再在同一事务内写入来源案件移出链、目标案件移入链与
 * 同一 transferId 的双案封存快照，并原子变更证物案件归属与保管人。
 * 任一证物校验失败整批回滚，不写入任何保管链；撤销仅追加反向链，不删除原移交。
 * 新表时间统一使用 UTC（{@link EvidenceClock}）。
 */
@Service
public class CaseTransferService {

    static final String OP_ORDER_REGISTER = "TRANSFER_ORDER_REGISTER";
    static final String OP_ORDER_REVOKE = "TRANSFER_ORDER_REVOKE";
    static final String OP_CASE_TRANSFER = "CASE_TRANSFER";
    static final String OP_CASE_TRANSFER_REVOKE = "CASE_TRANSFER_REVOKE";

    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_REVOKED = "REVOKED";

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final LoanRecordRepository loanRepository;
    private final CommandLogRepository commandLogRepository;
    private final CaseCustodianRepository caseCustodianRepository;
    private final TransferOrderRepository orderRepository;
    private final CaseTransferChainRepository chainRepository;
    private final CaseTransferSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public CaseTransferService(EvidenceRepository evidenceRepository,
                               TransferRecordRepository transferRepository,
                               LoanRecordRepository loanRepository,
                               CommandLogRepository commandLogRepository,
                               CaseCustodianRepository caseCustodianRepository,
                               TransferOrderRepository orderRepository,
                               CaseTransferChainRepository chainRepository,
                               CaseTransferSnapshotRepository snapshotRepository,
                               ObjectMapper objectMapper,
                               EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.loanRepository = loanRepository;
        this.commandLogRepository = commandLogRepository;
        this.caseCustodianRepository = caseCustodianRepository;
        this.orderRepository = orderRepository;
        this.chainRepository = chainRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记移交令：令版本全局唯一，来源与目标案件不得相同，初始状态 VALID。
     */
    @Transactional
    public StoredResponse registerOrder(String actorId, TransferOrderRegisterRequest request,
                                        String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.badRequest("来源与目标案件不得相同: " + request.sourceCaseKey());
        }
        if (orderRepository.findByVersion(request.orderVersion()).isPresent()) {
            throw ApiException.conflict("移交令版本已存在: " + request.orderVersion());
        }
        orderRepository.insert(request.orderVersion(), request.sourceCaseKey(),
                request.targetCaseKey(), actorId, clock.nowUtc());
        TransferOrder order = orderRepository.findByVersion(request.orderVersion()).orElseThrow();
        return record(request.commandKey(), OP_ORDER_REGISTER, actorId, requestHash,
                201, toView(order));
    }

    /**
     * 撤销移交令：版本失效，不可再用于新移交；历史快照中固化的令版本不受影响。
     */
    @Transactional
    public StoredResponse revokeOrder(String actorId, String orderVersion,
                                      CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 锁定令行，与并发移交校验串行化
        TransferOrder order = orderRepository.findByVersionForUpdate(orderVersion)
                .orElseThrow(() -> ApiException.notFound("移交令不存在: " + orderVersion));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!orderRepository.revoke(orderVersion, clock.nowUtc())) {
            throw ApiException.conflict("移交令已撤销: " + orderVersion);
        }
        TransferOrder revoked = orderRepository.findByVersion(orderVersion).orElseThrow();
        return record(request.commandKey(), OP_ORDER_REVOKE, actorId, requestHash,
                200, toView(revoked));
    }

    /**
     * 执行跨案移交（请求证物键已由控制器规范排序）：
     * 逐键加行锁后校验全部证物最终状态，再在同一事务内写双案链与快照并变更案件归属。
     */
    @Transactional
    public StoredResponse transfer(String actorId, CaseTransferExecuteRequest request,
                                   String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.badRequest("来源与目标案件不得相同: " + request.sourceCaseKey());
        }
        if (request.sourceCustodianId().equals(request.targetCustodianId())) {
            throw ApiException.conflict("双方保管人不得相同: " + request.sourceCustodianId());
        }
        // 按升序逐键锁定证物行，保证并发移交/借出/撤销按提交顺序裁决且不会死锁
        List<Evidence> locked = new ArrayList<>();
        for (String evidenceKey : request.evidenceKeys()) {
            locked.add(lockEvidence(evidenceKey));
        }
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        TransferOrder order = orderRepository.findByVersionForUpdate(request.orderVersion())
                .orElseThrow(() -> ApiException.conflict("移交令版本失效: " + request.orderVersion()));
        if (order.status() != TransferOrderStatus.VALID) {
            throw ApiException.conflict("移交令版本失效: " + request.orderVersion());
        }
        if (!order.sourceCaseKey().equals(request.sourceCaseKey())
                || !order.targetCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.conflict("移交令版本与案件对不匹配: " + request.orderVersion());
        }
        if (!caseCustodianRepository.isAuthorized(request.sourceCaseKey(), request.sourceCustodianId())) {
            throw ApiException.forbidden(
                    "来源保管人不具备来源案件权限: " + request.sourceCustodianId());
        }
        if (!caseCustodianRepository.isAuthorized(request.targetCaseKey(), request.targetCustodianId())) {
            throw ApiException.forbidden(
                    "目标保管人不具备目标案件权限: " + request.targetCustodianId());
        }
        for (Evidence evidence : locked) {
            requireTransferable(evidence, request.sourceCaseKey(), request.sourceCustodianId());
        }
        String transferId = "CT-" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime nowUtc = clock.nowUtc();
        for (Evidence evidence : locked) {
            evidenceRepository.updateCaseCustody(evidence.evidenceKey(), request.targetCaseKey(),
                    request.targetCustodianId(), nowUtc);
            chainRepository.insert(transferId, evidence.evidenceKey(), ChainDirection.OUT,
                    request.sourceCaseKey(), request.targetCaseKey(),
                    request.sourceCustodianId(), request.orderVersion(), nowUtc);
            chainRepository.insert(transferId, evidence.evidenceKey(), ChainDirection.IN,
                    request.targetCaseKey(), request.sourceCaseKey(),
                    request.targetCustodianId(), request.orderVersion(), nowUtc);
            snapshotRepository.insert(transferId, evidence.evidenceKey(),
                    request.sourceCaseKey(), request.targetCaseKey(),
                    request.sourceCustodianId(), request.targetCustodianId(),
                    request.sourceCaseKey() + "#" + request.sourceCustodianId(),
                    evidence.sealNo(), request.orderVersion(), nowUtc);
        }
        return record(request.requestId(), OP_CASE_TRANSFER, actorId, requestHash,
                200, buildView(transferId));
    }

    /**
     * 撤销跨案移交：仅限目标案件尚未发生后续交接（保管人交接、借出或再次跨案移交）时；
     * 由双方不同保管人确认，追加反向链并把证物退回来源案件，不删除原移交。
     */
    @Transactional
    public StoredResponse revokeTransfer(String actorId, String transferId,
                                         CaseTransferRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<CaseTransferSnapshot> snapshots = snapshotRepository.findByTransferId(transferId);
        if (snapshots.isEmpty()) {
            throw ApiException.notFound("移交批次不存在: " + transferId);
        }
        if (request.sourceConfirmBy().equals(request.targetConfirmBy())) {
            throw ApiException.conflict("双方确认保管人不得相同: " + request.sourceConfirmBy());
        }
        String sourceCaseKey = snapshots.get(0).sourceCaseKey();
        String targetCaseKey = snapshots.get(0).targetCaseKey();
        if (!caseCustodianRepository.isAuthorized(sourceCaseKey, request.sourceConfirmBy())) {
            throw ApiException.forbidden("来源确认人不具备来源案件权限: " + request.sourceConfirmBy());
        }
        if (!caseCustodianRepository.isAuthorized(targetCaseKey, request.targetConfirmBy())) {
            throw ApiException.forbidden("目标确认人不具备目标案件权限: " + request.targetConfirmBy());
        }
        requireNotRevoked(transferId);
        // 按快照证物键升序逐键锁定（快照查询已按证物键排序）
        Map<String, Evidence> lockedByKey = new LinkedHashMap<>();
        for (CaseTransferSnapshot snapshot : snapshots) {
            lockedByKey.put(snapshot.evidenceKey(), lockEvidence(snapshot.evidenceKey()));
        }
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志与撤销状态。
        replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireNotRevoked(transferId);
        for (CaseTransferSnapshot snapshot : snapshots) {
            requireNoFollowUp(snapshot, lockedByKey.get(snapshot.evidenceKey()));
        }
        LocalDateTime nowUtc = clock.nowUtc();
        for (CaseTransferSnapshot snapshot : snapshots) {
            chainRepository.insert(transferId, snapshot.evidenceKey(), ChainDirection.REVOKE_OUT,
                    targetCaseKey, sourceCaseKey, request.targetConfirmBy(),
                    snapshot.orderVersion(), nowUtc);
            chainRepository.insert(transferId, snapshot.evidenceKey(), ChainDirection.REVOKE_IN,
                    sourceCaseKey, targetCaseKey, request.sourceConfirmBy(),
                    snapshot.orderVersion(), nowUtc);
            evidenceRepository.updateCaseCustody(snapshot.evidenceKey(), sourceCaseKey,
                    snapshot.sourceCustodianId(), nowUtc);
        }
        return record(request.requestId(), OP_CASE_TRANSFER_REVOKE, actorId, requestHash,
                200, buildView(transferId));
    }

    /**
     * 移交明细查询：批次头 + 全部证物快照 + 全部保管链条目（含反向链）。只读。
     */
    @Transactional(readOnly = true)
    public CaseTransferView getTransfer(String transferId) {
        if (snapshotRepository.findByTransferId(transferId).isEmpty()) {
            throw ApiException.notFound("移交批次不存在: " + transferId);
        }
        return buildView(transferId);
    }

    /**
     * 按证物查询跨案移交历史（按发生顺序）。只读。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferView> listByEvidence(String evidenceKey) {
        List<String> transferIds = snapshotRepository.findByEvidenceKey(evidenceKey).stream()
                .map(CaseTransferSnapshot::transferId)
                .distinct()
                .toList();
        return transferIds.stream().map(this::buildView).toList();
    }

    /**
     * 按案件查询跨案移交历史（作为来源或目标）。只读。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferView> listByCase(String caseKey) {
        return snapshotRepository.findTransferIdsByCase(caseKey).stream()
                .map(this::buildView)
                .toList();
    }

    /**
     * 移交诊断：校验批次内快照与双案链数量一致性。只读，不改变任何状态。
     */
    @Transactional(readOnly = true)
    public CaseTransferDiagnosticsView diagnostics(String transferId) {
        List<CaseTransferSnapshot> snapshots = snapshotRepository.findByTransferId(transferId);
        if (snapshots.isEmpty()) {
            throw ApiException.notFound("移交批次不存在: " + transferId);
        }
        List<CaseTransferChainEntry> chain = chainRepository.findByTransferId(transferId);
        int out = countDirection(chain, ChainDirection.OUT);
        int in = countDirection(chain, ChainDirection.IN);
        int revokeOut = countDirection(chain, ChainDirection.REVOKE_OUT);
        int revokeIn = countDirection(chain, ChainDirection.REVOKE_IN);
        boolean revoked = revokeOut > 0 || revokeIn > 0;
        boolean balanced = out == snapshots.size() && in == snapshots.size();
        boolean consistent = balanced
                && (!revoked || (revokeOut == snapshots.size() && revokeIn == snapshots.size()));
        return new CaseTransferDiagnosticsView(transferId, snapshots.size(), out, in,
                revokeOut, revokeIn, revoked, balanced, consistent);
    }

    private void requireTransferable(Evidence evidence, String sourceCaseKey, String sourceCustodianId) {
        if (!evidence.caseKey().equals(sourceCaseKey)) {
            throw ApiException.unprocessable(
                    "证物不属于来源案件: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.unprocessable(
                    "证物借出未归还，禁止跨案移交: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable(
                    "证物封签异常，禁止跨案移交: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.unprocessable(
                    "证物已处于其他移交中: " + evidence.evidenceKey());
        }
        if (!evidence.custodianId().equals(sourceCustodianId)) {
            throw ApiException.conflict(
                    "证物当前保管人与来源保管人不一致: " + evidence.evidenceKey());
        }
    }

    private void requireNotRevoked(String transferId) {
        boolean revoked = chainRepository.findByTransferId(transferId).stream()
                .anyMatch(entry -> entry.direction() == ChainDirection.REVOKE_OUT
                        || entry.direction() == ChainDirection.REVOKE_IN);
        if (revoked) {
            throw ApiException.conflict("移交已撤销，不得重复撤销: " + transferId);
        }
    }

    private void requireNoFollowUp(CaseTransferSnapshot snapshot, Evidence evidence) {
        String evidenceKey = snapshot.evidenceKey();
        if (!evidence.caseKey().equals(snapshot.targetCaseKey())
                || !evidence.custodianId().equals(snapshot.targetCustodianId())
                || evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("目标案件已发生后续交接或状态变更，禁止撤销: " + evidenceKey);
        }
        if (loanRepository.countLoansSince(evidenceKey, snapshot.createdAt()) > 0) {
            throw ApiException.conflict("目标案件已发生后续借出，禁止撤销: " + evidenceKey);
        }
        // transfer_record.created_at 使用本机默认时区，将 UTC 快照时间转换到同一时域再比较
        LocalDateTime handoverBound = snapshot.createdAt().atZone(ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        if (transferRepository.countSince(evidenceKey, handoverBound) > 0) {
            throw ApiException.conflict("目标案件已发生后续交接，禁止撤销: " + evidenceKey);
        }
        if (chainRepository.countLaterTransfers(evidenceKey, snapshot.transferId(),
                snapshot.createdAt()) > 0) {
            throw ApiException.conflict("证物已发生后续跨案移交，禁止撤销: " + evidenceKey);
        }
    }

    private CaseTransferView buildView(String transferId) {
        List<CaseTransferSnapshot> snapshots = snapshotRepository.findByTransferId(transferId);
        List<CaseTransferChainEntry> chain = chainRepository.findByTransferId(transferId);
        List<CaseTransferItemView> items = snapshots.stream()
                .map(snapshot -> new CaseTransferItemView(snapshot.evidenceKey(),
                        snapshot.sourceCustodianId(), snapshot.targetCustodianId(),
                        snapshot.originLocation(), snapshot.sealNo()))
                .toList();
        List<ChainEntryView> chainViews = chain.stream().map(this::toView).toList();
        LocalDateTime revokedAt = chain.stream()
                .filter(entry -> entry.direction() == ChainDirection.REVOKE_IN)
                .map(CaseTransferChainEntry::createdAt)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        CaseTransferSnapshot first = snapshots.get(0);
        return new CaseTransferView(transferId,
                revokedAt == null ? STATUS_COMPLETED : STATUS_REVOKED,
                first.orderVersion(), first.sourceCaseKey(), first.targetCaseKey(),
                items, chainViews, first.createdAt(), revokedAt);
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private int countDirection(List<CaseTransferChainEntry> chain, ChainDirection direction) {
        return (int) chain.stream().filter(entry -> entry.direction() == direction).count();
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
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

    private TransferOrderView toView(TransferOrder order) {
        return new TransferOrderView(order.orderVersion(), order.sourceCaseKey(),
                order.targetCaseKey(), order.status().name(), order.createdBy(),
                order.createdAt(), order.revokedAt());
    }

    private ChainEntryView toView(CaseTransferChainEntry entry) {
        return new ChainEntryView(entry.transferId(), entry.evidenceKey(),
                entry.direction().name(), entry.caseKey(), entry.counterCaseKey(),
                entry.custodianId(), entry.orderVersion(), entry.createdAt());
    }
}
