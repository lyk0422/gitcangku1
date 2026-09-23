package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.PackageCancelRequest;
import com.example.starter.evidence.dto.PackageCreateRequest;
import com.example.starter.evidence.dto.PackageItemView;
import com.example.starter.evidence.dto.PackageReturnRequest;
import com.example.starter.evidence.dto.PackageView;
import com.example.starter.evidence.dto.ReturnBatchView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 组合借出包核心服务。
 * 创建：锁定全部证物行后校验同案件/同保管点/可借/版本一致，任一失败整包不创建；
 * 分批归还：先锁包行串行化同包并发，再锁证物行，双人确认、逐件保管链与不可变快照同事务落库，
 * 任一件失败整批回滚；最后一件归还时同事务 PARTIAL→CLOSED 并写关闭快照，不能单独关闭。
 */
@Service
public class PackageService {

    static final String OP_PACKAGE_CREATE = "PACKAGE_CREATE";
    static final String OP_PACKAGE_RETURN = "PACKAGE_RETURN";
    static final String OP_PACKAGE_CANCEL = "PACKAGE_CANCEL";

    private final LoanPackageRepository packageRepository;
    private final PackageItemRepository itemRepository;
    private final ReturnBatchRepository batchRepository;
    private final PackageReturnChainRepository chainRepository;
    private final PackageSnapshotRepository snapshotRepository;
    private final EvidenceRepository evidenceRepository;
    private final CaseGrantRepository grantRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public PackageService(LoanPackageRepository packageRepository,
                          PackageItemRepository itemRepository,
                          ReturnBatchRepository batchRepository,
                          PackageReturnChainRepository chainRepository,
                          PackageSnapshotRepository snapshotRepository,
                          EvidenceRepository evidenceRepository,
                          CaseGrantRepository grantRepository,
                          CommandLogRepository commandLogRepository,
                          ObjectMapper objectMapper,
                          EvidenceClock clock) {
        this.packageRepository = packageRepository;
        this.itemRepository = itemRepository;
        this.batchRepository = batchRepository;
        this.chainRepository = chainRepository;
        this.snapshotRepository = snapshotRepository;
        this.evidenceRepository = evidenceRepository;
        this.grantRepository = grantRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建组合借出包：2~20 件同案件、同保管点且当前可借（SEALED）证物整体借出。
     * 任一件不存在、已借出、封条异常、待交接或 expectedVersion 不符，整包失败，无任何部分借出。
     */
    @Transactional
    public StoredResponse createPackage(String actorId, PackageCreateRequest request,
                                        String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (packageRepository.findByKey(request.packageKey()).isPresent()) {
            throw ApiException.conflict("组合包已存在: " + request.packageKey());
        }
        // 请求内证物键去重校验（Bean Validation 只校验非空与数量）。
        List<String> evidenceKeys = request.items().stream()
                .map(PackageCreateRequest.PackageItemRequest::evidenceKey)
                .sorted().toList();
        if (Set.copyOf(evidenceKeys).size() != evidenceKeys.size()) {
            throw ApiException.badRequest("组合包内证物重复: " + request.packageKey());
        }
        if (request.borrowerId().equals(actorId)) {
            throw ApiException.badRequest("借用人不能与经办人（当前保管人）相同");
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!request.dueAt().isAfter(nowUtc)) {
            throw ApiException.badRequest("应还时刻须晚于服务端当前时刻");
        }
        if (request.dueAt().isAfter(nowUtc.plusHours(EvidenceService.MAX_LOAN_HOURS))) {
            throw ApiException.badRequest("借出期限不得超过 " + EvidenceService.MAX_LOAN_HOURS + " 小时");
        }
        // 固定按证物键排序加锁，避免与其他多证物事务交叉等锁。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdateOrdered(
                new ArrayList<>(Set.copyOf(evidenceKeys)));
        // 并发下可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (locked.size() != evidenceKeys.size()) {
            throw ApiException.notFound("组合包包含不存在的证物: " + request.packageKey());
        }
        Map<String, Evidence> evidenceByKey = locked.stream()
                .collect(Collectors.toMap(Evidence::evidenceKey, e -> e, (a, b) -> a, LinkedHashMap::new));
        String caseKey = locked.get(0).caseKey();
        String custodianId = locked.get(0).custodianId();
        if (!locked.stream().allMatch(e -> e.caseKey().equals(caseKey))) {
            throw ApiException.badRequest("组合包内证物必须属于同一案件: " + request.packageKey());
        }
        if (!custodianId.equals(actorId)) {
            throw ApiException.conflict("经办人不是当前保管点保管人: " + actorId);
        }
        if (!locked.stream().allMatch(e -> e.custodianId().equals(custodianId))) {
            throw ApiException.badRequest("组合包内证物当前保管点不一致: " + request.packageKey());
        }
        Map<String, Long> expectedVersions = request.items().stream()
                .collect(Collectors.toMap(PackageCreateRequest.PackageItemRequest::evidenceKey,
                        PackageCreateRequest.PackageItemRequest::expectedVersion, (a, b) -> a));
        for (Evidence evidence : locked) {
            if (evidence.status() != EvidenceStatus.SEALED) {
                if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                    throw ApiException.unprocessable(
                            "封条已异常，禁止组合借出: " + evidence.evidenceKey());
                }
                throw ApiException.conflict(
                        "证物当前不可借（已借出或待交接）: " + evidence.evidenceKey());
            }
            if (!evidence.version().equals(expectedVersions.get(evidence.evidenceKey()))) {
                throw ApiException.conflict(
                        "证物封条版本已变化: " + evidence.evidenceKey());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        packageRepository.insert(request.packageKey(), caseKey, custodianId, request.borrowerId(),
                actorId, request.purpose(), nowUtc, request.dueAt(), now);
        LoanPackage pkg = packageRepository.findByKey(request.packageKey()).orElseThrow();
        for (Evidence evidence : locked) {
            // 冻结借出时封条版本（当前 SEALED 版本），随后条件置为 BORROWED（版本加 1）。
            itemRepository.insert(pkg.id(), evidence.evidenceKey(), evidence.version(), now);
            if (!evidenceRepository.compareAndSetStatus(evidence.evidenceKey(),
                    EvidenceStatus.SEALED, EvidenceStatus.BORROWED, LocalDateTime.now())) {
                throw ApiException.conflict(
                        "证物已被并发借出: " + evidence.evidenceKey());
            }
        }
        PackageView view = loadView(pkg);
        return record(request.commandKey(), OP_PACKAGE_CREATE, actorId, requestHash, 200, view);
    }

    /**
     * 分批归还：提交包内一个非空子集及各件冻结封条版本。
     * 先锁包行串行化同包并发，再校验子集与权限，全部通过后同事务写批次、逐件保管链、快照；
     * 最后一件归还时同事务自动关闭并写关闭快照。任一件失败整批不落账。
     */
    @Transactional
    public StoredResponse returnPackage(String actorId, String packageKey,
                                        PackageReturnRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 先锁包行：两个含重叠证物的归还请求在此串行，后提交者必然看到重叠件已归还而整批失败。
        LoanPackage pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        // 并发下可能在包行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (pkg.status() != PackageStatus.PARTIAL) {
            throw ApiException.conflict("组合包已关闭或已撤销，不能再归还: " + packageKey);
        }
        if (request.receiverId().equals(request.reviewerId())) {
            throw ApiException.badRequest("接收人与复核人必须不同");
        }
        requireCaseGrant(pkg.caseKey(), request.receiverId());
        requireCaseGrant(pkg.caseKey(), request.reviewerId());

        List<PackageReturnRequest.ReturnItemRequest> items = request.items().stream()
                .sorted(java.util.Comparator.comparing(PackageReturnRequest.ReturnItemRequest::evidenceKey))
                .toList();
        Set<String> submittedKeys = items.stream()
                .map(PackageReturnRequest.ReturnItemRequest::evidenceKey)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (submittedKeys.size() != items.size()) {
            throw ApiException.badRequest("本批归还包含重复证物: " + packageKey);
        }

        List<PackageItem> allItems = itemRepository.findByPackageIdOrdered(pkg.id());
        Map<String, PackageItem> itemByKey = allItems.stream()
                .collect(Collectors.toMap(PackageItem::evidenceKey, item -> item));
        // 服务端先核对完整子集，再进入任何写入。
        List<PackageItem> matched = new ArrayList<>();
        for (PackageReturnRequest.ReturnItemRequest asked : items) {
            PackageItem item = itemByKey.get(asked.evidenceKey());
            if (item == null) {
                throw ApiException.badRequest("证物不属于该组合包: " + asked.evidenceKey());
            }
            if (item.returnBatchId() != null) {
                throw ApiException.conflict("证物已归还，不得重复归还: " + asked.evidenceKey());
            }
            if (!item.frozenSealVersion().equals(asked.sealVersion())) {
                throw ApiException.conflict("封条版本与借出冻结版本不符: " + asked.evidenceKey());
            }
            matched.add(item);
        }
        // 锁全部涉事证物（与包行之间固定“先包行后证物行、证物按键排序”的加锁顺序）。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdateOrdered(
                new ArrayList<>(submittedKeys));
        Map<String, Evidence> evidenceByKey = locked.stream()
                .collect(Collectors.toMap(Evidence::evidenceKey, e -> e));
        for (PackageItem item : matched) {
            Evidence evidence = evidenceByKey.get(item.evidenceKey());
            if (evidence == null || evidence.status() != EvidenceStatus.BORROWED) {
                throw ApiException.conflict(
                        "证物不在组合借出未归还状态: " + item.evidenceKey());
            }
        }

        LocalDateTime nowUtc = clock.nowUtc();
        LocalDateTime now = LocalDateTime.now();
        long batchId = batchRepository.insert(pkg.id(), request.receiverId(), request.reviewerId(),
                nowUtc, request.note(), now);
        for (PackageItem item : matched) {
            if (!itemRepository.markReturned(item.id(), batchId, nowUtc)) {
                // 包行锁已串行同包归还，此处仍以条件回写兜底，失败即整批回滚。
                throw ApiException.conflict("证物已被并发归还: " + item.evidenceKey());
            }
            chainRepository.insert(pkg.id(), batchId, item.evidenceKey(),
                    item.frozenSealVersion(), request.receiverId(), request.reviewerId(), nowUtc);
            if (!evidenceRepository.compareAndSetStatus(item.evidenceKey(),
                    EvidenceStatus.BORROWED, EvidenceStatus.SEALED, LocalDateTime.now())) {
                throw ApiException.conflict(
                        "证物状态已被并发改变: " + item.evidenceKey());
            }
        }
        // 批次不可变归还快照（含本批双人确认与逐件封条版本）。
        snapshotRepository.insert(pkg.id(), PackageSnapshot.TYPE_RETURN, batchId,
                toJson(buildBatchSnapshot(pkg, batchId, request, matched, nowUtc)), now);

        PackageView view;
        List<PackageItem> refreshed = itemRepository.findByPackageIdOrdered(pkg.id());
        boolean allReturned = refreshed.stream().allMatch(i -> i.returnBatchId() != null);
        if (allReturned) {
            // 最后一件归还：同事务自动关闭，关闭快照含全部借出与全部归还批次；不能单独调用关闭。
            if (!packageRepository.close(pkg.id(), nowUtc, LocalDateTime.now())) {
                throw ApiException.conflict("组合包已被并发关闭: " + packageKey);
            }
            LoanPackage closed = packageRepository.findByKey(packageKey).orElseThrow();
            snapshotRepository.insert(pkg.id(), PackageSnapshot.TYPE_CLOSE, null,
                    toJson(buildCloseSnapshot(closed, refreshed, nowUtc)), LocalDateTime.now());
            view = loadView(closed);
        } else {
            view = loadView(pkg);
        }
        return record(request.commandKey(), OP_PACKAGE_RETURN, actorId, requestHash, 200, view);
    }

    /**
     * 借出撤销：仅允许尚无任何归还且全部证物仍在借出状态（在借出人名下）时原子执行。
     */
    @Transactional
    public StoredResponse cancelPackage(String actorId, String packageKey,
                                        PackageCancelRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LoanPackage pkg = packageRepository.findByKeyForUpdate(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!pkg.handlerId().equals(actorId)) {
            throw ApiException.conflict("仅组合借出经办人可撤销: " + actorId);
        }
        if (pkg.status() != PackageStatus.PARTIAL) {
            throw ApiException.conflict("组合包已关闭或已撤销: " + packageKey);
        }
        List<PackageItem> allItems = itemRepository.findByPackageIdOrdered(pkg.id());
        if (allItems.isEmpty()) {
            throw ApiException.conflict("组合包无借出明细: " + packageKey);
        }
        if (allItems.stream().anyMatch(i -> i.returnBatchId() != null)) {
            throw ApiException.conflict("组合包已有归还记录，不能撤销: " + packageKey);
        }
        List<String> evidenceKeys = allItems.stream().map(PackageItem::evidenceKey).sorted().toList();
        List<Evidence> locked = evidenceRepository.findByKeysForUpdateOrdered(evidenceKeys);
        if (locked.size() != evidenceKeys.size()) {
            throw ApiException.notFound("组合包包含不存在的证物: " + packageKey);
        }
        for (Evidence evidence : locked) {
            // 全部证物仍须处于组合借出未归还状态（即在借出人名下）。
            if (evidence.status() != EvidenceStatus.BORROWED) {
                throw ApiException.conflict(
                        "证物已不在借出人名下，不能撤销: " + evidence.evidenceKey());
            }
        }
        if (!packageRepository.cancel(pkg.id(), LocalDateTime.now())) {
            throw ApiException.conflict("组合包已被并发撤销或关闭: " + packageKey);
        }
        for (Evidence evidence : locked) {
            if (!evidenceRepository.compareAndSetStatus(evidence.evidenceKey(),
                    EvidenceStatus.BORROWED, EvidenceStatus.SEALED, LocalDateTime.now())) {
                throw ApiException.conflict(
                        "证物状态已被并发改变: " + evidence.evidenceKey());
            }
        }
        LoanPackage cancelled = packageRepository.findByKey(packageKey).orElseThrow();
        PackageView view = loadView(cancelled);
        return record(request.commandKey(), OP_PACKAGE_CANCEL, actorId, requestHash, 200, view);
    }

    /**
     * 查询组合包、剩余未归还集合及链路证据；只读，列表稳定排序。
     */
    @Transactional(readOnly = true)
    public PackageView getPackage(String packageKey) {
        LoanPackage pkg = packageRepository.findByKey(packageKey)
                .orElseThrow(() -> ApiException.notFound("组合包不存在: " + packageKey));
        return loadView(pkg);
    }

    /**
     * 追加案件授权（用于测试与初始化合成授权；幂等）。
     */
    @Transactional
    public void grantCase(String caseKey, String userId) {
        grantRepository.grant(caseKey, userId, LocalDateTime.now());
    }

    private void requireCaseGrant(String caseKey, String userId) {
        if (!grantRepository.hasGrant(caseKey, userId)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "操作人不具备案件权限: " + userId);
        }
    }

    private PackageView loadView(LoanPackage pkg) {
        List<PackageItem> items = itemRepository.findByPackageIdOrdered(pkg.id());
        List<PackageItemView> itemViews = items.stream()
                .map(i -> new PackageItemView(i.evidenceKey(), i.frozenSealVersion(),
                        i.returnBatchId() != null, i.returnBatchId(), i.returnedAt()))
                .toList();
        List<String> remaining = pkg.status() == PackageStatus.PARTIAL
                ? items.stream()
                        .filter(i -> i.returnBatchId() == null)
                        .map(PackageItem::evidenceKey)
                        .toList()
                : List.of();
        List<ReturnBatch> batches = batchRepository.findByPackageIdOrdered(pkg.id());
        List<ReturnBatchView> batchViews = batches.stream().map(batch -> {
            List<PackageReturnChain> chains = chainRepository.findByBatchIdOrdered(batch.id());
            List<ReturnBatchView.ChainItemView> chainViews = chains.stream()
                    .map(c -> new ReturnBatchView.ChainItemView(
                            c.evidenceKey(), c.sealVersion(), c.eventAt()))
                    .toList();
            return new ReturnBatchView(batch.id(), batch.receiverId(), batch.reviewerId(),
                    batch.returnedAt(), batch.note(), chainViews);
        }).toList();
        return new PackageView(pkg.packageKey(), pkg.caseKey(), pkg.custodianId(),
                pkg.borrowerId(), pkg.handlerId(), pkg.purpose(), pkg.loanAt(), pkg.dueAt(),
                pkg.status(), pkg.closedAt(), itemViews, remaining, batchViews);
    }

    private Map<String, Object> buildBatchSnapshot(LoanPackage pkg, long batchId,
                                                   PackageReturnRequest request,
                                                   List<PackageItem> matched,
                                                   LocalDateTime returnedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotType", PackageSnapshot.TYPE_RETURN);
        snapshot.put("packageKey", pkg.packageKey());
        snapshot.put("batchId", batchId);
        snapshot.put("receiverId", request.receiverId());
        snapshot.put("reviewerId", request.reviewerId());
        snapshot.put("note", request.note());
        snapshot.put("returnedAt", returnedAt);
        snapshot.put("items", matched.stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("evidenceKey", i.evidenceKey());
            m.put("sealVersion", i.frozenSealVersion());
            return m;
        }).toList());
        return snapshot;
    }

    private Map<String, Object> buildCloseSnapshot(LoanPackage pkg, List<PackageItem> allItems,
                                                   LocalDateTime closedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotType", PackageSnapshot.TYPE_CLOSE);
        snapshot.put("packageKey", pkg.packageKey());
        snapshot.put("caseKey", pkg.caseKey());
        snapshot.put("borrowerId", pkg.borrowerId());
        snapshot.put("closedAt", closedAt);
        snapshot.put("loan", Map.of(
                "loanAt", pkg.loanAt(),
                "dueAt", pkg.dueAt(),
                "purpose", pkg.purpose(),
                "items", allItems.stream().map(i -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("evidenceKey", i.evidenceKey());
                    m.put("frozenSealVersion", i.frozenSealVersion());
                    m.put("returnBatchId", i.returnBatchId());
                    m.put("returnedAt", i.returnedAt());
                    return m;
                }).toList()));
        List<ReturnBatch> batches = batchRepository.findByPackageIdOrdered(pkg.id());
        snapshot.put("returnBatches", batches.stream().map(batch -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchId", batch.id());
            m.put("receiverId", batch.receiverId());
            m.put("reviewerId", batch.reviewerId());
            m.put("returnedAt", batch.returnedAt());
            m.put("note", batch.note());
            m.put("items", chainRepository.findByBatchIdOrdered(batch.id()).stream().map(c -> {
                Map<String, Object> ci = new LinkedHashMap<>();
                ci.put("evidenceKey", c.evidenceKey());
                ci.put("sealVersion", c.sealVersion());
                return ci;
            }).toList());
            return m;
        }).toList());
        return snapshot;
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
}
