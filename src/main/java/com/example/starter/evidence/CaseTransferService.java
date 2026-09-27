package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseCustodianGrantRequest;
import com.example.starter.evidence.dto.CaseTransferChainEntryView;
import com.example.starter.evidence.dto.CaseTransferDetailView;
import com.example.starter.evidence.dto.CaseTransferDiagnosticsView;
import com.example.starter.evidence.dto.CaseTransferItemView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.TransferOrderCreateRequest;
import com.example.starter.evidence.dto.TransferOrderView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 证物跨案移交核心服务。一次请求可包含多件证物：
 * 先按字典序锁定全部证物行并校验完整最终状态，再在单事务内写入
 * 来源案件移出链、目标案件移入链与同一 transferId 的双案封存快照，并原子切换案件归属。
 * 撤销仅追加反向链，不删除原移交；所有快照字段固化后不改写。新表时间统一为 UTC。
 */
@Service
public class CaseTransferService {

    static final String OP_CASE_CUSTODIAN_GRANT = "CASE_CUSTODIAN_GRANT";
    static final String OP_TRANSFER_ORDER_CREATE = "TRANSFER_ORDER_CREATE";
    static final String OP_TRANSFER_ORDER_REVOKE = "TRANSFER_ORDER_REVOKE";
    static final String OP_CASE_TRANSFER = "CASE_TRANSFER";
    static final String OP_CASE_TRANSFER_REVOKE = "CASE_TRANSFER_REVOKE";

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final CaseCustodianRepository caseCustodianRepository;
    private final TransferOrderRepository transferOrderRepository;
    private final CaseTransferRepository caseTransferRepository;
    private final CaseTransferItemRepository itemRepository;
    private final CaseTransferChainRepository chainRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public CaseTransferService(EvidenceRepository evidenceRepository,
                               TransferRecordRepository transferRepository,
                               SealInspectionRepository inspectionRepository,
                               LoanRecordRepository loanRepository,
                               CaseCustodianRepository caseCustodianRepository,
                               TransferOrderRepository transferOrderRepository,
                               CaseTransferRepository caseTransferRepository,
                               CaseTransferItemRepository itemRepository,
                               CaseTransferChainRepository chainRepository,
                               CommandLogRepository commandLogRepository,
                               ObjectMapper objectMapper,
                               EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.caseCustodianRepository = caseCustodianRepository;
        this.transferOrderRepository = transferOrderRepository;
        this.caseTransferRepository = caseTransferRepository;
        this.itemRepository = itemRepository;
        this.chainRepository = chainRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 案件保管人授权：新案件首名保管人可自助登记，之后仅已有保管人可追加。
     */
    @Transactional
    public StoredResponse grantCustodian(String actorId, CaseCustodianGrantRequest request,
                                         String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (caseCustodianRepository.exists(request.caseKey(), request.custodianId())) {
            throw ApiException.conflict("保管人已具备该案件权限: " + request.custodianId());
        }
        boolean caseHasCustodian = caseCustodianRepository.existsAnyForCase(request.caseKey());
        if (caseHasCustodian && !caseCustodianRepository.exists(request.caseKey(), actorId)) {
            throw ApiException.forbidden("操作人不具备该案件保管权限，无法授权: " + actorId);
        }
        caseCustodianRepository.insert(request.caseKey(), request.custodianId(), actorId,
                clock.nowUtc());
        return record(request.commandKey(), OP_CASE_CUSTODIAN_GRANT, actorId, requestHash,
                201, new GrantView(request.caseKey(), request.custodianId(), actorId));
    }

    /**
     * 签发移交令：签发人须具备来源案件保管权限；有效期 UTC 左闭右开。
     */
    @Transactional
    public StoredResponse createOrder(String actorId, TransferOrderCreateRequest request,
                                      String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.badRequest("来源与目标案件不得相同: " + request.sourceCaseKey());
        }
        if (!request.validUntil().isAfter(request.validFrom())) {
            throw ApiException.badRequest("失效时刻须晚于生效时刻: validFrom="
                    + request.validFrom() + ", validUntil=" + request.validUntil());
        }
        if (!caseCustodianRepository.exists(request.sourceCaseKey(), actorId)) {
            throw ApiException.forbidden("签发人不具备来源案件保管权限: " + actorId);
        }
        if (transferOrderRepository.findByVersion(request.orderVersion()).isPresent()) {
            throw ApiException.conflict("移交令版本已存在: " + request.orderVersion());
        }
        transferOrderRepository.insert(request.orderVersion(), request.sourceCaseKey(),
                request.targetCaseKey(), request.validFrom(), request.validUntil(),
                actorId, clock.nowUtc());
        TransferOrder order = transferOrderRepository.findByVersion(request.orderVersion())
                .orElseThrow();
        return record(request.commandKey(), OP_TRANSFER_ORDER_CREATE, actorId, requestHash,
                201, toView(order));
    }

    /**
     * 撤销移交令：仅来源案件保管人；撤销后令立即失效（终态）。
     */
    @Transactional
    public StoredResponse revokeOrder(String actorId, String orderVersion,
                                      CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        TransferOrder order = transferOrderRepository.findByVersion(orderVersion)
                .orElseThrow(() -> ApiException.notFound("移交令不存在: " + orderVersion));
        if (!caseCustodianRepository.exists(order.sourceCaseKey(), actorId)) {
            throw ApiException.forbidden("操作人不具备来源案件保管权限: " + actorId);
        }
        if (!transferOrderRepository.revoke(orderVersion, clock.nowUtc())) {
            throw ApiException.conflict("移交令已撤销: " + orderVersion);
        }
        TransferOrder revoked = transferOrderRepository.findByVersion(orderVersion).orElseThrow();
        return record(request.commandKey(), OP_TRANSFER_ORDER_REVOKE, actorId, requestHash,
                200, toView(revoked));
    }

    /**
     * 跨案移交：整批证物在单事务内完成。
     * 任一证物不属于来源案件、借出未归还、封签异常或已处于其他移交中返回 422，整批不写入；
     * 令失效或权限不足返回 403，双方保管人相同返回 409。
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
        List<String> evidenceKeys = normalizeEvidenceKeys(request.evidenceKeys());
        if (!request.sourceCustodianId().equals(actorId)) {
            throw ApiException.forbidden("操作人必须是来源案件保管人: " + actorId);
        }
        if (request.sourceCustodianId().equals(request.targetCustodianId())) {
            throw ApiException.conflict("双方保管人不得相同: " + actorId);
        }
        requireOrderUsable(request.orderVersion(), request.sourceCaseKey(), request.targetCaseKey());
        requireCasePermission(request.sourceCaseKey(), request.sourceCustodianId(), "来源");
        requireCasePermission(request.targetCaseKey(), request.targetCustodianId(), "目标");

        // 按规范化排序依次锁定证物行，避免并发死锁；持锁后复查幂等日志。
        List<Evidence> locked = new ArrayList<>();
        for (String evidenceKey : evidenceKeys) {
            locked.add(evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey)));
        }
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        for (Evidence evidence : locked) {
            requireTransferable(evidence, request.sourceCaseKey());
        }

        LocalDateTime nowUtc = clock.nowUtc();
        String transferKey = newTransferKey();
        caseTransferRepository.insert(transferKey, request.sourceCaseKey(), request.targetCaseKey(),
                request.orderVersion(), request.sourceCustodianId(), request.targetCustodianId(),
                evidenceKeys.size(), nowUtc);
        for (Evidence evidence : locked) {
            int sealVersion = inspectionRepository.countByEvidenceKey(evidence.evidenceKey()) + 1;
            int handoverCount = transferRepository.countByEvidenceKey(evidence.evidenceKey());
            int loanCount = loanRepository.countByEvidenceKey(evidence.evidenceKey());
            itemRepository.insert(transferKey, evidence.evidenceKey(),
                    request.sourceCaseKey(), request.targetCaseKey(),
                    request.sourceCustodianId(), request.targetCustodianId(),
                    evidence.sealNo(), sealVersion, handoverCount, loanCount,
                    request.orderVersion(), nowUtc);
            chainRepository.insert(transferKey, evidence.evidenceKey(), request.sourceCaseKey(),
                    CaseTransferChainRepository.DIRECTION_OUT,
                    CaseTransferChainRepository.KIND_TRANSFER,
                    request.sourceCustodianId(), nowUtc);
            chainRepository.insert(transferKey, evidence.evidenceKey(), request.targetCaseKey(),
                    CaseTransferChainRepository.DIRECTION_IN,
                    CaseTransferChainRepository.KIND_TRANSFER,
                    request.targetCustodianId(), nowUtc);
            evidenceRepository.updateCaseCustody(evidence.evidenceKey(), request.targetCaseKey(),
                    request.targetCustodianId(), nowUtc);
        }
        return record(request.commandKey(), OP_CASE_TRANSFER, actorId, requestHash,
                201, detailView(transferKey));
    }

    /**
     * 撤销跨案移交：仅限目标案件尚未发生后续交接/借出/跨案移交；
     * 由双方不同保管人确认，追加反向链，不删除原移交。
     */
    @Transactional
    public StoredResponse revoke(String actorId, String transferKey,
                                 CaseTransferRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        CaseTransfer transfer = caseTransferRepository.findByKey(transferKey)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferKey));
        if (transfer.status() != CaseTransferStatus.COMPLETED) {
            throw ApiException.conflict("跨案移交已撤销，不得重复撤销: " + transferKey);
        }
        if (!actorId.equals(request.sourceConfirmerId()) && !actorId.equals(request.targetConfirmerId())) {
            throw ApiException.forbidden("操作人必须是双方确认保管人之一: " + actorId);
        }
        if (request.sourceConfirmerId().equals(request.targetConfirmerId())) {
            throw ApiException.conflict("双方确认保管人不得相同: " + actorId);
        }
        requireCasePermission(transfer.sourceCaseKey(), request.sourceConfirmerId(), "来源");
        requireCasePermission(transfer.targetCaseKey(), request.targetConfirmerId(), "目标");

        List<CaseTransferItem> items = itemRepository.findByTransferKey(transferKey);
        // 按证物键排序锁定，与移交方向加锁顺序一致，避免死锁。
        List<String> evidenceKeys = items.stream()
                .map(CaseTransferItem::evidenceKey).sorted().toList();
        List<Evidence> locked = new ArrayList<>();
        for (String evidenceKey : evidenceKeys) {
            locked.add(evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey)));
        }
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        for (int i = 0; i < locked.size(); i++) {
            requireRevocable(locked.get(i), items.get(i), transfer);
        }

        LocalDateTime nowUtc = clock.nowUtc();
        if (!caseTransferRepository.revoke(transferKey, nowUtc,
                request.sourceConfirmerId(), request.targetConfirmerId())) {
            throw ApiException.conflict("跨案移交已被并发撤销: " + transferKey);
        }
        for (Evidence evidence : locked) {
            chainRepository.insert(transferKey, evidence.evidenceKey(), transfer.targetCaseKey(),
                    CaseTransferChainRepository.DIRECTION_OUT,
                    CaseTransferChainRepository.KIND_REVOKE,
                    transfer.targetCustodianId(), nowUtc);
            chainRepository.insert(transferKey, evidence.evidenceKey(), transfer.sourceCaseKey(),
                    CaseTransferChainRepository.DIRECTION_IN,
                    CaseTransferChainRepository.KIND_REVOKE,
                    transfer.sourceCustodianId(), nowUtc);
            evidenceRepository.updateCaseCustody(evidence.evidenceKey(), transfer.sourceCaseKey(),
                    transfer.sourceCustodianId(), nowUtc);
        }
        return record(request.commandKey(), OP_CASE_TRANSFER_REVOKE, actorId, requestHash,
                200, detailView(transferKey));
    }

    /**
     * 移交明细：主表 + 双案封存快照 + 双案保管链（只读，不改变状态）。
     */
    @Transactional(readOnly = true)
    public CaseTransferDetailView detail(String transferKey) {
        if (caseTransferRepository.findByKey(transferKey).isEmpty()) {
            throw ApiException.notFound("跨案移交不存在: " + transferKey);
        }
        return detailView(transferKey);
    }

    /**
     * 案件维度移交历史（来源或目标涉及该案件，按发生顺序；只读）。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferView> listByCase(String caseKey) {
        return caseTransferRepository.findByCase(caseKey).stream().map(this::toView).toList();
    }

    /**
     * 证物维度跨案保管链（含移交与撤销反向链，按写入顺序；只读）。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferChainEntryView> chainByEvidence(String evidenceKey) {
        if (evidenceRepository.findByKey(evidenceKey).isEmpty()) {
            throw ApiException.notFound("证物不存在: " + evidenceKey);
        }
        return chainRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView).toList();
    }

    /**
     * 案件维度跨案保管链（按写入顺序；只读）。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferChainEntryView> chainByCase(String caseKey) {
        return chainRepository.findByCaseKey(caseKey).stream().map(this::toView).toList();
    }

    /**
     * 案件维度诊断：移交计数与保管人清单（实际值；只读，不改变状态）。
     */
    @Transactional(readOnly = true)
    public CaseTransferDiagnosticsView diagnostics(String caseKey) {
        long completed = 0;
        long revoked = 0;
        for (CaseTransferRepository.StatusCount row
                : caseTransferRepository.countByCaseGroupByStatus(caseKey)) {
            if (row.status() == CaseTransferStatus.COMPLETED) {
                completed = row.count();
            } else if (row.status() == CaseTransferStatus.REVOKED) {
                revoked = row.count();
            }
        }
        long chainEntryCount = chainRepository.findByCaseKey(caseKey).size();
        return new CaseTransferDiagnosticsView(caseKey, completed + revoked, completed, revoked,
                chainEntryCount, caseCustodianRepository.findCustodians(caseKey));
    }

    /**
     * 规范化证物列表：去重校验后按字典序排序。重复键返回 400。
     */
    private List<String> normalizeEvidenceKeys(List<String> evidenceKeys) {
        Set<String> seen = new HashSet<>();
        for (String key : evidenceKeys) {
            if (!seen.add(key)) {
                throw ApiException.badRequest("证物列表存在重复键: " + key);
            }
        }
        return evidenceKeys.stream().sorted().toList();
    }

    /**
     * 校验移交令可用：存在、ACTIVE、案件对匹配、当前 UTC 时刻处于 [validFrom, validUntil)。
     */
    private void requireOrderUsable(String orderVersion, String sourceCaseKey, String targetCaseKey) {
        TransferOrder order = transferOrderRepository.findByVersion(orderVersion)
                .orElseThrow(() -> ApiException.forbidden("移交令版本不存在或已失效: " + orderVersion));
        if (order.status() != TransferOrderStatus.ACTIVE) {
            throw ApiException.forbidden("移交令版本已撤销: " + orderVersion);
        }
        if (!order.sourceCaseKey().equals(sourceCaseKey)
                || !order.targetCaseKey().equals(targetCaseKey)) {
            throw ApiException.forbidden("移交令不适用于该案件对: " + orderVersion
                    + "（令案件对 " + order.sourceCaseKey() + "->" + order.targetCaseKey() + "）");
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (nowUtc.isBefore(order.validFrom()) || !nowUtc.isBefore(order.validUntil())) {
            throw ApiException.forbidden("移交令版本已失效: " + orderVersion
                    + "（有效期 [" + order.validFrom() + ", " + order.validUntil()
                    + ") UTC，当前 " + nowUtc + "）");
        }
    }

    private void requireCasePermission(String caseKey, String custodianId, String side) {
        if (!caseCustodianRepository.exists(caseKey, custodianId)) {
            throw ApiException.forbidden(side + "案件保管人不具备对应案件权限: " + custodianId);
        }
    }

    /**
     * 校验证物可移交：属于来源案件、在库、封签正常、未借出、未处于其他移交中。
     */
    private void requireTransferable(Evidence evidence, String sourceCaseKey) {
        if (!evidence.caseKey().equals(sourceCaseKey)) {
            throw ApiException.unprocessable("证物不属于来源案件: " + evidence.evidenceKey()
                    + "（实际所属 " + evidence.caseKey() + "，要求 " + sourceCaseKey + "）");
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

    /**
     * 校验可撤销：目标案件未发生后续交接/借出/跨案移交，证物仍由目标保管人封存保管。
     */
    private void requireRevocable(Evidence evidence, CaseTransferItem item, CaseTransfer transfer) {
        if (!evidence.caseKey().equals(transfer.targetCaseKey())
                || !evidence.custodianId().equals(transfer.targetCustodianId())
                || evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("目标案件已发生后续交接或状态变化，禁止撤销: "
                    + evidence.evidenceKey());
        }
        int handoverCount = transferRepository.countByEvidenceKey(evidence.evidenceKey());
        if (handoverCount != item.handoverCount()) {
            throw ApiException.conflict("目标案件已发生后续交接，禁止撤销: " + evidence.evidenceKey()
                    + "（移交时 " + item.handoverCount() + " 条，当前 " + handoverCount + " 条）");
        }
        int loanCount = loanRepository.countByEvidenceKey(evidence.evidenceKey());
        if (loanCount != item.loanCount()) {
            throw ApiException.conflict("目标案件已发生后续借出，禁止撤销: " + evidence.evidenceKey()
                    + "（移交时 " + item.loanCount() + " 条，当前 " + loanCount + " 条）");
        }
        if (itemRepository.existsForEvidenceAfter(evidence.evidenceKey(),
                transfer.transferKey(), transfer.createdAt())) {
            throw ApiException.conflict("证物已包含在后续跨案移交中，禁止撤销: " + evidence.evidenceKey());
        }
    }

    private String newTransferKey() {
        return "CT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private CaseTransferDetailView detailView(String transferKey) {
        CaseTransfer transfer = caseTransferRepository.findByKey(transferKey).orElseThrow();
        List<CaseTransferItemView> items = itemRepository.findByTransferKey(transferKey)
                .stream().map(this::toView).toList();
        List<CaseTransferChainEntryView> chain = chainRepository.findByTransferKey(transferKey)
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
        return new TransferOrderView(order.orderVersion(), order.sourceCaseKey(),
                order.targetCaseKey(), order.status(), order.validFrom(), order.validUntil(),
                order.createdBy(), order.createdAt(), order.revokedAt());
    }

    private CaseTransferView toView(CaseTransfer transfer) {
        return new CaseTransferView(transfer.transferKey(), transfer.sourceCaseKey(),
                transfer.targetCaseKey(), transfer.orderVersion(), transfer.sourceCustodianId(),
                transfer.targetCustodianId(), transfer.evidenceCount(), transfer.status(),
                transfer.createdAt(), transfer.revokedAt(), transfer.revokeSourceConfirmer(),
                transfer.revokeTargetConfirmer());
    }

    private CaseTransferItemView toView(CaseTransferItem item) {
        return new CaseTransferItemView(item.transferKey(), item.evidenceKey(),
                item.sourceCaseKey(), item.targetCaseKey(), item.sourceCustodianId(),
                item.targetCustodianId(), item.sealNo(), item.sealVersion(),
                item.handoverCount(), item.loanCount(), item.orderVersion(), item.createdAt());
    }

    private CaseTransferChainEntryView toView(CaseTransferChainEntry entry) {
        return new CaseTransferChainEntryView(entry.transferKey(), entry.evidenceKey(),
                entry.caseKey(), entry.direction(), entry.entryKind(), entry.custodianId(),
                entry.createdAt());
    }

    /**
     * 授权结果视图。
     *
     * @param caseKey     案件键
     * @param custodianId 被授权保管人
     * @param grantedBy   授权操作人
     */
    public record GrantView(String caseKey, String custodianId, String grantedBy) {
    }
}
