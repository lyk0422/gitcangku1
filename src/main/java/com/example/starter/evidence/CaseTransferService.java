package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseChainEventView;
import com.example.starter.evidence.dto.CaseHistoryView;
import com.example.starter.evidence.dto.CaseSealSnapshotView;
import com.example.starter.evidence.dto.CaseTransferDiagnosisView;
import com.example.starter.evidence.dto.CaseTransferItemView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.GrantStatusView;
import com.example.starter.evidence.dto.OrderStatusView;
import com.example.starter.evidence.dto.TransferItemDiagnosisView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 证物跨案移交核心服务。
 *
 * <p>整批移交在单一事务内完成：先按规范化证物顺序锁定全部证物行，再校验完整最终状态
 * （来源案归属、在库 SEALED、未借出、封签正常、无人员交接/其他跨案移交进行中、
 * 移交令版本在左闭右开生效区间内、双方保管人不同且各持有对应案件授权），
 * 全部通过后才写入：来源案移出链、目标案移入链、同一 transferId 的双案封存快照、
 * 移交主单与明细，并原子切换证物案件归属、保管人与封签版本。</p>
 *
 * <p>任一前置条件失败整批回滚，不写入任何保管链，也不写幂等日志（失败不占键）。
 * 撤销仅在目标案件尚未发生后续交接时，由双方不同保管人确认并追加反向链，不删除原移交。</p>
 */
@Service
public class CaseTransferService {

    static final String OP_CASE_TRANSFER = "CASE_TRANSFER";
    static final String OP_CASE_TRANSFER_REVOKE = "CASE_TRANSFER_REVOKE";

    private final EvidenceRepository evidenceRepository;
    private final CaseTransferRepository caseTransferRepository;
    private final CaseChainEventRepository chainEventRepository;
    private final CaseSealSnapshotRepository snapshotRepository;
    private final TransferOrderRepository orderRepository;
    private final CaseCustodianGrantRepository grantRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public CaseTransferService(EvidenceRepository evidenceRepository,
                               CaseTransferRepository caseTransferRepository,
                               CaseChainEventRepository chainEventRepository,
                               CaseSealSnapshotRepository snapshotRepository,
                               TransferOrderRepository orderRepository,
                               CaseCustodianGrantRepository grantRepository,
                               CommandLogRepository commandLogRepository,
                               ObjectMapper objectMapper,
                               EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.caseTransferRepository = caseTransferRepository;
        this.chainEventRepository = chainEventRepository;
        this.snapshotRepository = snapshotRepository;
        this.orderRepository = orderRepository;
        this.grantRepository = grantRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 执行整批跨案移交。
     */
    @Transactional
    public StoredResponse transfer(String actorId, CaseTransferRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.fromCaseKey().equals(request.toCaseKey())) {
            throw ApiException.unprocessable("来源案件与目标案件不得相同: " + request.fromCaseKey());
        }
        List<String> sortedKeys = normalizeKeys(request.evidenceKeys());

        // 按规范化顺序整批加锁，保证并发移交按相同顺序获取行锁、按提交顺序裁决。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdate(sortedKeys);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = clock.nowUtc();

        Map<String, Evidence> evidenceByKey = new LinkedHashMap<>();
        for (Evidence evidence : locked) {
            evidenceByKey.put(evidence.evidenceKey(), evidence);
        }
        // 先校验完整最终状态：任一证物不满足整批拒绝，不留半成品。
        for (String key : sortedKeys) {
            Evidence evidence = evidenceByKey.get(key);
            if (evidence == null) {
                throw ApiException.unprocessable("证物不存在，整批移交拒绝: " + key);
            }
            validateTransferable(evidence, request);
        }
        TransferOrder order = requireEffectiveOrder(request, now);
        requireDistinctCustodians(request);
        if (!request.fromCustodian().equals(actorId)) {
            throw ApiException.conflict("操作者必须与来源案件保管人一致: " + actorId);
        }
        requireGrant(request.fromCaseKey(), request.fromCustodian(), "来源案件");
        requireGrant(request.toCaseKey(), request.toCustodian(), "目标案件");

        String transferId = UUID.randomUUID().toString().replace("-", "");
        caseTransferRepository.insert(transferId, request.orderNo(), request.orderVersion(),
                request.fromCaseKey(), request.toCaseKey(), request.fromCustodian(),
                request.toCustodian(), now);

        int seq = 0;
        for (String key : sortedKeys) {
            Evidence evidence = evidenceByKey.get(key);
            int sealedVersion = evidence.sealVersion();
            caseTransferRepository.insertItem(transferId, key, seq, evidence.location(),
                    sealedVersion, now);

            // 来源案移出链 + FROM 封存快照（固化原位置、封签版本、令版本）。
            long outEventId = chainEventRepository.insert(transferId, key, request.fromCaseKey(),
                    ChainEventType.CASE_OUT, seq, request.fromCustodian(), evidence.location(),
                    sealedVersion, request.orderVersion(), now);
            snapshotRepository.insert(transferId, request.fromCaseKey(), SnapshotSide.FROM, key,
                    evidence.location(), sealedVersion, request.orderVersion(), outEventId, now);

            // 目标案移入链 + TO 封存快照。
            long inEventId = chainEventRepository.insert(transferId, key, request.toCaseKey(),
                    ChainEventType.CASE_IN, seq, request.toCustodian(), evidence.location(),
                    sealedVersion, request.orderVersion(), now);
            snapshotRepository.insert(transferId, request.toCaseKey(), SnapshotSide.TO, key,
                    evidence.location(), sealedVersion, request.orderVersion(), inEventId, now);

            // 目标案接收后重新封存，封签版本 +1；历史链与快照保留旧版本，不被改写。
            evidenceRepository.switchCase(key, request.toCaseKey(), request.toCustodian(),
                    sealedVersion + 1, now);
            seq++;
        }

        CaseTransfer transfer = caseTransferRepository.findById(transferId).orElseThrow();
        CaseTransferView view = toTransferView(transfer,
                caseTransferRepository.findItems(transferId));
        return record(request.commandKey(), OP_CASE_TRANSFER, actorId, requestHash, 200, view, now);
    }

    /**
     * 撤销跨案移交：仅限目标案件尚未发生后续交接，双方不同保管人确认，追加反向链。
     */
    @Transactional
    public StoredResponse revoke(String actorId, String transferId,
                                 CaseTransferRevokeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        CaseTransfer transfer = caseTransferRepository.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferId));
        if (transfer.status() != CaseTransferStatus.TRANSFERRED) {
            throw ApiException.conflict("跨案移交已撤销，不可重复撤销: " + transferId);
        }
        if (!request.fromCustodian().equals(transfer.fromCustodian())
                || !request.toCustodian().equals(transfer.toCustodian())) {
            throw ApiException.conflict("撤销确认人必须与原移交双方保管人一致: " + transferId);
        }
        if (request.fromCustodian().equals(request.toCustodian())) {
            throw ApiException.conflict("撤销必须由双方不同保管人确认");
        }
        if (!request.fromCustodian().equals(actorId)) {
            throw ApiException.conflict("操作者必须与来源案件保管人一致: " + actorId);
        }

        List<CaseTransferItem> items = caseTransferRepository.findItems(transferId);
        List<String> sortedKeys = items.stream().map(CaseTransferItem::evidenceKey)
                .sorted(Comparator.naturalOrder()).toList();
        // 锁定全部相关证物，与移交/借出/核验等写操作按提交顺序互斥。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdate(sortedKeys);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = clock.nowUtc();

        Map<String, Evidence> evidenceByKey = new LinkedHashMap<>();
        for (Evidence evidence : locked) {
            evidenceByKey.put(evidence.evidenceKey(), evidence);
        }
        for (CaseTransferItem item : items) {
            Evidence evidence = evidenceByKey.get(item.evidenceKey());
            if (evidence == null) {
                throw ApiException.unprocessable("移交证物已缺失，撤销拒绝: " + item.evidenceKey());
            }
            // 目标案件承接后一旦发生人员交接、借出或再次跨案移交，即不允许撤销。
            if (!evidence.caseKey().equals(transfer.toCaseKey())) {
                throw ApiException.conflict(
                        "目标案件已发生后续跨案交接，禁止撤销: " + item.evidenceKey());
            }
            if (caseTransferRepository.existsPersonTransferAfter(
                    item.evidenceKey(), transfer.createdAt())) {
                throw ApiException.conflict(
                        "目标案件已发生后续保管人交接，禁止撤销: " + item.evidenceKey());
            }
            if (caseTransferRepository.existsLoanAfter(
                    item.evidenceKey(), transfer.createdAt())) {
                throw ApiException.conflict(
                        "目标案件已发生后续借出或归还，禁止撤销: " + item.evidenceKey());
            }
            if (caseTransferRepository.existsLaterCaseTransferEvent(
                    item.evidenceKey(), transferId, transfer.createdAt())) {
                throw ApiException.conflict(
                        "证物已存在更晚的跨案移交链事件，禁止撤销: " + item.evidenceKey());
            }
        }

        if (!caseTransferRepository.revoke(transferId, request.fromCustodian(),
                request.toCustodian(), now)) {
            throw ApiException.conflict("跨案移交已被并发撤销: " + transferId);
        }
        for (CaseTransferItem item : items) {
            Evidence evidence = evidenceByKey.get(item.evidenceKey());
            // 撤销只追加反向链：目标案反向移出、来源案反向移入；不删除原移交与原快照。
            chainEventRepository.insert(transferId, item.evidenceKey(), transfer.toCaseKey(),
                    ChainEventType.REVERSE_OUT, item.seq(), transfer.toCustodian(),
                    evidence.location(), evidence.sealVersion(), transfer.orderVersion(), now);
            chainEventRepository.insert(transferId, item.evidenceKey(), transfer.fromCaseKey(),
                    ChainEventType.REVERSE_IN, item.seq(), transfer.fromCustodian(),
                    evidence.location(), evidence.sealVersion(), transfer.orderVersion(), now);
            evidenceRepository.switchCase(item.evidenceKey(), transfer.fromCaseKey(),
                    transfer.fromCustodian(), evidence.sealVersion() + 1, now);
        }

        CaseTransfer revoked = caseTransferRepository.findById(transferId).orElseThrow();
        CaseTransferView view = toTransferView(revoked, items);
        return record(request.commandKey(), OP_CASE_TRANSFER_REVOKE, actorId, requestHash,
                200, view, now);
    }

    /**
     * 移交明细查询（只读）：主单 + 规范化排序明细。
     */
    @Transactional(readOnly = true)
    public CaseTransferView getTransfer(String transferId) {
        CaseTransfer transfer = caseTransferRepository.findById(transferId)
                .orElseThrow(() -> ApiException.notFound("跨案移交不存在: " + transferId));
        return toTransferView(transfer, caseTransferRepository.findItems(transferId));
    }

    /**
     * 证物跨案历史查询（只读）：当前证物视图 + 全部跨案移交 + 案件链事件 + 双案封存快照。
     */
    @Transactional(readOnly = true)
    public CaseHistoryView history(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<CaseTransfer> transfers = caseTransferRepository.findAllByEvidenceKey(evidenceKey);
        List<CaseTransferView> transferViews = new ArrayList<>();
        for (CaseTransfer transfer : transfers) {
            transferViews.add(toTransferView(transfer,
                    caseTransferRepository.findItems(transfer.transferId())));
        }
        List<CaseChainEventView> events = chainEventRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toEventView).toList();
        List<CaseSealSnapshotView> snapshots = snapshotRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toSnapshotView).toList();
        return new CaseHistoryView(toEvidenceView(evidence), transferViews, events, snapshots);
    }

    /**
     * 诊断查询（只读）：给出当前 UTC 时刻、移交令版本生效判定（含要求区间与差额）、
     * 双方授权状态、每件证物的实际状态与可移交判定；不改变任何状态。
     */
    @Transactional(readOnly = true)
    public CaseTransferDiagnosisView diagnose(CaseTransferRequest request) {
        LocalDateTime now = clock.nowUtc();
        List<String> sortedKeys = normalizeKeys(request.evidenceKeys());

        OrderStatusView orderStatus = orderRepository
                .findByVersion(request.orderNo(), request.orderVersion())
                .map(order -> describeOrder(order, request, now))
                .orElseGet(() -> new OrderStatusView(request.orderNo(), request.orderVersion(),
                        null, null, null, false, "移交令版本不存在", null, null, null));

        List<GrantStatusView> grants = List.of(
                describeGrant(request.fromCaseKey(), request.fromCustodian()),
                describeGrant(request.toCaseKey(), request.toCustodian()));

        Map<String, Evidence> evidenceByKey = new LinkedHashMap<>();
        for (String key : sortedKeys) {
            evidenceRepository.findByKey(key).ifPresent(e -> evidenceByKey.put(key, e));
        }
        List<TransferItemDiagnosisView> items = new ArrayList<>();
        long blocked = 0;
        for (String key : sortedKeys) {
            Evidence evidence = evidenceByKey.get(key);
            TransferItemDiagnosisView item;
            if (evidence == null) {
                item = new TransferItemDiagnosisView(key, false, null, null, null, 0,
                        false, "证物不存在");
                blocked++;
            } else {
                String reason = transferBlockReason(evidence, request);
                boolean transferable = reason == null;
                if (!transferable) {
                    blocked++;
                }
                item = new TransferItemDiagnosisView(key, true, evidence.caseKey(),
                        evidence.custodianId(), evidence.status().name(), evidence.sealVersion(),
                        transferable, reason);
            }
            items.add(item);
        }
        boolean custodiansDistinct = !request.fromCustodian().equals(request.toCustodian());
        boolean allGranted = grants.stream().allMatch(GrantStatusView::active);
        boolean transferable = orderStatus.effective() && custodiansDistinct && allGranted
                && blocked == 0 && !request.fromCaseKey().equals(request.toCaseKey());
        return new CaseTransferDiagnosisView(now, request.fromCaseKey(), request.toCaseKey(),
                sortedKeys.size(), items.size(), blocked, custodiansDistinct, orderStatus,
                grants, items, transferable);
    }

    /**
     * 判断移交令版本在指定 UTC 时刻是否生效：左闭右开 [validFrom, validTo)。
     * 边界：now == validFrom 生效；now == validTo 失效。供单元测试直接覆盖。
     */
    static boolean isOrderEffective(TransferOrder order, LocalDateTime now) {
        if (order.status() != OrderStatus.VALID) {
            return false;
        }
        if (now.isBefore(order.validFrom())) {
            return false;
        }
        return order.validTo() == null || now.isBefore(order.validTo());
    }

    /**
     * 规范化证物列表：去重并按字典序排序。出现重复键视为请求非法（400）。
     */
    static List<String> normalizeKeys(List<String> keys) {
        List<String> sorted = keys.stream().sorted().distinct().toList();
        if (sorted.size() != keys.size()) {
            throw ApiException.badRequest("证物列表存在重复键，整批移交拒绝");
        }
        return sorted;
    }

    private void validateTransferable(Evidence evidence, CaseTransferRequest request) {
        String reason = transferBlockReason(evidence, request);
        if (reason != null) {
            throw ApiException.unprocessable(reason);
        }
    }

    /**
     * 单件证物的 422 类阻断原因；返回 null 表示满足移交前置条件。
     */
    private String transferBlockReason(Evidence evidence, CaseTransferRequest request) {
        String key = evidence.evidenceKey();
        if (!evidence.caseKey().equals(request.fromCaseKey())) {
            List<CaseTransfer> activeTransfers =
                    caseTransferRepository.findActiveByEvidenceKey(key);
            if (!activeTransfers.isEmpty()) {
                return "证物已处于其他跨案移交中，当前所属案件: "
                        + evidence.caseKey() + "，证物: " + key;
            }
            return "证物不属于来源案件 " + request.fromCaseKey()
                    + "，当前所属案件: " + evidence.caseKey() + "，证物: " + key;
        }
        return switch (evidence.status()) {
            case BORROWED -> "证物借出未归还，禁止跨案移交: " + key;
            case SEAL_BROKEN -> "封签异常，禁止跨案移交: " + key;
            case TRANSFER_PENDING -> "证物存在待接收的保管人交接，禁止跨案移交: " + key;
            case SEALED -> null;
        };
    }

    private TransferOrder requireEffectiveOrder(CaseTransferRequest request, LocalDateTime now) {
        TransferOrder order = orderRepository.findByVersion(request.orderNo(), request.orderVersion())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "移交令版本不存在或已失效: " + request.orderNo() + " v"
                                + request.orderVersion()));
        if (!order.fromCaseKey().equals(request.fromCaseKey())
                || !order.toCaseKey().equals(request.toCaseKey())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "移交令版本载明的双方案件与请求不符，禁止移交: " + request.orderNo()
                            + " v" + request.orderVersion());
        }
        if (order.status() != OrderStatus.VALID) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "移交令版本已作废: " + request.orderNo() + " v" + request.orderVersion());
        }
        if (now.isBefore(order.validFrom())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "移交令版本尚未生效，生效时刻: " + order.validFrom() + "，当前时刻: " + now);
        }
        if (order.validTo() != null && !now.isBefore(order.validTo())) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "移交令版本已过生效区间（左闭右开），失效时刻: " + order.validTo()
                            + "，当前时刻: " + now + "，超出时长: "
                            + Duration.between(order.validTo(), now).toSeconds() + " 秒");
        }
        return order;
    }

    private void requireDistinctCustodians(CaseTransferRequest request) {
        if (request.fromCustodian().equals(request.toCustodian())) {
            throw ApiException.conflict("来源与目标案件保管人不得相同: " + request.fromCustodian());
        }
    }

    private void requireGrant(String caseKey, String custodianId, String caseLabel) {
        if (grantRepository.findActive(caseKey, custodianId).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    caseLabel + "保管人不具备该案件权限: " + custodianId + "，案件: " + caseKey);
        }
    }

    private GrantStatusView describeGrant(String caseKey, String custodianId) {
        return grantRepository.findActive(caseKey, custodianId)
                .map(grant -> new GrantStatusView(caseKey, custodianId, true))
                .orElseGet(() -> new GrantStatusView(caseKey, custodianId, false));
    }

    private OrderStatusView describeOrder(TransferOrder order, CaseTransferRequest request,
                                          LocalDateTime now) {
        boolean caseMatches = order.fromCaseKey().equals(request.fromCaseKey())
                && order.toCaseKey().equals(request.toCaseKey());
        boolean effective = isOrderEffective(order, now) && caseMatches;
        String reason;
        if (!caseMatches) {
            reason = "移交令载明案件与请求不符";
        } else if (order.status() != OrderStatus.VALID) {
            reason = "移交令版本已作废";
        } else if (now.isBefore(order.validFrom())) {
            reason = "尚未生效，距生效时长: "
                    + Duration.between(now, order.validFrom()).toSeconds() + " 秒";
        } else if (order.validTo() != null && !now.isBefore(order.validTo())) {
            reason = "已过生效区间（左闭右开），超出时长: "
                    + Duration.between(order.validTo(), now).toSeconds() + " 秒";
        } else {
            reason = null;
        }
        return new OrderStatusView(order.orderNo(), order.version(), order.status().name(),
                order.validFrom(), order.validTo(), effective, reason,
                order.fromCaseKey(), order.toCaseKey(), caseMatches);
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
                                  String requestHash, int status, Object body, LocalDateTime now) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json, now);
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private EvidenceView toEvidenceView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.status(), evidence.location(),
                evidence.sealVersion(), evidence.createdAt(), evidence.updatedAt());
    }

    private CaseTransferView toTransferView(CaseTransfer transfer, List<CaseTransferItem> items) {
        List<CaseTransferItemView> itemViews = items.stream()
                .sorted(Comparator.comparingInt(CaseTransferItem::seq))
                .map(item -> new CaseTransferItemView(item.seq(), item.evidenceKey(),
                        item.originalLocation(), item.sealVersion()))
                .toList();
        return new CaseTransferView(transfer.transferId(), transfer.orderNo(),
                transfer.orderVersion(), transfer.fromCaseKey(), transfer.toCaseKey(),
                transfer.fromCustodian(), transfer.toCustodian(), transfer.status(),
                itemViews.size(), transfer.createdAt(), transfer.revokedAt(),
                transfer.revokeFromCustodian(), transfer.revokeToCustodian(), itemViews);
    }

    private CaseChainEventView toEventView(CaseChainEvent event) {
        return new CaseChainEventView(event.transferId(), event.evidenceKey(), event.caseKey(),
                event.eventType(), event.seqInTransfer(), event.custodianId(), event.location(),
                event.sealVersion(), event.orderVersion(), event.createdAt());
    }

    private CaseSealSnapshotView toSnapshotView(CaseSealSnapshot snapshot) {
        return new CaseSealSnapshotView(snapshot.transferId(), snapshot.caseKey(), snapshot.side(),
                snapshot.evidenceKey(), snapshot.originalLocation(), snapshot.sealVersion(),
                snapshot.orderVersion(), snapshot.chainEventId(), snapshot.createdAt());
    }
}
