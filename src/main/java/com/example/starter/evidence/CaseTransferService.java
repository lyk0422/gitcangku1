package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferDiagView;
import com.example.starter.evidence.dto.CaseTransferItemView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.CustodyCaseLinkView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 证物跨案移交核心服务。
 * 一次请求包含规范排序的多个证物：先在事务内按证物键升序锁定全部证物行，
 * 校验完整最终状态后，在同一事务写入来源移出链、目标移入链、双案封存快照并原子切换归属。
 * 任一条不满足则整批失败，不写入任何保管链。
 *
 * <p>时间统一使用 UTC；令版本有效期按左闭右开 [validFrom, validTo) 解释。
 */
@Service
public class CaseTransferService {

    static final String OP_CASE_TRANSFER = "CASE_TRANSFER";
    static final String OP_CASE_TRANSFER_REVOKE = "CASE_TRANSFER_REVOKE";

    private final EvidenceRepository evidenceRepository;
    private final CaseTransferRepository caseTransferRepository;
    private final CustodyCaseLinkRepository linkRepository;
    private final TransferOrderRepository orderRepository;
    private final CaseCustodianRepository caseCustodianRepository;
    private final TransferRecordRepository transferRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public CaseTransferService(EvidenceRepository evidenceRepository,
                               CaseTransferRepository caseTransferRepository,
                               CustodyCaseLinkRepository linkRepository,
                               TransferOrderRepository orderRepository,
                               CaseCustodianRepository caseCustodianRepository,
                               TransferRecordRepository transferRepository,
                               CommandLogRepository commandLogRepository,
                               ObjectMapper objectMapper,
                               EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.caseTransferRepository = caseTransferRepository;
        this.linkRepository = linkRepository;
        this.orderRepository = orderRepository;
        this.caseCustodianRepository = caseCustodianRepository;
        this.transferRepository = transferRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 跨案移交：整批证物在同一事务内校验并双案一致封存。
     * 403：令版本失效/保管人相同/无权；422：证物状态或归属不满足；整批失败不留任何链。
     */
    @Transactional
    public StoredResponse caseTransfer(String actorId, CaseTransferRequest request,
                                       String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceCaseKey().equals(request.targetCaseKey())) {
            throw ApiException.badRequest("来源案件与目标案件不得相同");
        }
        if (new HashSet<>(request.evidenceKeys()).size() != request.evidenceKeys().size()) {
            throw ApiException.badRequest("证物列表存在重复键");
        }
        LocalDateTime nowUtc = clock.nowUtc();
        requireOrderValid(request.orderVersion(), nowUtc);
        if (request.sourceCustodianId().equals(request.targetCustodianId())) {
            throw ApiException.forbidden("双方案件保管人不得相同");
        }
        if (!request.sourceCustodianId().equals(actorId)) {
            throw ApiException.forbidden("提交人必须是来源案件保管人");
        }
        requireCustodianPermission(request.sourceCaseKey(), request.sourceCustodianId(), "来源");
        requireCustodianPermission(request.targetCaseKey(), request.targetCustodianId(), "目标");

        // 按证物键升序锁定，保证多证物批次固定加锁顺序；并发批次在此串行裁决。
        List<Evidence> evidenceList = evidenceRepository.findByKeysForUpdate(request.evidenceKeys());
        // 并发下本事务可能在行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        validateBatchEvidence(evidenceList, request, request.evidenceKeys().size());

        String transferId = "CT-" + UUID.randomUUID().toString().replace("-", "");
        int count = request.evidenceKeys().size();
        caseTransferRepository.insert(transferId, request.sourceCaseKey(), request.targetCaseKey(),
                request.orderVersion(), request.sourceCustodianId(), request.targetCustodianId(),
                count, nowUtc);
        for (Evidence evidence : evidenceList) {
            long transferSeq = transferRepository.findMaxIdByEvidenceKey(evidence.evidenceKey());
            caseTransferRepository.insertItem(transferId, evidence.evidenceKey(),
                    request.sourceCaseKey(), request.targetCaseKey(), evidence.location(),
                    evidence.sealVersion(), request.orderVersion(), transferSeq, nowUtc);
            linkRepository.insert(request.sourceCaseKey(), evidence.evidenceKey(), transferId,
                    ChainDirection.OUT, request.orderVersion(), request.sourceCustodianId(), nowUtc);
            linkRepository.insert(request.targetCaseKey(), evidence.evidenceKey(), transferId,
                    ChainDirection.IN, request.orderVersion(), request.targetCustodianId(), nowUtc);
            evidenceRepository.updateCaseCustody(evidence.evidenceKey(), request.targetCaseKey(),
                    request.targetCustodianId(), request.targetLocation(), nowUtc);
        }
        CaseTransferView view = loadView(transferId);
        return record(request.commandKey(), OP_CASE_TRANSFER, actorId, requestHash, 200, view);
    }

    /**
     * 撤销跨案移交：仅限目标案件尚未发生后续交接；双方不同保管人确认，追加反向链，不删除原移交。
     */
    @Transactional
    public StoredResponse revokeCaseTransfer(String actorId, String transferId,
                                             CaseTransferRevokeRequest request,
                                             String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        requireOrderValid(request.orderVersion(), nowUtc);
        if (request.sourceCustodianId().equals(request.targetCustodianId())) {
            throw ApiException.forbidden("撤销确认的双方保管人不得相同");
        }
        if (!request.sourceCustodianId().equals(actorId)
                && !request.targetCustodianId().equals(actorId)) {
            throw ApiException.forbidden("提交人必须是撤销确认的双方保管人之一");
        }

        CaseTransfer batch = caseTransferRepository.findByTransferIdForUpdate(transferId)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferId));
        // 并发下本事务可能在批次行锁上等待；持锁后复查幂等日志。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (batch.status() != CaseTransferStatus.COMPLETED) {
            throw ApiException.conflict("跨案移交已撤销，不能重复撤销: " + transferId);
        }
        if (!request.orderVersion().equals(batch.orderVersion())) {
            throw ApiException.conflict("撤销令版本与原移交令版本不一致");
        }
        requireCustodianPermission(batch.sourceCaseKey(), request.sourceCustodianId(), "来源");
        requireCustodianPermission(batch.targetCaseKey(), request.targetCustodianId(), "目标");

        List<CaseTransferItem> items = caseTransferRepository.findItemsByTransferId(transferId);
        List<String> evidenceKeys = items.stream().map(CaseTransferItem::evidenceKey).toList();
        List<Evidence> evidenceList = evidenceRepository.findByKeysForUpdate(evidenceKeys);
        validateRevokeEvidence(evidenceList, items, batch, evidenceKeys.size());

        if (!caseTransferRepository.markRevoked(transferId, request.sourceCustodianId(),
                request.targetCustodianId(), nowUtc)) {
            throw ApiException.conflict("跨案移交已被并发撤销: " + transferId);
        }
        for (CaseTransferItem item : items) {
            linkRepository.insert(batch.targetCaseKey(), item.evidenceKey(), transferId,
                    ChainDirection.REVOKE_OUT, request.orderVersion(),
                    request.targetCustodianId(), nowUtc);
            linkRepository.insert(batch.sourceCaseKey(), item.evidenceKey(), transferId,
                    ChainDirection.REVOKE_IN, request.orderVersion(),
                    request.sourceCustodianId(), nowUtc);
            evidenceRepository.updateCaseCustody(item.evidenceKey(), batch.sourceCaseKey(),
                    request.sourceCustodianId(), null, nowUtc);
        }
        CaseTransferView view = loadView(transferId);
        return record(request.commandKey(), OP_CASE_TRANSFER_REVOKE, actorId, requestHash,
                200, view);
    }

    /**
     * 明细查询：批次视图含双案封存快照明细。只读，不改变状态。
     */
    @Transactional(readOnly = true)
    public CaseTransferView getTransfer(String transferId) {
        caseTransferRepository.findByTransferId(transferId)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferId));
        return loadView(transferId);
    }

    /**
     * 历史查询：案件相关（作为来源或目标）的全部跨案移交，按发生顺序。
     */
    @Transactional(readOnly = true)
    public List<CaseTransferView> listByCase(String caseKey) {
        return caseTransferRepository.findByCaseKey(caseKey).stream()
                .map(batch -> toView(batch, caseTransferRepository.findItemsByTransferId(
                        batch.transferId())))
                .toList();
    }

    /**
     * 案件跨案链事件历史（移出/移入/撤销反向链），按发生顺序。只读。
     */
    @Transactional(readOnly = true)
    public List<CustodyCaseLinkView> listCaseLinks(String caseKey) {
        return linkRepository.findByCaseKey(caseKey).stream().map(this::toLinkView).toList();
    }

    /**
     * 证物跨案链事件历史，按发生顺序。只读。
     */
    @Transactional(readOnly = true)
    public List<CustodyCaseLinkView> listEvidenceCaseLinks(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return linkRepository.findByEvidenceKey(evidenceKey).stream().map(this::toLinkView).toList();
    }

    /**
     * 诊断查询：批次实际数量、令版本当前是否有效与剩余有效秒数、双案链事件实际条数。只读。
     */
    @Transactional(readOnly = true)
    public CaseTransferDiagView diagnose(String transferId) {
        CaseTransfer batch = caseTransferRepository.findByTransferId(transferId)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferId));
        LocalDateTime nowUtc = clock.nowUtc();
        boolean validNow = false;
        Long remainingSeconds = null;
        Optional<TransferOrder> order = orderRepository.findByVersion(batch.orderVersion());
        if (order.isPresent()) {
            TransferOrder o = order.get();
            validNow = !nowUtc.isBefore(o.validFrom()) && nowUtc.isBefore(o.validTo());
            if (validNow) {
                remainingSeconds = Duration.between(nowUtc, o.validTo()).toSeconds();
            }
        }
        int sourceCount = linkRepository.countByTransferAndCase(transferId, batch.sourceCaseKey());
        int targetCount = linkRepository.countByTransferAndCase(transferId, batch.targetCaseKey());
        return new CaseTransferDiagView(transferId, batch.status().name(), batch.evidenceCount(),
                batch.orderVersion(), validNow, remainingSeconds, sourceCount, targetCount, nowUtc);
    }

    /**
     * 登记/续期移交令版本（合成数据管理入口）。有效期 UTC 左闭右开。
     */
    @Transactional
    public void registerOrder(String orderVersion, LocalDateTime validFrom, LocalDateTime validTo) {
        if (!validTo.isAfter(validFrom)) {
            throw ApiException.badRequest("令版本有效期终点必须晚于起点");
        }
        orderRepository.upsert(orderVersion, validFrom, validTo, clock.nowUtc());
    }

    /**
     * 授权案件保管人（合成数据管理入口）。
     */
    @Transactional
    public void grantCustodian(String caseKey, String custodianId) {
        caseCustodianRepository.grant(caseKey, custodianId, clock.nowUtc());
    }

    private void validateBatchEvidence(List<Evidence> evidenceList, CaseTransferRequest request,
                                       int requestedCount) {
        if (evidenceList.size() != requestedCount) {
            Set<String> found = new HashSet<>();
            evidenceList.forEach(e -> found.add(e.evidenceKey()));
            String missing = request.evidenceKeys().stream().filter(k -> !found.contains(k))
                    .findFirst().orElse("?");
            throw ApiException.unprocessable("证物不存在，不属于来源案件: " + missing);
        }
        for (Evidence evidence : evidenceList) {
            if (!evidence.caseKey().equals(request.sourceCaseKey())) {
                throw ApiException.unprocessable(
                        "证物不属于来源案件: " + evidence.evidenceKey()
                                + "，实际案件=" + evidence.caseKey()
                                + "，要求案件=" + request.sourceCaseKey());
            }
            if (!evidence.custodianId().equals(request.sourceCustodianId())) {
                throw ApiException.unprocessable(
                        "证物当前保管人与来源保管人不一致: " + evidence.evidenceKey()
                                + "，实际保管人=" + evidence.custodianId()
                                + "，要求保管人=" + request.sourceCustodianId());
            }
            switch (evidence.status()) {
                case BORROWED -> throw ApiException.unprocessable(
                        "证物借出未归还，禁止跨案移交: " + evidence.evidenceKey());
                case SEAL_BROKEN -> throw ApiException.unprocessable(
                        "证物封签异常，禁止跨案移交: " + evidence.evidenceKey());
                case TRANSFER_PENDING -> throw ApiException.unprocessable(
                        "证物已处于其他移交中，禁止跨案移交: " + evidence.evidenceKey());
                case SEALED -> {
                    // 状态符合要求
                }
            }
        }
    }

    private void validateRevokeEvidence(List<Evidence> evidenceList, List<CaseTransferItem> items,
                                        CaseTransfer batch, int requestedCount) {
        if (evidenceList.size() != requestedCount) {
            throw ApiException.unprocessable("移交批次明细与现存证物数量不一致，实际="
                    + evidenceList.size() + "，要求=" + requestedCount);
        }
        Map<String, Long> frozenSeq = new HashMap<>();
        for (CaseTransferItem item : items) {
            frozenSeq.put(item.evidenceKey(), item.transferSeq());
        }
        for (Evidence evidence : evidenceList) {
            if (!evidence.caseKey().equals(batch.targetCaseKey())) {
                // 已被再次跨案移交出目标案件即属于后续交接。
                throw ApiException.conflict(
                        "目标案件已发生后续跨案交接，禁止撤销: " + evidence.evidenceKey());
            }
            if (!evidence.custodianId().equals(batch.targetCustodianId())) {
                throw ApiException.conflict(
                        "目标案件已发生后续交接，保管人已变更，禁止撤销: " + evidence.evidenceKey()
                                + "，实际保管人=" + evidence.custodianId()
                                + "，要求保管人=" + batch.targetCustodianId());
            }
            // 交接链序号增长（含已取消的后续交接发起）即视为目标案件已发生后续交接。
            long currentSeq = transferRepository.findMaxIdByEvidenceKey(evidence.evidenceKey());
            long frozen = frozenSeq.getOrDefault(evidence.evidenceKey(), 0L);
            if (currentSeq != frozen) {
                throw ApiException.conflict(
                        "目标案件已发生后续交接，禁止撤销: " + evidence.evidenceKey()
                                + "，交接链序号实际=" + currentSeq + "，移交时=" + frozen);
            }
            if (evidence.status() == EvidenceStatus.BORROWED) {
                throw ApiException.unprocessable(
                        "证物借出未归还，禁止撤销: " + evidence.evidenceKey());
            }
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable(
                        "证物封签异常，禁止撤销: " + evidence.evidenceKey());
            }
        }
    }

    private void requireOrderValid(String orderVersion, LocalDateTime nowUtc) {
        TransferOrder order = orderRepository.findByVersion(orderVersion)
                .orElseThrow(() -> ApiException.forbidden("移交令版本不存在: " + orderVersion));
        // 左闭右开：[validFrom, validTo)
        if (nowUtc.isBefore(order.validFrom()) || !nowUtc.isBefore(order.validTo())) {
            throw ApiException.forbidden("移交令版本已失效: " + orderVersion
                    + "，当前=" + nowUtc + "，有效期=[" + order.validFrom() + ","
                    + order.validTo() + ")");
        }
    }

    private void requireCustodianPermission(String caseKey, String custodianId, String side) {
        if (!caseCustodianRepository.isActive(caseKey, custodianId)) {
            throw ApiException.forbidden(
                    side + "保管人不具备对应案件权限: 案件=" + caseKey + "，保管人=" + custodianId);
        }
    }

    private CaseTransferView loadView(String transferId) {
        CaseTransfer batch = caseTransferRepository.findByTransferId(transferId).orElseThrow();
        List<CaseTransferItem> items = caseTransferRepository.findItemsByTransferId(transferId);
        return toView(batch, items);
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

    private CaseTransferView toView(CaseTransfer batch, List<CaseTransferItem> items) {
        List<CaseTransferItemView> itemViews = items.stream()
                .map(item -> new CaseTransferItemView(item.evidenceKey(), item.sourceCaseKey(),
                        item.targetCaseKey(), item.fromLocation(), item.sealVersion(),
                        item.orderVersion()))
                .toList();
        return new CaseTransferView(batch.transferId(), batch.sourceCaseKey(),
                batch.targetCaseKey(), batch.orderVersion(), batch.sourceCustodianId(),
                batch.targetCustodianId(), batch.status(), batch.evidenceCount(), itemViews,
                batch.createdAt(), batch.revokedAt(), batch.revokeSourceCustodianId(),
                batch.revokeTargetCustodianId());
    }

    private CustodyCaseLinkView toLinkView(CustodyCaseLink link) {
        return new CustodyCaseLinkView(link.caseKey(), link.evidenceKey(), link.transferId(),
                link.direction(), link.orderVersion(), link.actorId(), link.createdAt());
    }
}
