package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferChainView;
import com.example.starter.evidence.dto.CaseTransferDetailView;
import com.example.starter.evidence.dto.CaseTransferItemView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.TransferOrderRequest;
import com.example.starter.evidence.dto.TransferOrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 证物跨案移交核心服务。批量移交先校验完整最终状态，再在同一事务内写入：
 * 来源案件移出链、目标案件移入链与同一 transferId 的双案封存快照；
 * 任一证物校验失败整批回滚，查询不到半成品。所有记录只追加，撤销仅追加反向链。
 * 本服务产生的全部业务时间统一为 UTC；有效期等区间端点按左闭右开解释。
 */
@Service
public class CaseTransferService {

    static final String OP_CASE_TRANSFER = "CASE_TRANSFER";
    static final String OP_CASE_TRANSFER_REVOKE = "CASE_TRANSFER_REVOKE";
    static final String OP_ORDER_REGISTER = "TRANSFER_ORDER_REGISTER";

    private final EvidenceRepository evidenceRepository;
    private final TransferOrderRepository orderRepository;
    private final CaseTransferRepository transferRepository;
    private final CaseTransferItemRepository itemRepository;
    private final CaseTransferChainRepository chainRepository;
    private final CaseCustodianRepository custodianRepository;
    private final SealInspectionRepository inspectionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public CaseTransferService(EvidenceRepository evidenceRepository,
                               TransferOrderRepository orderRepository,
                               CaseTransferRepository transferRepository,
                               CaseTransferItemRepository itemRepository,
                               CaseTransferChainRepository chainRepository,
                               CaseCustodianRepository custodianRepository,
                               SealInspectionRepository inspectionRepository,
                               CommandLogRepository commandLogRepository,
                               ObjectMapper objectMapper,
                               EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.orderRepository = orderRepository;
        this.transferRepository = transferRepository;
        this.itemRepository = itemRepository;
        this.chainRepository = chainRepository;
        this.custodianRepository = custodianRepository;
        this.inspectionRepository = inspectionRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记移交令：令版本全局唯一；validTo 须晚于 validFrom（UTC 左闭右开区间）。
     */
    @Transactional
    public StoredResponse registerOrder(String actorId, TransferOrderRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.badRequest("移交令来源与目标案件不得相同: " + request.sourceCaseKey());
        }
        if (!request.validTo().isAfter(request.validFrom())) {
            throw ApiException.badRequest("有效期终点须晚于起点: [" + request.validFrom()
                    + ", " + request.validTo() + ")");
        }
        if (orderRepository.findByVersion(request.orderVersion()).isPresent()) {
            throw ApiException.conflict("移交令版本已存在: " + request.orderVersion());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        orderRepository.insert(request.orderVersion(), request.sourceCaseKey(), request.targetCaseKey(),
                request.validFrom(), request.validTo(), actorId, nowUtc);
        TransferOrder order = orderRepository.findByVersion(request.orderVersion()).orElseThrow();
        return record(request.commandKey(), OP_ORDER_REGISTER, actorId, requestHash, 201, toView(order));
    }

    /**
     * 跨案移交：证物须在库（SEALED）、封签正常且未借出，且全部属于来源案件。
     * 先按证物键规范排序并逐行锁定，校验完整最终状态后在一个事务内提交：
     * 移交头 + 每证物双案封存快照 + 来源 MOVE_OUT / 目标 MOVE_IN 链条目 + 证物案件归属原子切换。
     */
    @Transactional
    public StoredResponse transfer(String actorId, CaseTransferRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.badRequest("来源与目标案件不得相同: " + request.sourceCaseKey());
        }
        if (request.sourceCustodian().equals(request.targetCustodian())) {
            throw ApiException.conflict("双方保管人不得相同: " + request.sourceCustodian());
        }
        List<String> evidenceKeys = normalizeEvidenceKeys(request.evidenceKeys());
        // 按规范排序逐行锁定证物，保证并发移交/借出/撤销按提交顺序裁决且不会死锁。
        Map<String, Evidence> locked = lockEvidenceInOrder(evidenceKeys);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        requireValidOrder(request, nowUtc);
        // 先校验证物状态（422），再校验双方保管人权限（403），保证失败原因可区分。
        for (String evidenceKey : evidenceKeys) {
            requireTransferable(locked.get(evidenceKey), request.sourceCaseKey());
        }
        requirePermissions(request, locked);
        String transferId = newTransferId();
        transferRepository.insert(transferId, request.sourceCaseKey(), request.targetCaseKey(),
                request.orderVersion(), request.sourceCustodian(), request.targetCustodian(),
                actorId, evidenceKeys.size(), nowUtc);
        int seq = 1;
        for (String evidenceKey : evidenceKeys) {
            Evidence evidence = locked.get(evidenceKey);
            int sealVersion = inspectionRepository.countByEvidenceKey(evidenceKey) + 1;
            itemRepository.insert(transferId, evidenceKey, seq,
                    request.sourceCaseKey(), request.targetCaseKey(),
                    request.sourceCustodian(), request.targetCustodian(),
                    evidence.sealNo(), sealVersion, request.orderVersion(), nowUtc);
            chainRepository.insert(transferId, evidenceKey, request.sourceCaseKey(),
                    ChainEntryType.MOVE_OUT, request.sourceCustodian(), request.orderVersion(), nowUtc);
            chainRepository.insert(transferId, evidenceKey, request.targetCaseKey(),
                    ChainEntryType.MOVE_IN, request.targetCustodian(), request.orderVersion(), nowUtc);
            evidenceRepository.updateCaseCustody(evidenceKey, request.targetCaseKey(),
                    request.targetCustodian(), LocalDateTime.now());
            seq++;
        }
        // 目标案件尚无登记保管人时，接收保管人登记为首位保管人。
        custodianRepository.registerIfAbsent(request.targetCaseKey(), request.targetCustodian(), nowUtc);
        return record(request.commandKey(), OP_CASE_TRANSFER, actorId, requestHash,
                200, detailView(transferId));
    }

    /**
     * 撤销跨案移交：仅限目标案件尚未发生后续交接（证物仍在目标案件、目标保管人名下且在库），
     * 由双方不同保管人确认；追加反向链并将证物归属切回来源案件，不删除原移交。
     */
    @Transactional
    public StoredResponse revoke(String actorId, String transferId,
                                 CaseTransferRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        CaseTransfer transfer = transferRepository.findByTransferIdForUpdate(transferId)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferId));
        List<CaseTransferItem> items = itemRepository.findByTransferId(transferId);
        List<String> evidenceKeys = items.stream().map(CaseTransferItem::evidenceKey).sorted().toList();
        Map<String, Evidence> locked = lockEvidenceInOrder(evidenceKeys);
        // 并发下本事务可能在行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (transfer.status() != CaseTransferStatus.COMPLETED) {
            throw ApiException.conflict("移交已撤销，不得重复撤销: " + transferId);
        }
        if (request.sourceConfirmer().equals(request.targetConfirmer())) {
            throw ApiException.conflict("撤销确认人不得为同一人: " + request.sourceConfirmer());
        }
        if (!request.sourceConfirmer().equals(transfer.sourceCustodian())) {
            throw ApiException.forbidden("来源确认人不具备来源案件权限: " + request.sourceConfirmer());
        }
        if (!request.targetConfirmer().equals(transfer.targetCustodian())) {
            throw ApiException.forbidden("目标确认人不具备目标案件权限: " + request.targetConfirmer());
        }
        for (String evidenceKey : evidenceKeys) {
            Evidence evidence = locked.get(evidenceKey);
            if (!evidence.caseKey().equals(transfer.targetCaseKey())
                    || !evidence.custodianId().equals(transfer.targetCustodian())
                    || evidence.status() != EvidenceStatus.SEALED) {
                throw ApiException.conflict("目标案件已发生后续交接或借出，禁止撤销: " + evidenceKey
                        + "（当前案件=" + evidence.caseKey() + "，保管人=" + evidence.custodianId()
                        + "，状态=" + evidence.status() + "）");
            }
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!transferRepository.markRevoked(transfer.id(), request.sourceConfirmer(),
                request.targetConfirmer(), nowUtc)) {
            throw ApiException.conflict("移交已被并发撤销: " + transferId);
        }
        itemRepository.markRevokedByTransferId(transferId, nowUtc);
        for (String evidenceKey : evidenceKeys) {
            chainRepository.insert(transferId, evidenceKey, transfer.targetCaseKey(),
                    ChainEntryType.REVOKE_OUT, transfer.targetCustodian(), transfer.orderVersion(), nowUtc);
            chainRepository.insert(transferId, evidenceKey, transfer.sourceCaseKey(),
                    ChainEntryType.REVOKE_IN, transfer.sourceCustodian(), transfer.orderVersion(), nowUtc);
            evidenceRepository.updateCaseCustody(evidenceKey, transfer.sourceCaseKey(),
                    transfer.sourceCustodian(), LocalDateTime.now());
        }
        return record(request.commandKey(), OP_CASE_TRANSFER_REVOKE, actorId, requestHash,
                200, detailView(transferId));
    }

    /**
     * 查询跨案移交明细：移交头 + 双案封存快照 + 双案链条目（只读，不改变状态）。
     */
    @Transactional(readOnly = true)
    public CaseTransferDetailView detail(String transferId) {
        transferRepository.findByTransferId(transferId)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferId));
        return detailView(transferId);
    }

    /**
     * 按案件查询双案保管链历史（该案件的只读链证据；只读，不改变状态）。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferChainView> chainByCase(String caseKey) {
        return chainRepository.findByCaseKey(caseKey).stream().map(this::toView).toList();
    }

    /**
     * 按证物查询跨案移交历史（全部快照，按发生顺序；只读，不改变状态）。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferItemView> historyByEvidence(String evidenceKey) {
        return itemRepository.findByEvidenceKey(evidenceKey).stream().map(this::toView).toList();
    }

    /**
     * 规范化证物列表：去重校验后按字典序排序，保证指纹与处理顺序稳定。
     */
    private List<String> normalizeEvidenceKeys(List<String> evidenceKeys) {
        List<String> sorted = evidenceKeys.stream().sorted().toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw ApiException.badRequest("证物列表存在重复: " + sorted.get(i));
            }
        }
        return sorted;
    }

    private Map<String, Evidence> lockEvidenceInOrder(List<String> evidenceKeys) {
        Map<String, Evidence> locked = new LinkedHashMap<>();
        for (String evidenceKey : evidenceKeys) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
            locked.put(evidenceKey, evidence);
        }
        return locked;
    }

    private void requireValidOrder(CaseTransferRequest request, LocalDateTime nowUtc) {
        TransferOrder order = orderRepository.findByVersion(request.orderVersion())
                .orElseThrow(() -> ApiException.conflict("移交令版本不存在或已失效: " + request.orderVersion()));
        if (!order.sourceCaseKey().equals(request.sourceCaseKey())
                || !order.targetCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.conflict("移交令不适用于该案件对: " + request.orderVersion()
                    + "（令限定 " + order.sourceCaseKey() + " -> " + order.targetCaseKey() + "）");
        }
        // 有效期按 UTC 左闭右开 [validFrom, validTo) 解释。
        if (nowUtc.isBefore(order.validFrom()) || !nowUtc.isBefore(order.validTo())) {
            throw ApiException.conflict("移交令版本已失效: " + request.orderVersion()
                    + "（有效期 [" + order.validFrom() + ", " + order.validTo() + ") UTC，当前 " + nowUtc + "）");
        }
    }

    private void requirePermissions(CaseTransferRequest request, Map<String, Evidence> locked) {
        if (!custodianRepository.exists(request.sourceCaseKey(), request.sourceCustodian())) {
            throw ApiException.forbidden("来源保管人不具备来源案件权限: " + request.sourceCustodian());
        }
        for (Evidence evidence : locked.values()) {
            if (!evidence.custodianId().equals(request.sourceCustodian())) {
                throw ApiException.forbidden("来源保管人不是证物当前保管人: " + evidence.evidenceKey()
                        + "（当前保管人=" + evidence.custodianId() + "）");
            }
        }
        if (custodianRepository.hasAnyCustodian(request.targetCaseKey())
                && !custodianRepository.exists(request.targetCaseKey(), request.targetCustodian())) {
            throw ApiException.forbidden("目标保管人不具备目标案件权限: " + request.targetCustodian());
        }
    }

    private void requireTransferable(Evidence evidence, String sourceCaseKey) {
        if (!evidence.caseKey().equals(sourceCaseKey)) {
            throw ApiException.unprocessable("证物不属于来源案件: " + evidence.evidenceKey()
                    + "（所属案件=" + evidence.caseKey() + "，要求来源案件=" + sourceCaseKey + "）");
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.unprocessable("证物借出未归还，禁止跨案移交: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("证物封签异常，禁止跨案移交: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.unprocessable("证物已处于其他移交中: " + evidence.evidenceKey());
        }
    }

    private String newTransferId() {
        return "CT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private CaseTransferDetailView detailView(String transferId) {
        CaseTransfer transfer = transferRepository.findByTransferId(transferId).orElseThrow();
        List<CaseTransferItemView> items = itemRepository.findByTransferId(transferId)
                .stream().map(this::toView).toList();
        List<CaseTransferChainView> chain = chainRepository.findByTransferId(transferId)
                .stream().map(this::toView).toList();
        return new CaseTransferDetailView(toView(transfer), items, chain);
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
        return new TransferOrderView(order.orderVersion(), order.sourceCaseKey(), order.targetCaseKey(),
                order.validFrom(), order.validTo(), order.createdBy(), order.createdAt());
    }

    private CaseTransferView toView(CaseTransfer transfer) {
        return new CaseTransferView(transfer.transferId(), transfer.sourceCaseKey(),
                transfer.targetCaseKey(), transfer.orderVersion(), transfer.sourceCustodian(),
                transfer.targetCustodian(), transfer.status(), transfer.actorId(),
                transfer.evidenceCount(), transfer.createdAt(), transfer.revokedAt(),
                transfer.revokeSourceConfirmer(), transfer.revokeTargetConfirmer());
    }

    private CaseTransferItemView toView(CaseTransferItem item) {
        return new CaseTransferItemView(item.transferId(), item.evidenceKey(), item.seqNo(),
                item.sourceCaseKey(), item.targetCaseKey(), item.sourceCustodian(),
                item.targetCustodian(), item.sealNo(), item.sealVersion(), item.orderVersion(),
                item.status(), item.createdAt(), item.revokedAt());
    }

    private CaseTransferChainView toView(CaseTransferChainEntry entry) {
        return new CaseTransferChainView(entry.transferId(), entry.evidenceKey(), entry.caseKey(),
                entry.entryType(), entry.custodianId(), entry.orderVersion(), entry.createdAt());
    }
}
