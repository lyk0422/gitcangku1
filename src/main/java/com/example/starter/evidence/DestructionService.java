package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionApprovalView;
import com.example.starter.evidence.dto.DestructionCreateRequest;
import com.example.starter.evidence.dto.DestructionItemView;
import com.example.starter.evidence.dto.DestructionOrderView;
import com.example.starter.evidence.dto.DestructionRejectRequest;
import com.example.starter.evidence.dto.FreezeStatusView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 证物销毁令核心服务。
 * 创建在单事务内锁定全部入列证物行后逐件校验，任一件不合格整单 422（逐件原因）且不创建销毁令；
 * 双人审批/拒绝/执行均先锁定销毁令行，状态流转全部走条件更新，按事务提交顺序裁决；
 * 执行在事务内重查全部证物状态，任一件被改动整单 409 回滚，销毁令与证物状态保持原样。
 * 所有写操作沿用 command_key 幂等：同键同参重放首次结果，异参 409，失败不占键。
 */
@Service
public class DestructionService {

    static final String OP_DESTRUCTION_CREATE = "DESTRUCTION_CREATE";
    static final String OP_DESTRUCTION_APPROVE = "DESTRUCTION_APPROVE";
    static final String OP_DESTRUCTION_REJECT = "DESTRUCTION_REJECT";
    static final String OP_DESTRUCTION_EXECUTE = "DESTRUCTION_EXECUTE";

    private static final int REQUIRED_APPROVALS = 2;

    private final DestructionOrderRepository orderRepository;
    private final DestructionItemRepository itemRepository;
    private final DestructionApprovalRepository approvalRepository;
    private final EvidenceRepository evidenceRepository;
    private final LoanRecordRepository loanRepository;
    private final TransferRecordRepository transferRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public DestructionService(DestructionOrderRepository orderRepository,
                           DestructionItemRepository itemRepository,
                           DestructionApprovalRepository approvalRepository,
                           EvidenceRepository evidenceRepository,
                           LoanRecordRepository loanRepository,
                           TransferRecordRepository transferRepository,
                           CommandLogRepository commandLogRepository,
                           ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.approvalRepository = approvalRepository;
        this.evidenceRepository = evidenceRepository;
        this.loanRepository = loanRepository;
        this.transferRepository = transferRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 保管人创建 PENDING 销毁令。任一件不合格整单 422 并逐件返回原因，不创建销毁令；
     * 任一件已被其他未终结销毁令冻结则整单 409 并返回冻结它的 destructionKey。
     */
    @Transactional
    public StoredResponse create(String actorId, DestructionCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (orderRepository.findByKey(request.destructionKey()).isPresent()) {
            // 并发同键同参重放：另一事务已连同幂等日志一起提交，先重放首次结果而非直接 409。
            StoredResponse concurrent = checkReplay(request.commandKey(), requestHash)
                    .orElseThrow(() -> ApiException.conflict(
                            "销毁令已存在: " + request.destructionKey()));
            return concurrent;
        }

        // 去重：同一证物在同一单内重复入列视为逐件校验失败（422）。
        LinkedHashSet<String> uniqueKeys = new LinkedHashSet<>(request.evidenceKeys());
        List<String> orderedKeys = new ArrayList<>(uniqueKeys);

        // 统一按证物键排序加锁，避免多证物事务交叉等待造成死锁。
        List<String> lockedKeys = orderedKeys.stream().sorted().toList();
        List<Evidence> lockedEvidence = new ArrayList<>();
        for (String evidenceKey : lockedKeys) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey).orElse(null);
            if (evidence != null) {
                lockedEvidence.add(evidence);
            }
        }
        // 并发下本事务可能在证物行锁上等待；全部持锁后复查幂等日志与销毁令，
        // 重放先提交事务的首次结果，避免把同键同参并发创建误判为冻结冲突。
        Optional<StoredResponse> afterLock = checkReplay(request.commandKey(), requestHash);
        if (afterLock.isPresent()) {
            return afterLock.get();
        }
        if (orderRepository.findByKey(request.destructionKey()).isPresent()) {
            throw ApiException.conflict("销毁令已存在: " + request.destructionKey());
        }
        List<DestructionValidationException.ItemReason> reasons = new ArrayList<>();
        for (String evidenceKey : lockedKeys) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey).orElse(null);
            if (evidence == null) {
                reasons.add(new DestructionValidationException.ItemReason(
                        evidenceKey, "EVIDENCE_NOT_FOUND", "证物不存在: " + evidenceKey));
            }
        }

        // 冻结优先：任一证物已在其他未终结销毁令中，整单 409 并指明冻结它的销毁令。
        for (Evidence evidence : lockedEvidence) {
            Optional<String> openOrder = itemRepository.findOpenOrderKeyForEvidence(evidence.evidenceKey());
            if (openOrder.isPresent()) {
                throw ApiException.conflict("证物已被未终结销毁令冻结: " + evidence.evidenceKey()
                        + "，冻结销毁令: " + openOrder.get());
            }
        }

        if (uniqueKeys.size() != request.evidenceKeys().size()) {
            for (String duplicated : duplicatedKeys(request.evidenceKeys())) {
                reasons.add(new DestructionValidationException.ItemReason(
                        duplicated, "DUPLICATE_IN_ORDER", "证物在本销毁令中重复入列: " + duplicated));
            }
        }

        for (Evidence evidence : lockedEvidence) {
            validateForDestruction(actorId, evidence, request.forceIncludeBroken(), reasons);
        }

        if (!reasons.isEmpty()) {
            throw new DestructionValidationException(reasons);
        }

        LocalDateTime now = LocalDateTime.now();
        orderRepository.insert(request.destructionKey(), actorId, request.legalBasis(),
                request.destructionMethod(), request.forceIncludeBroken(), now);
        for (String evidenceKey : orderedKeys) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey).orElseThrow();
            boolean forcedBroken = evidence.status() == EvidenceStatus.SEAL_BROKEN;
            itemRepository.insert(request.destructionKey(), evidenceKey, evidence.status(),
                    forcedBroken, now);
        }

        DestructionOrder created = orderRepository.findByKey(request.destructionKey()).orElseThrow();
        return record(request.commandKey(), OP_DESTRUCTION_CREATE, actorId, requestHash,
                201, toView(created));
    }

    /**
     * 审批人同意：两名互不相同且都不同于提交人的审批人各自同意一次，第二次同意后转 APPROVED。
     */
    @Transactional
    public StoredResponse approve(String actorId, String destructionKey,
                                CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DestructionOrder order = lockOrder(destructionKey);
        // 并发下可能在销毁令行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requirePending(order);
        requireDistinctApprover(order, actorId);
        if (approvalRepository.find(destructionKey, actorId).isPresent()) {
            throw ApiException.conflict("审批人已同意过本销毁令: " + actorId);
        }

        LocalDateTime now = LocalDateTime.now();
        approvalRepository.insert(destructionKey, actorId, now);
        int approvals = approvalRepository.countByDestructionKey(destructionKey);
        if (approvals >= REQUIRED_APPROVALS
                && !orderRepository.compareAndUpdateStatus(
                        destructionKey, DestructionStatus.PENDING, DestructionStatus.APPROVED, now)) {
            throw ApiException.conflict("销毁令状态已被并发改变: " + destructionKey);
        }

        DestructionOrder updated = orderRepository.findByKey(destructionKey).orElseThrow();
        return record(request.commandKey(), OP_DESTRUCTION_APPROVE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 审批人拒绝：立即转 REJECTED 终态，拒绝原因不可改写，入列证物随即恢复可用。
     */
    @Transactional
    public StoredResponse reject(String actorId, String destructionKey,
                                DestructionRejectRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DestructionOrder order = lockOrder(destructionKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requirePending(order);
        requireDistinctApprover(order, actorId);

        LocalDateTime now = LocalDateTime.now();
        if (!orderRepository.reject(destructionKey, request.reason(), now)) {
            throw ApiException.conflict("销毁令状态已被并发改变: " + destructionKey);
        }

        // 证物在创建期间未改状态，冻结关系来自销毁令；REJECTED 后冻结查询自动排除，证物即刻恢复可用。
        DestructionOrder rejected = orderRepository.findByKey(destructionKey).orElseThrow();
        return record(request.commandKey(), OP_DESTRUCTION_REJECT, actorId, requestHash,
                200, toView(rejected));
    }

    /**
     * 保管人执行：仅 APPROVED 后一次提交；事务内锁定并重查全部证物状态，
     * 任一件被改动整单 409 回滚；条件更新保证同一销毁令最多执行成功一次。
     */
    @Transactional
    public StoredResponse execute(String actorId, String destructionKey,
                                CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DestructionOrder order = lockOrder(destructionKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!order.submittedBy().equals(actorId)) {
            throw ApiException.conflict("仅销毁令提交保管人可执行销毁: " + actorId);
        }
        if (order.status() != DestructionStatus.APPROVED) {
            throw ApiException.conflict("销毁令当前状态不允许执行: " + order.status());
        }

        List<DestructionOrderItem> items = itemRepository.findByDestructionKey(destructionKey);
        // 锁定全部入列证物（排序避免死锁），事务内重查状态与冻结归属。
        for (String evidenceKey : items.stream().map(DestructionOrderItem::evidenceKey).sorted().toList()) {
            evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> ApiException.conflict("入列证物已不存在: " + evidenceKey));
        }
        for (DestructionOrderItem item : items) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(item.evidenceKey()).orElseThrow();
            if (evidence.status() != item.includedStatus()) {
                throw ApiException.conflict(
                        "入列证物状态已被改动，整单中止: " + item.evidenceKey()
                                + "，入列时 " + item.includedStatus() + "，当前 " + evidence.status());
            }
            if (evidence.status() == EvidenceStatus.DESTROYED) {
                throw ApiException.conflict("入列证物已被销毁: " + item.evidenceKey());
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // 条件更新抢占执行权：APPROVED→DESTROYED 仅一次，并发执行败者得到 409 并回滚。
        if (!orderRepository.markExecuted(destructionKey, now)) {
            throw ApiException.conflict("销毁令已被并发执行或状态已改变: " + destructionKey);
        }
        for (DestructionOrderItem item : items) {
            evidenceRepository.updateStatus(item.evidenceKey(), EvidenceStatus.DESTROYED, now);
        }

        DestructionOrder executed = orderRepository.findByKey(destructionKey).orElseThrow();
        return record(request.commandKey(), OP_DESTRUCTION_EXECUTE, actorId, requestHash,
                200, toView(executed));
    }

    /**
     * 查询销毁令明细（含入列证物当前状态与同意记录）。
     */
    @Transactional(readOnly = true)
    public DestructionOrderView detail(String destructionKey) {
        DestructionOrder order = orderRepository.findByKey(destructionKey)
                .orElseThrow(() -> ApiException.notFound("销毁令不存在: " + destructionKey));
        return toView(order);
    }

    /**
     * 待审清单：全部 PENDING 销毁令（按创建顺序）。
     */
    @Transactional(readOnly = true)
    public List<DestructionOrderView> pendingList() {
        return orderRepository.findPending().stream().map(this::toView).toList();
    }

    /**
     * 查询证物冻结状态：被 PENDING/APPROVED 销毁令冻结时返回冻结它的 destructionKey。
     */
    @Transactional(readOnly = true)
    public FreezeStatusView freezeStatus(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        Optional<String> openOrder = itemRepository.findOpenOrderKeyForEvidence(evidenceKey);
        return new FreezeStatusView(evidenceKey, openOrder.isPresent(), openOrder.orElse(null));
    }

    private void validateForDestruction(String actorId, Evidence evidence, boolean forceIncludeBroken,
                                       List<DestructionValidationException.ItemReason> reasons) {
        String key = evidence.evidenceKey();
        if (!evidence.custodianId().equals(actorId)) {
            reasons.add(new DestructionValidationException.ItemReason(
                    key, "NOT_CUSTODIAN", "操作人不是该证物当前保管人: " + evidence.custodianId()));
        }
        switch (evidence.status()) {
            case SEALED -> {
                // 合格状态，继续检查借出与交接。
            }
            case SEAL_BROKEN -> {
                if (!forceIncludeBroken) {
                    reasons.add(new DestructionValidationException.ItemReason(
                            key, "SEAL_BROKEN",
                            "封条异常证物须显式声明 forceIncludeBroken 才可入列: " + key));
                }
            }
            case BORROWED -> reasons.add(new DestructionValidationException.ItemReason(
                    key, "ACTIVE_LOAN", "证物存在未归还借出: " + key));
            case TRANSFER_PENDING -> reasons.add(new DestructionValidationException.ItemReason(
                    key, "PENDING_TRANSFER", "证物存在待接收交接: " + key));
            case DESTROYED -> reasons.add(new DestructionValidationException.ItemReason(
                    key, "ALREADY_DESTROYED", "证物已销毁，不得再次入列: " + key));
        }
        if (evidence.status() != EvidenceStatus.BORROWED
                && loanRepository.findActiveByEvidenceKey(key).isPresent()) {
            reasons.add(new DestructionValidationException.ItemReason(
                    key, "ACTIVE_LOAN", "证物存在未归还借出: " + key));
        }
        if (evidence.status() != EvidenceStatus.TRANSFER_PENDING
                && transferRepository.findPendingByEvidenceKey(key).isPresent()) {
            reasons.add(new DestructionValidationException.ItemReason(
                    key, "PENDING_TRANSFER", "证物存在待接收交接: " + key));
        }
    }

    private List<String> duplicatedKeys(List<String> keys) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<String> duplicates = new LinkedHashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        return List.copyOf(duplicates);
    }

    private DestructionOrder lockOrder(String destructionKey) {
        return orderRepository.findByKeyForUpdate(destructionKey)
                .orElseThrow(() -> ApiException.notFound("销毁令不存在: " + destructionKey));
    }

    private void requirePending(DestructionOrder order) {
        if (order.status() != DestructionStatus.PENDING) {
            throw ApiException.conflict("销毁令当前状态不允许审批或拒绝: " + order.status());
        }
    }

    private void requireDistinctApprover(DestructionOrder order, String actorId) {
        if (order.submittedBy().equals(actorId)) {
            throw ApiException.conflict("提交人不能作为销毁令审批人: " + actorId);
        }
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

    private DestructionOrderView toView(DestructionOrder order) {
        List<DestructionApprovalView> approvals = approvalRepository
                .findByDestructionKey(order.destructionKey()).stream()
                .map(a -> new DestructionApprovalView(a.approverId(), a.createdAt()))
                .toList();
        List<DestructionItemView> items = itemRepository.findByDestructionKey(order.destructionKey())
                .stream()
                .map(item -> {
                    EvidenceStatus currentStatus = evidenceRepository
                            .findByKey(item.evidenceKey())
                            .map(Evidence::status)
                            .orElse(item.includedStatus());
                    return new DestructionItemView(item.evidenceKey(), item.includedStatus(),
                            currentStatus, item.forcedBroken(), item.createdAt());
                })
                .toList();
        return new DestructionOrderView(
                order.destructionKey(),
                order.submittedBy(),
                order.legalBasis(),
                order.destructionMethod(),
                order.forceIncludeBroken(),
                order.status(),
                approvals,
                items,
                order.rejectReason(),
                order.createdAt(),
                order.decidedAt(),
                order.executedAt());
    }
}
