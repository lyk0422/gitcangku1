package com.example.starter.evidence.destruction;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.CommandLogRepository;
import com.example.starter.evidence.Evidence;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.LoanRecordRepository;
import com.example.starter.evidence.StoredResponse;
import com.example.starter.evidence.TransferRecordRepository;
import com.example.starter.evidence.destruction.dto.DestructionAgreeRequest;
import com.example.starter.evidence.destruction.dto.DestructionApprovalView;
import com.example.starter.evidence.destruction.dto.DestructionCreateRequest;
import com.example.starter.evidence.destruction.dto.DestructionExecuteRequest;
import com.example.starter.evidence.destruction.dto.DestructionItemError;
import com.example.starter.evidence.destruction.dto.DestructionOrderView;
import com.example.starter.evidence.destruction.dto.DestructionRejectRequest;
import com.example.starter.evidence.destruction.dto.EvidenceFreezeView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 销毁令核心服务。
 *
 * <p>创建：逐件锁定证物行后校验（SEALED / 无未归还借出 / 无待接收交接 / 未在其他未终结销毁令中 /
 * 封条异常须 forceIncludeBroken），任一件不合格整单 422 并按提交原序返回逐件原因，不创建销毁令。
 * 审批：销毁令行锁串行化；两名互不相同且都不同于提交人的审批人各同意一次，第二次同意转 APPROVED；
 * 任一拒绝立即转 REJECTED 终态，原因不可改写，证物冻结关系随状态自动解除。
 * 执行：APPROVED 后由原提交保管人一次提交，事务内重查证物状态与冻结关系，全部通过才置 DESTROYED，
 * 任一件被改动整单 409 回滚。
 *
 * <p>关于“再次入列”：创建新销毁令时证物已在其他未终结销毁令中，属于题面列举的入列资格条件，
 * 按创建契约整单 422 并在逐件原因中返回冻结它的 destructionKey；交接/借出/独立封条核验等
 * 既有写路径命中冻结时一律 409 并返回 destructionKey。
 */
@Service
public class DestructionService {

    static final String OP_DESTRUCTION_CREATE = "DESTRUCTION_CREATE";
    static final String OP_DESTRUCTION_APPROVE = "DESTRUCTION_APPROVE";
    static final String OP_DESTRUCTION_REJECT = "DESTRUCTION_REJECT";
    static final String OP_DESTRUCTION_EXECUTE = "DESTRUCTION_EXECUTE";

    private final DestructionOrderRepository orderRepository;
    private final DestructionOrderItemRepository itemRepository;
    private final DestructionApprovalRepository approvalRepository;
    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final LoanRecordRepository loanRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public DestructionService(DestructionOrderRepository orderRepository,
                              DestructionOrderItemRepository itemRepository,
                              DestructionApprovalRepository approvalRepository,
                              EvidenceRepository evidenceRepository,
                              TransferRecordRepository transferRepository,
                              LoanRecordRepository loanRepository,
                              CommandLogRepository commandLogRepository,
                              ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.approvalRepository = approvalRepository;
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.loanRepository = loanRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建销毁令：逐件校验，任一不合格整单 422、不落库、不占用 commandKey。
     */
    @Transactional
    public StoredResponse create(String actorId, DestructionCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> submitted = request.evidenceKeys();
        // 列表为集合语义：重复键属于请求形态错误（400），不进入逐件 422 校验。
        if (new HashSet<>(submitted).size() != submitted.size()) {
            throw ApiException.badRequest("入列证物不得重复: " + request.destructionKey());
        }
        if (orderRepository.findByKey(request.destructionKey()).isPresent()) {
            throw ApiException.conflict("销毁令已存在: " + request.destructionKey());
        }
        // 按证物主键升序加锁，避免与其他创建/执行事务交叉时死锁。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdateOrdered(submitted);
        // 持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (orderRepository.findByKey(request.destructionKey()).isPresent()) {
            throw ApiException.conflict("销毁令已存在: " + request.destructionKey());
        }

        Map<String, Evidence> byKey = new LinkedHashMap<>();
        for (Evidence evidence : locked) {
            byKey.put(evidence.evidenceKey(), evidence);
        }
        // 冻结属于硬冲突：已被 PENDING/APPROVED 销毁令入列的证物再次入列一律 409 并返回冻结它的销毁令。
        for (String evidenceKey : submitted) {
            Optional<String> freezeKey = itemRepository.findActiveFreezeKey(evidenceKey);
            if (freezeKey.isPresent()) {
                throw ApiException.conflict("证物已被销毁令冻结，不可再次入列: " + evidenceKey
                        + "，destructionKey=" + freezeKey.get());
            }
        }
        List<DestructionItemError> errors = new ArrayList<>();
        for (String evidenceKey : submitted) {
            String reason = validateItem(actorId, evidenceKey, request.forceIncludeBroken(), byKey);
            if (reason != null) {
                errors.add(new DestructionItemError(evidenceKey, reason));
            }
        }
        if (!errors.isEmpty()) {
            throw new DestructionValidationException("入列证物校验失败，销毁令未创建", errors);
        }

        LocalDateTime now = LocalDateTime.now();
        orderRepository.insert(request.destructionKey(), actorId, request.legalBasis(),
                request.destructionMethod(), request.forceIncludeBroken(), now);
        itemRepository.insert(request.destructionKey(), submitted);
        return record(request.commandKey(), OP_DESTRUCTION_CREATE, actorId, requestHash,
                201, getOrder(request.destructionKey()));
    }

    /**
     * 审批同意：审批人须不同于提交人且此前未做过决定；第二次有效同意转 APPROVED。
     */
    @Transactional
    public StoredResponse agree(String actorId, String destructionKey,
                                DestructionAgreeRequest request, String requestHash) {
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
        if (order.submitterId().equals(actorId)) {
            throw ApiException.badRequest("提交人不能审批自己的销毁令: " + actorId);
        }
        List<DestructionApproval> approvals = approvalRepository.findByDestructionKey(destructionKey);
        if (approvals.stream().anyMatch(a -> a.approverId().equals(actorId))) {
            throw ApiException.conflict("审批人已做出审批决定，不可重复审批: " + actorId);
        }
        approvalRepository.insert(destructionKey, actorId, ApprovalDecision.AGREED,
                request.reason(), LocalDateTime.now());
        long agreedCount = approvals.stream().filter(a -> a.decision() == ApprovalDecision.AGREED).count()
                + 1;
        if (agreedCount >= 2) {
            LocalDateTime now = LocalDateTime.now();
            if (!orderRepository.markApproved(order.id(), now, now)) {
                throw ApiException.conflict("销毁令状态已被并发改变: " + destructionKey);
            }
        }
        return record(request.commandKey(), OP_DESTRUCTION_APPROVE, actorId, requestHash,
                200, getOrder(destructionKey));
    }

    /**
     * 审批拒绝：任一合格审批人拒绝立即转 REJECTED 终态；拒绝原因一次写入不可改写，证物自动恢复可用。
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
        if (order.submitterId().equals(actorId)) {
            throw ApiException.badRequest("提交人不能审批自己的销毁令: " + actorId);
        }
        List<DestructionApproval> approvals = approvalRepository.findByDestructionKey(destructionKey);
        if (approvals.stream().anyMatch(a -> a.approverId().equals(actorId))) {
            throw ApiException.conflict("审批人已做出审批决定，不可重复审批: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        approvalRepository.insert(destructionKey, actorId, ApprovalDecision.REJECTED,
                request.reason(), now);
        if (!orderRepository.markRejected(order.id(), request.reason(), actorId, now, now)) {
            throw ApiException.conflict("销毁令状态已被并发改变: " + destructionKey);
        }
        return record(request.commandKey(), OP_DESTRUCTION_REJECT, actorId, requestHash,
                200, getOrder(destructionKey));
    }

    /**
     * 执行销毁：仅 APPROVED 后由原提交保管人一次提交；事务内重查全部证物与冻结关系，
     * 任一件被改动整单 409，证物与销毁令状态保持原样。成功后证物 DESTROYED、保管链封存。
     */
    @Transactional
    public StoredResponse execute(String actorId, String destructionKey,
                                  DestructionExecuteRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DestructionOrder order = lockOrder(destructionKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!order.submitterId().equals(actorId)) {
            throw ApiException.conflict("仅销毁令提交保管人可执行销毁: " + actorId);
        }
        if (order.status() == DestructionStatus.PENDING) {
            throw ApiException.conflict("销毁令尚未经两名审批人批准: " + destructionKey);
        }
        if (order.status() != DestructionStatus.APPROVED) {
            throw ApiException.conflict("销毁令已终结，不可执行: " + destructionKey);
        }

        List<String> itemKeys = itemRepository.findEvidenceKeys(destructionKey);
        if (itemKeys.isEmpty()) {
            // 销毁令入列关系缺失属于冻结关系被改动：整单中止，不允许零证物推进到 DESTROYED。
            throw ApiException.conflict("销毁令入列证物关系缺失，销毁中止: " + destructionKey);
        }
        List<Evidence> locked = evidenceRepository.findByKeysForUpdateOrdered(itemKeys);
        Map<String, Evidence> byKey = new LinkedHashMap<>();
        for (Evidence evidence : locked) {
            byKey.put(evidence.evidenceKey(), evidence);
        }
        // 重查阶段：任何一件不满足创建时条件或冻结关系，整单失败回滚。
        for (String evidenceKey : itemKeys) {
            Evidence evidence = byKey.get(evidenceKey);
            if (evidence == null) {
                throw ApiException.conflict("证物已不存在，销毁中止: " + evidenceKey);
            }
            if (!evidence.custodianId().equals(order.submitterId())) {
                throw ApiException.conflict("证物保管人已变更，销毁中止: " + evidenceKey);
            }
            boolean sealOk = evidence.status() == EvidenceStatus.SEALED
                    || (order.forceIncludeBroken() && evidence.status() == EvidenceStatus.SEAL_BROKEN);
            if (!sealOk) {
                throw ApiException.conflict("证物状态已改变，销毁中止: " + evidenceKey);
            }
            if (transferRepository.findPendingByEvidenceKey(evidenceKey).isPresent()
                    || loanRepository.findActiveByEvidenceKey(evidenceKey).isPresent()) {
                throw ApiException.conflict("证物存在交接或借出变动，销毁中止: " + evidenceKey);
            }
            String freezeKey = itemRepository.findActiveFreezeKey(evidenceKey)
                    .orElseThrow(() -> ApiException.conflict(
                            "证物冻结关系已改变，销毁中止: " + evidenceKey));
            if (!freezeKey.equals(destructionKey)) {
                throw ApiException.conflict(
                        "证物已被其他销毁令冻结，销毁中止: " + evidenceKey + "，destructionKey=" + freezeKey);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        if (!orderRepository.markDestroyed(order.id(), now, now)) {
            throw ApiException.conflict("销毁令已被并发执行: " + destructionKey);
        }
        for (String evidenceKey : itemKeys) {
            evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.DESTROYED, now);
        }
        return record(request.commandKey(), OP_DESTRUCTION_EXECUTE, actorId, requestHash,
                200, getOrder(destructionKey));
    }

    /**
     * 销毁令明细：本体 + 入列证物（提交原序）+ 全部审批记录（提交顺序）。
     */
    @Transactional(readOnly = true)
    public DestructionOrderView getOrder(String destructionKey) {
        DestructionOrder order = orderRepository.findByKey(destructionKey)
                .orElseThrow(() -> ApiException.notFound("销毁令不存在: " + destructionKey));
        return toView(order);
    }

    /**
     * 待审清单：全部 PENDING 销毁令，按创建顺序。
     */
    @Transactional(readOnly = true)
    public List<DestructionOrderView> listPending() {
        return orderRepository.findPending().stream().map(this::toView).toList();
    }

    /**
     * 证物冻结状态查询：返回是否被 PENDING/APPROVED 销毁令冻结及冻结它的 destructionKey。
     */
    @Transactional(readOnly = true)
    public EvidenceFreezeView freezeStatus(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        Optional<String> freezeKey = itemRepository.findActiveFreezeKey(evidenceKey);
        return new EvidenceFreezeView(evidenceKey, freezeKey.isPresent(), freezeKey.orElse(null),
                evidence.status().name());
    }

    private String validateItem(String actorId, String evidenceKey, boolean forceIncludeBroken,
                                Map<String, Evidence> locked) {
        Evidence evidence = locked.get(evidenceKey);
        if (evidence == null) {
            return "证物不存在";
        }
        if (!evidence.custodianId().equals(actorId)) {
            return "提交人不是该证物当前保管人";
        }
        // 注：已被其他未终结销毁令冻结属于硬冲突，由创建主流程提前以 409 拒绝（见 create）。
        if (transferRepository.findPendingByEvidenceKey(evidenceKey).isPresent()
                || evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            return "证物存在待接收交接";
        }
        if (loanRepository.findActiveByEvidenceKey(evidenceKey).isPresent()
                || evidence.status() == EvidenceStatus.BORROWED) {
            return "证物存在未归还借出";
        }
        if (evidence.status() == EvidenceStatus.DESTROYED) {
            return "证物已销毁";
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN && !forceIncludeBroken) {
            return "封条异常证物须显式声明 forceIncludeBroken 才可入列";
        }
        if (evidence.status() != EvidenceStatus.SEALED && evidence.status() != EvidenceStatus.SEAL_BROKEN) {
            return "证物不处于可销毁状态: " + evidence.status().name();
        }
        return null;
    }

    private DestructionOrder lockOrder(String destructionKey) {
        return orderRepository.findByKeyForUpdate(destructionKey)
                .orElseThrow(() -> ApiException.notFound("销毁令不存在: " + destructionKey));
    }

    private void requirePending(DestructionOrder order) {
        if (order.status() != DestructionStatus.PENDING) {
            throw ApiException.conflict("销毁令不处于待审批状态: "
                    + order.destructionKey() + "，当前状态=" + order.status().name());
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
        List<String> evidenceKeys = itemRepository.findEvidenceKeys(order.destructionKey());
        List<DestructionApprovalView> approvals = approvalRepository
                .findByDestructionKey(order.destructionKey()).stream()
                .map(a -> new DestructionApprovalView(a.approverId(), a.decision(), a.reason(),
                        a.createdAt()))
                .toList();
        return new DestructionOrderView(
                order.destructionKey(),
                order.submitterId(),
                order.legalBasis(),
                order.destructionMethod(),
                order.forceIncludeBroken(),
                order.status(),
                evidenceKeys,
                approvals,
                order.rejectReason(),
                order.rejectedBy(),
                order.rejectedAt(),
                order.approvedAt(),
                order.destroyedAt(),
                order.createdAt(),
                order.updatedAt());
    }
}
