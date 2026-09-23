package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CasePermissionGrantRequest;
import com.example.starter.evidence.dto.LoanPackageView;
import com.example.starter.evidence.dto.PackageBatchView;
import com.example.starter.evidence.dto.PackageCancelRequest;
import com.example.starter.evidence.dto.PackageCancelView;
import com.example.starter.evidence.dto.PackageChainView;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageItemView;
import com.example.starter.evidence.dto.PackageRemainingView;
import com.example.starter.evidence.dto.PackageReturnRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 组合借出包核心服务。所有写操作在单事务内完成：
 * 建包先按主键顺序锁定全部证物行（SELECT ... FOR UPDATE），归还/撤销先锁定组合包行，
 * 再校验状态机并落库，最后写入幂等日志。归还批次与逐件快照只追加；
 * 最后一件归还时在同一事务自动关闭并写入不可变关闭快照，不可单独关闭。
 */
@Service
public class LoanPackageService {

    static final String OP_PACKAGE_CREATE = "PACKAGE_CREATE";
    static final String OP_PACKAGE_RETURN = "PACKAGE_RETURN";
    static final String OP_PACKAGE_CANCEL = "PACKAGE_CANCEL";
    static final String OP_CASE_PERMISSION_GRANT = "CASE_PERMISSION_GRANT";

    /**
     * 借出最长期限：72 小时（与单件借出一致）。
     */
    static final long MAX_LOAN_HOURS = 72;

    private final LoanPackageRepository packageRepository;
    private final LoanPackageItemRepository itemRepository;
    private final PackageReturnBatchRepository batchRepository;
    private final PackageReturnItemRepository returnItemRepository;
    private final CasePermissionRepository permissionRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public LoanPackageService(LoanPackageRepository packageRepository,
                              LoanPackageItemRepository itemRepository,
                              PackageReturnBatchRepository batchRepository,
                              PackageReturnItemRepository returnItemRepository,
                              CasePermissionRepository permissionRepository,
                              EvidenceRepository evidenceRepository,
                              CommandLogRepository commandLogRepository,
                              ObjectMapper objectMapper,
                              EvidenceClock clock) {
        this.packageRepository = packageRepository;
        this.itemRepository = itemRepository;
        this.batchRepository = batchRepository;
        this.returnItemRepository = returnItemRepository;
        this.permissionRepository = permissionRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记案件权限：同一 (caseKey, userId) 重复登记幂等。
     */
    @Transactional
    public StoredResponse grantPermission(String actorId, CasePermissionGrantRequest request,
                                          String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        permissionRepository.grantIfAbsent(request.caseKey(), request.userId(), actorId,
                LocalDateTime.now());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseKey", request.caseKey());
        body.put("userId", request.userId());
        body.put("granted", true);
        return record(request.requestId(), OP_CASE_PERMISSION_GRANT, actorId, requestHash,
                200, body);
    }

    /**
     * 创建组合借出包：经办人须为全部证物当前保管人；全部证物同案件、同保管点、
     * 状态 SEALED 且 expectedVersion 与当前版本一致；任一件不满足则整包失败。
     * 借用人与保管人不同；dueAt 为 UTC 时刻，须晚于当前且不超过 72 小时。
     */
    @Transactional
    public StoredResponse createPackage(String actorId, PackageCreateRequest request,
                                        String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<PackageCreateRequest.Item> items = request.items();
        requireNoDuplicateEvidence(items.stream().map(PackageCreateRequest.Item::evidenceKey).toList());
        if (request.borrowerId().equals(actorId)) {
            throw ApiException.badRequest("借用人不能与经办保管人相同");
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!request.dueAt().isAfter(nowUtc)) {
            throw ApiException.badRequest("应还时刻须晚于服务端当前时刻");
        }
        if (request.dueAt().isAfter(nowUtc.plusHours(MAX_LOAN_HOURS))) {
            throw ApiException.badRequest("借出期限不得超过 " + MAX_LOAN_HOURS + " 小时");
        }
        if (packageRepository.findByKey(request.packageKey()).isPresent()) {
            throw ApiException.conflict("组合包键已存在: " + request.packageKey());
        }
        List<String> keys = items.stream().map(PackageCreateRequest.Item::evidenceKey).toList();
        List<Evidence> locked = evidenceRepository.findByKeysForUpdate(keys);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<String, Evidence> byKey = new HashMap<>();
        for (Evidence evidence : locked) {
            byKey.put(evidence.evidenceKey(), evidence);
        }
        Map<String, Long> expectedVersions = new HashMap<>();
        for (PackageCreateRequest.Item item : items) {
            expectedVersions.put(item.evidenceKey(), item.expectedVersion());
        }
        String caseKey = null;
        String custodianId = null;
        for (String key : keys) {
            Evidence evidence = byKey.get(key);
            if (evidence == null) {
                throw ApiException.notFound("证物不存在: " + key);
            }
            if (caseKey == null) {
                caseKey = evidence.caseKey();
                custodianId = evidence.custodianId();
            } else {
                if (!caseKey.equals(evidence.caseKey())) {
                    throw ApiException.conflict("组合包证物必须属于同一案件: " + key);
                }
                if (!custodianId.equals(evidence.custodianId())) {
                    throw ApiException.conflict("组合包证物当前保管点必须一致: " + key);
                }
            }
            if (!custodianId.equals(actorId)) {
                throw ApiException.conflict("经办人不是全部证物的当前保管人: " + actorId);
            }
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable("封条已异常，禁止借出: " + key);
            }
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                throw ApiException.conflict("待接收交接期间禁止借出: " + key);
            }
            if (evidence.status() == EvidenceStatus.BORROWED) {
                throw ApiException.conflict("证物已借出，整包创建失败: " + key);
            }
            if (evidence.version() != expectedVersions.get(key)) {
                throw ApiException.conflict("证物版本已变化，整包创建失败: " + key);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        long packageId = packageRepository.insert(request.packageKey(), caseKey, custodianId,
                request.borrowerId(), request.purpose(), request.dueAt(), nowUtc, now);
        // 按证物锁定顺序（主键序）分配稳定排序序号并冻结 sealVersion。
        int seq = 0;
        for (Evidence evidence : locked) {
            seq++;
            itemRepository.insert(packageId, evidence.evidenceKey(), evidence.version(), seq, now);
            evidenceRepository.updateStatus(evidence.evidenceKey(), EvidenceStatus.BORROWED, now);
        }
        LoanPackageView view = buildView(packageId);
        return record(request.requestId(), OP_PACKAGE_CREATE, actorId, requestHash, 201, view);
    }

    /**
     * 分批归还：提交包内一个非空子集、各件冻结 sealVersion、接收人和复核人。
     * 先核对完整子集（拒绝重复证物、已归还、非本包、封条版本不符），
     * 再在一个事务内写入双人确认批次、逐件快照并更新明细与证物状态；
     * 任一件失败则整批回滚。最后一件归还时同事务自动关闭并写入关闭快照。
     */
    @Transactional
    public StoredResponse returnBatch(String actorId, String packageKey,
                                      PackageReturnRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.receiverId().equals(request.reviewerId())) {
            throw ApiException.badRequest("接收人与复核人必须不同");
        }
        List<PackageReturnRequest.Item> subset = request.items();
        requireNoDuplicateEvidence(subset.stream().map(PackageReturnRequest.Item::evidenceKey).toList());
        LoanPackage pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        // 并发下本事务可能在组合包行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (pkg.status() == PackageStatus.CLOSED) {
            throw ApiException.conflict("组合包已关闭，禁止再归还: " + packageKey);
        }
        if (!permissionRepository.hasPermission(pkg.caseKey(), request.receiverId())) {
            throw ApiException.conflict("接收人不具备案件权限: " + request.receiverId());
        }
        if (!permissionRepository.hasPermission(pkg.caseKey(), request.reviewerId())) {
            throw ApiException.conflict("复核人不具备案件权限: " + request.reviewerId());
        }
        List<LoanPackageItem> allItems = itemRepository.findByPackageId(pkg.id());
        Map<String, LoanPackageItem> itemByKey = new HashMap<>();
        for (LoanPackageItem item : allItems) {
            itemByKey.put(item.evidenceKey(), item);
        }
        // 先核对完整子集：非本包、已归还、封条版本不符均拒绝，整批不落账。
        List<LoanPackageItem> batchItems = new ArrayList<>();
        for (PackageReturnRequest.Item requested : subset) {
            LoanPackageItem item = itemByKey.get(requested.evidenceKey());
            if (item == null) {
                throw ApiException.notFound("证物不属于本组合包: " + requested.evidenceKey());
            }
            if (item.status() == PackageItemStatus.RETURNED) {
                throw ApiException.conflict("证物已归还，整批回滚: " + requested.evidenceKey());
            }
            if (item.sealVersion() != requested.sealVersion()) {
                throw ApiException.conflict("封条版本不符，整批回滚: " + requested.evidenceKey());
            }
            batchItems.add(item);
        }
        // 锁定本批证物行，确认仍处借出状态（保管点未变）。
        List<String> batchKeys = batchItems.stream().map(LoanPackageItem::evidenceKey).toList();
        List<Evidence> lockedEvidence = evidenceRepository.findByKeysForUpdate(batchKeys);
        for (Evidence evidence : lockedEvidence) {
            if (evidence.status() != EvidenceStatus.BORROWED) {
                throw ApiException.conflict("证物当前不在借出状态，整批回滚: " + evidence.evidenceKey());
            }
            if (!evidence.custodianId().equals(pkg.custodianId())) {
                throw ApiException.conflict("证物保管点已变化，整批回滚: " + evidence.evidenceKey());
            }
        }
        LocalDateTime nowUtc = clock.nowUtc();
        LocalDateTime now = LocalDateTime.now();
        int batchSeq = batchRepository.findByPackageId(pkg.id()).size() + 1;
        boolean closesPackage = allItems.stream()
                .filter(item -> item.status() == PackageItemStatus.OUT)
                .count() == batchItems.size();
        long batchId = batchRepository.insert(pkg.id(), request.receiverId(), request.reviewerId(),
                batchSeq, nowUtc, closesPackage, now);
        for (LoanPackageItem item : batchItems) {
            if (!itemRepository.markReturned(item.id(), batchId, nowUtc)) {
                throw ApiException.conflict("证物已被并发归还，整批回滚: " + item.evidenceKey());
            }
            returnItemRepository.insert(batchId, pkg.id(), item.evidenceKey(), item.sealVersion(),
                    item.itemSeq(), now);
            evidenceRepository.updateStatus(item.evidenceKey(), EvidenceStatus.SEALED, now);
        }
        if (closesPackage) {
            String snapshot = buildCloseSnapshot(pkg, batchSeq);
            if (!packageRepository.closeIfPartial(pkg.id(), nowUtc, snapshot, now)) {
                throw ApiException.conflict("组合包已被并发关闭: " + packageKey);
            }
        } else {
            packageRepository.touch(pkg.id(), now);
        }
        LoanPackageView view = buildView(pkg.id());
        return record(request.requestId(), OP_PACKAGE_RETURN, actorId, requestHash, 200, view);
    }

    /**
     * 借出撤销：仅允许尚无任何归还且全部证物仍在借出人名下（仍 BORROWED 且保管点未变）时
     * 原子执行；全部证物恢复 SEALED，组合包及明细整体移除，不形成部分借出记录。
     */
    @Transactional
    public StoredResponse cancelPackage(String actorId, String packageKey,
                                        PackageCancelRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LoanPackage pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        // 并发下本事务可能在组合包行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!pkg.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅建包保管人可撤销借出: " + actorId);
        }
        if (pkg.status() == PackageStatus.CLOSED) {
            throw ApiException.conflict("组合包已关闭，禁止撤销: " + packageKey);
        }
        if (!batchRepository.findByPackageId(pkg.id()).isEmpty()) {
            throw ApiException.conflict("已存在归还批次，禁止撤销: " + packageKey);
        }
        List<LoanPackageItem> items = itemRepository.findByPackageId(pkg.id());
        List<String> keys = items.stream().map(LoanPackageItem::evidenceKey).toList();
        List<Evidence> lockedEvidence = evidenceRepository.findByKeysForUpdate(keys);
        for (Evidence evidence : lockedEvidence) {
            if (evidence.status() != EvidenceStatus.BORROWED
                    || !evidence.custodianId().equals(pkg.custodianId())) {
                throw ApiException.conflict("证物已不在借出人名下，禁止撤销: " + evidence.evidenceKey());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> restored = new ArrayList<>();
        for (Evidence evidence : lockedEvidence) {
            evidenceRepository.updateStatus(evidence.evidenceKey(), EvidenceStatus.SEALED, now);
            restored.add(evidence.evidenceKey());
        }
        itemRepository.deleteByPackageId(pkg.id());
        packageRepository.deleteById(pkg.id());
        return record(request.requestId(), OP_PACKAGE_CANCEL, actorId, requestHash,
                200, new PackageCancelView(packageKey, true, restored));
    }

    /**
     * 查询组合包：全部借出明细与归还批次，只读并稳定排序。
     */
    @Transactional(readOnly = true)
    public LoanPackageView getPackage(String packageKey) {
        LoanPackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        return buildView(pkg.id());
    }

    /**
     * 查询组合包剩余未归还集合，只读并稳定排序。
     */
    @Transactional(readOnly = true)
    public PackageRemainingView getRemaining(String packageKey) {
        LoanPackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        List<String> remaining = itemRepository.findOutstandingByPackageId(pkg.id()).stream()
                .map(LoanPackageItem::evidenceKey)
                .toList();
        return new PackageRemainingView(packageKey, remaining);
    }

    /**
     * 查询组合包链路证据：借出明细（含冻结封条版本）+ 全部归还批次 + 关闭快照。
     */
    @Transactional(readOnly = true)
    public PackageChainView getChain(String packageKey) {
        LoanPackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        List<PackageItemView> items = buildItemViews(pkg.id());
        List<PackageBatchView> batches = buildBatchViews(pkg.id());
        return new PackageChainView(pkg.packageKey(), pkg.status(), pkg.loanAt(), pkg.dueAt(),
                items, batches, pkg.closeSnapshot());
    }

    private void requireNoDuplicateEvidence(List<String> keys) {
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                throw ApiException.badRequest("请求包含重复证物: " + key);
            }
        }
    }

    private LoanPackageView buildView(long packageId) {
        LoanPackage pkg = packageRepository.findById(packageId)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageId));
        return new LoanPackageView(pkg.packageKey(), pkg.caseKey(), pkg.custodianId(),
                pkg.borrowerId(), pkg.purpose(), pkg.dueAt(), pkg.loanAt(), pkg.status(),
                pkg.closedAt(), buildItemViews(packageId), buildBatchViews(packageId));
    }

    private List<PackageItemView> buildItemViews(long packageId) {
        List<LoanPackageItem> items = itemRepository.findByPackageId(packageId);
        List<PackageReturnBatch> batches = batchRepository.findByPackageId(packageId);
        Map<Long, Integer> batchSeqById = new HashMap<>();
        for (PackageReturnBatch batch : batches) {
            batchSeqById.put(batch.id(), batch.batchSeq());
        }
        List<PackageItemView> views = new ArrayList<>();
        for (LoanPackageItem item : items) {
            Integer batchSeq = item.returnedBatchId() == null
                    ? null : batchSeqById.get(item.returnedBatchId());
            views.add(new PackageItemView(item.evidenceKey(), item.sealVersion(), item.itemSeq(),
                    item.status(), batchSeq, item.returnedAt()));
        }
        return views;
    }

    private List<PackageBatchView> buildBatchViews(long packageId) {
        List<PackageReturnBatch> batches = batchRepository.findByPackageId(packageId);
        List<PackageBatchView> views = new ArrayList<>();
        for (PackageReturnBatch batch : batches) {
            List<PackageBatchView.Item> items = returnItemRepository.findByBatchId(batch.id())
                    .stream()
                    .map(item -> new PackageBatchView.Item(item.evidenceKey(), item.sealVersion(),
                            item.itemSeq()))
                    .toList();
            views.add(new PackageBatchView(batch.batchSeq(), batch.receiverId(), batch.reviewerId(),
                    batch.returnedAt(), batch.closedPackage(), items));
        }
        return views;
    }

    /**
     * 构建关闭快照 JSON：包含全部借出明细与归还批次（含双人确认与逐件封条版本）。
     */
    private String buildCloseSnapshot(LoanPackage pkg, int upToBatchSeq) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("packageKey", pkg.packageKey());
        snapshot.put("caseKey", pkg.caseKey());
        snapshot.put("custodianId", pkg.custodianId());
        snapshot.put("borrowerId", pkg.borrowerId());
        snapshot.put("purpose", pkg.purpose());
        snapshot.put("loanAt", pkg.loanAt().toString());
        snapshot.put("dueAt", pkg.dueAt().toString());
        snapshot.put("items", buildItemViews(pkg.id()));
        snapshot.put("batches", buildBatchViews(pkg.id()));
        snapshot.put("batchCount", upToBatchSeq);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("关闭快照序列化失败", e);
        }
    }

    private Optional<StoredResponse> checkReplay(String requestId, String requestHash) {
        return commandLogRepository.findByKey(requestId).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + requestId);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String requestId, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(requestId, actorId, operation, requestHash, status, json,
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
}
