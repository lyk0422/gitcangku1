package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerInspectionView;
import com.example.starter.evidence.dto.ContainerLoadRequest;
import com.example.starter.evidence.dto.ContainerReviewRequest;
import com.example.starter.evidence.dto.ContainerUnloadRequest;
import com.example.starter.evidence.dto.ContainerView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.LoanEligibility;
import com.example.starter.evidence.dto.ReviewResultView;
import com.example.starter.evidence.dto.ReviewView;
import com.example.starter.evidence.dto.SnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 封存容器巡检核心服务。容器行锁（SELECT ... FOR UPDATE）是巡检、复核、装载并发的串行点；
 * 证物行按业务键排序后加锁，避免多事务逆序加锁死锁。
 * FAIL 巡检在单事务内完成容器转态、逐件快照与批量待核验标记，任一证物不可标记则整单回滚。
 */
@Service
public class ContainerService {

    static final String OP_CONTAINER_CREATE = "CONTAINER_CREATE";
    static final String OP_CONTAINER_LOAD = "CONTAINER_LOAD";
    static final String OP_CONTAINER_UNLOAD = "CONTAINER_UNLOAD";
    static final String OP_CONTAINER_INSPECT = "CONTAINER_INSPECT";
    static final String OP_CONTAINER_REVIEW = "CONTAINER_REVIEW";

    private final SealedContainerRepository containerRepository;
    private final ContainerInspectionRepository inspectionRepository;
    private final ContainerInspectionSnapshotRepository snapshotRepository;
    private final ContainerReviewRepository reviewRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public ContainerService(SealedContainerRepository containerRepository,
                            ContainerInspectionRepository inspectionRepository,
                            ContainerInspectionSnapshotRepository snapshotRepository,
                            ContainerReviewRepository reviewRepository,
                            EvidenceRepository evidenceRepository,
                            CommandLogRepository commandLogRepository,
                            ObjectMapper objectMapper,
                            EvidenceClock clock) {
        this.containerRepository = containerRepository;
        this.inspectionRepository = inspectionRepository;
        this.snapshotRepository = snapshotRepository;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建封存容器：初始 SEALED、版本 0，下次巡检时刻须晚于当前 UTC 时刻。
     */
    @Transactional
    public StoredResponse createContainer(String actorId, ContainerCreateRequest request,
                                          String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (containerRepository.findByKey(request.containerKey()).isPresent()) {
            throw ApiException.conflict("容器已存在: " + request.containerKey());
        }
        if (!request.nextInspectionAt().isAfter(clock.nowUtc())) {
            throw ApiException.badRequest("下次巡检时刻须晚于服务端当前时刻");
        }
        LocalDateTime now = LocalDateTime.now();
        containerRepository.insert(request.containerKey(), actorId, request.nextInspectionAt(), now);
        SealedContainer container = containerRepository.findByKey(request.containerKey()).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_CREATE, actorId, requestHash,
                201, toContainerView(container));
    }

    /**
     * 覆盖式装载：容器内集合最终等于去重排序后的 evidenceKeys。
     * 仅 SEALED 容器可变更集合；每件证物锁定后校验：须存在、SEALED，
     * 且不属于另一个 INSPECTION_FAILED 容器（失败容器内证物禁止迁移）。
     * 集合换序视为同参（控制器对排序集合计算指纹）。
     */
    @Transactional
    public StoredResponse load(String actorId, String containerKey, ContainerLoadRequest request,
                               String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SealedContainer container = lockContainer(containerKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireContainerCustodian(container, actorId);
        requireContainerSealed(container);

        Set<String> targetKeys = new TreeSet<>(request.evidenceKeys());
        Set<String> currentKeys = new TreeSet<>(evidenceRepository.findByContainer(containerKey)
                .stream().map(Evidence::evidenceKey).toList());

        // 移出项（当前在容器、目标集合不含）与新装入项都要逐件锁定，按证物键统一排序。
        Set<String> removedKeys = new TreeSet<>(currentKeys);
        removedKeys.removeAll(targetKeys);
        Set<String> addedKeys = new TreeSet<>(targetKeys);
        addedKeys.removeAll(currentKeys);
        Set<String> touchedKeys = new TreeSet<>();
        touchedKeys.addAll(removedKeys);
        touchedKeys.addAll(addedKeys);

        LocalDateTime now = LocalDateTime.now();
        for (String evidenceKey : touchedKeys) {
            Evidence evidence = lockEvidence(evidenceKey);
            if (removedKeys.contains(evidenceKey)) {
                // 本容器为 SEALED，其内部证物必为 SEALED，直接移出。
                evidenceRepository.updateContainer(evidenceKey, null, now);
                continue;
            }
            requireLoadable(containerKey, evidence);
            evidenceRepository.updateContainer(evidenceKey, containerKey, now);
        }

        SealedContainer updated = containerRepository.findByKey(containerKey).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_LOAD, actorId, requestHash,
                200, toContainerView(updated));
    }

    /**
     * 移出证物：仅 SEALED 容器；每件证物须当前属于该容器。
     */
    @Transactional
    public StoredResponse unload(String actorId, String containerKey, ContainerUnloadRequest request,
                                 String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SealedContainer container = lockContainer(containerKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireContainerCustodian(container, actorId);
        requireContainerSealed(container);

        Set<String> targetKeys = new TreeSet<>(request.evidenceKeys());
        LocalDateTime now = LocalDateTime.now();
        for (String evidenceKey : targetKeys) {
            Evidence evidence = lockEvidence(evidenceKey);
            if (!containerKey.equals(evidence.containerKey())) {
                throw ApiException.conflict("证物不在该容器中，无法移出: " + evidenceKey);
            }
            evidenceRepository.updateContainer(evidenceKey, null, now);
        }

        SealedContainer updated = containerRepository.findByKey(containerKey).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_UNLOAD, actorId, requestHash,
                200, toContainerView(updated));
    }

    /**
     * 容器巡检：允许提前巡检；下一次巡检时刻必须严格晚于实际时刻；FAIL 说明非空。
     * PASS 仅推进下次巡检时刻与版本并追加记录；FAIL 在单事务内转 INSPECTION_FAILED、
     * 逐件写不可变快照并将全部 SEALED 装载证物标记为 PENDING_VERIFICATION，
     * 任一证物状态不允许标记（借出中/待接收/封条异常/待核验）时整单回滚，失败不占幂等键。
     * 指纹含检查人、容器版本、实际时刻、结果和说明；同键同参重放首次完整结果。
     */
    @Transactional
    public StoredResponse inspect(String actorId, String containerKey,
                                  ContainerInspectionRequest request) {
        SealedContainer container = lockContainer(containerKey);
        Optional<StoredResponse> replay = commandLogRepository.findByKey(request.commandKey())
                .map(log -> resolveInspectReplay(log, request, actorId));
        if (replay.isPresent()) {
            return replay.get();
        }
        requireContainerCustodian(container, actorId);
        SealResult result = parseResult(request.result());
        if (!request.nextInspectionAt().isAfter(request.inspectedAt())) {
            throw ApiException.badRequest("下一次巡检时刻必须严格晚于实际巡检时刻");
        }
        if (result == SealResult.FAIL && (request.note() == null || request.note().isBlank())) {
            throw ApiException.badRequest("FAIL 巡检说明不能为空");
        }
        if (container.status() == ContainerStatus.INSPECTION_FAILED) {
            throw ApiException.conflict("容器巡检失败待双人复核，禁止再次巡检: " + containerKey);
        }

        // 指纹在持锁后按容器当前版本计算并落库；同键重放按检查人/实际时刻/结果/说明语义字段裁决。
        long versionBefore = container.version();
        String fingerprint = inspectionFingerprint(actorId, containerKey, versionBefore,
                request.inspectedAt(), result, request.note());
        LocalDateTime now = LocalDateTime.now();
        long inspectionId = inspectionRepository.insert(containerKey, actorId, result,
                request.note(), request.inspectedAt(), versionBefore, now);

        List<Evidence> members = evidenceRepository.findByContainerForUpdate(containerKey);
        if (result == SealResult.FAIL) {
            for (Evidence member : members) {
                if (member.status() != EvidenceStatus.SEALED) {
                    // 抛出后整个事务回滚：巡检记录、快照、容器转态全部不生效，幂等键不被占用。
                    throw ApiException.conflict("容器内存在不允许标记待核验的证物，整单回滚: "
                            + member.evidenceKey());
                }
            }
            for (Evidence member : members) {
                snapshotRepository.insert(inspectionId, containerKey, member, now);
                evidenceRepository.updateStatus(member.evidenceKey(),
                        EvidenceStatus.PENDING_VERIFICATION, now);
            }
            containerRepository.markFailed(containerKey, request.nextInspectionAt(), now);
        } else {
            containerRepository.advanceInspection(containerKey, request.nextInspectionAt(), now);
        }

        ContainerInspection saved = inspectionRepository.findById(inspectionId);
        List<SnapshotView> snapshots = result == SealResult.FAIL
                ? snapshotRepository.findByInspection(inspectionId).stream()
                .map(this::toSnapshotView).toList()
                : List.of();
        return record(request.commandKey(), OP_CONTAINER_INSPECT, actorId, fingerprint,
                200, toInspectionView(saved, snapshots));
    }

    /**
     * 双人复核封签：复核人必须与 FAIL 检查人不同，同一人不可重复复核；
     * 第二名不同保管人复核提交的同一事务内恢复容器 SEALED 并将待核验证物批量恢复 SEALED。
     */
    @Transactional
    public StoredResponse review(String actorId, String containerKey, ContainerReviewRequest request,
                                 String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SealedContainer container = lockContainer(containerKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (container.status() != ContainerStatus.INSPECTION_FAILED) {
            throw ApiException.conflict("容器不处于巡检失败状态，无需复核: " + containerKey);
        }
        ContainerInspection latestFail = inspectionRepository.findLatestFail(containerKey);
        if (actorId.equals(latestFail.inspectorId())) {
            throw ApiException.conflict("复核保管人不能与 FAIL 巡检检查人相同: " + actorId);
        }
        boolean alreadyReviewed = reviewRepository.findByContainer(containerKey).stream()
                .filter(r -> r.inspectorId().equals(latestFail.inspectorId()))
                .anyMatch(r -> r.reviewerId().equals(actorId));
        if (alreadyReviewed) {
            throw ApiException.conflict("该保管人已完成本次复核: " + actorId);
        }

        LocalDateTime now = LocalDateTime.now();
        reviewRepository.insert(containerKey, latestFail.inspectorId(), actorId, request.note(),
                request.reviewedAt(), now);

        int reviewerCount = reviewRepository.countDistinctReviewers(
                containerKey, latestFail.inspectorId());
        boolean restored = false;
        if (reviewerCount >= 2) {
            List<Evidence> members = evidenceRepository.findByContainerForUpdate(containerKey);
            for (Evidence member : members) {
                boolean cas = evidenceRepository.compareAndUpdateStatus(member.evidenceKey(),
                        EvidenceStatus.PENDING_VERIFICATION, EvidenceStatus.SEALED, now);
                if (!cas) {
                    throw ApiException.conflict(
                            "待核验证物状态已被并发改变，复核恢复回滚: " + member.evidenceKey());
                }
            }
            if (!containerRepository.markSealed(containerKey, now)) {
                throw ApiException.conflict("容器已被并发复核恢复: " + containerKey);
            }
            restored = true;
        }

        SealedContainer updated = containerRepository.findByKey(containerKey).orElseThrow();
        List<ReviewView> reviews = reviewRepository.findByContainer(containerKey).stream()
                .map(this::toReviewView).toList();
        return record(request.commandKey(), OP_CONTAINER_REVIEW, actorId, requestHash,
                200, new ReviewResultView(toContainerView(updated), reviews, restored,
                        reviewerCount));
    }

    /**
     * 查询容器当前状态与装载证物键集合。
     */
    @Transactional(readOnly = true)
    public ContainerView getContainer(String containerKey) {
        SealedContainer container = requireContainer(containerKey);
        return toContainerView(container);
    }

    /**
     * 查询容器全部巡检记录及逐件不可变快照（按发生顺序）。
     */
    @Transactional(readOnly = true)
    public List<ContainerInspectionView> listInspections(String containerKey) {
        requireContainer(containerKey);
        return inspectionRepository.findByContainer(containerKey).stream()
                .map(inspection -> {
                    List<SnapshotView> snapshots = snapshotRepository
                            .findByInspection(inspection.id()).stream()
                            .map(this::toSnapshotView).toList();
                    return toInspectionView(inspection, snapshots);
                })
                .toList();
    }

    /**
     * 查询容器内待核验证物（FAIL 巡检后 PENDING_VERIFICATION）。
     */
    @Transactional(readOnly = true)
    public List<EvidenceView> listPendingVerification(String containerKey) {
        requireContainer(containerKey);
        return evidenceRepository.findByContainer(containerKey).stream()
                .filter(e -> e.status() == EvidenceStatus.PENDING_VERIFICATION)
                .map(this::toEvidenceView)
                .toList();
    }

    /**
     * 查询容器全部复核记录。
     */
    @Transactional(readOnly = true)
    public List<ReviewView> listReviews(String containerKey) {
        requireContainer(containerKey);
        return reviewRepository.findByContainer(containerKey).stream()
                .map(this::toReviewView).toList();
    }

    /**
     * 借出门禁判定：返回是否可借出及阻断原因列表（容器 FAIL、证物状态等），供查询与借出 409 复用。
     */
    @Transactional(readOnly = true)
    public LoanEligibility loanEligibility(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<String> reasons = collectLoanBlockReasons(evidence);
        return new LoanEligibility(evidenceKey, !reasons.isEmpty(), reasons);
    }

    /**
     * 借出门禁校验：证物处于待核验或其容器 INSPECTION_FAILED 时抛 409。
     * 调用方必须已锁定证物行；此处对容器状态做最新读以裁决与巡检的并发提交顺序。
     */
    public void requireLoanAllowed(Evidence evidence) {
        gateByContainer(evidence, "新借出");
    }

    /**
     * 迁移（发起交接）门禁：证物处于待核验或其容器 INSPECTION_FAILED 时抛 409。
     */
    public void requireTransferAllowed(Evidence evidence) {
        gateByContainer(evidence, "迁移");
    }

    private void gateByContainer(Evidence evidence, String action) {
        List<String> reasons = collectLoanBlockReasons(evidence);
        if (!reasons.isEmpty()) {
            throw ApiException.conflict("容器巡检失败持续阻断" + action + ": "
                    + String.join("; ", reasons));
        }
    }

    private List<String> collectLoanBlockReasons(Evidence evidence) {
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        if (evidence.status() == EvidenceStatus.PENDING_VERIFICATION) {
            reasons.add("证物所在容器巡检失败，待两名不同保管人复核封签: " + evidence.containerKey());
        }
        if (evidence.containerKey() != null) {
            containerRepository.findByKey(evidence.containerKey()).ifPresent(container -> {
                if (container.status() == ContainerStatus.INSPECTION_FAILED) {
                    reasons.add("容器处于 INSPECTION_FAILED，冻结借出门禁: " + container.containerKey());
                }
            });
        }
        return List.copyOf(reasons);
    }

    /**
     * inspectKey 指纹：检查人、容器版本、实际时刻、结果和说明的 SHA-256（另含操作与容器键定界）。
     */
    public String inspectionFingerprint(String inspectorId, String containerKey, long version,
                                        LocalDateTime inspectedAt, SealResult result, String note) {
        try {
            String canonical = String.join("\n",
                    OP_CONTAINER_INSPECT,
                    inspectorId == null ? "" : inspectorId,
                    containerKey == null ? "" : containerKey,
                    Long.toString(version),
                    inspectedAt.toString(),
                    result.name(),
                    note == null ? "" : note);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("巡检指纹计算失败", e);
        }
    }

    /**
     * 并发同 inspectKey 竞态时的语义重放：日志先提交，其指纹含先提交时的容器版本，
     * 与等待事务在持锁后算出的指纹不同，故按检查人/实际时刻/结果/说明语义字段比对。
     */
    private StoredResponse resolveInspectReplay(CommandLogRepository.CommandLog log,
                                                ContainerInspectionRequest request,
                                                String actorId) {
        if (!log.operation().equals(OP_CONTAINER_INSPECT)) {
            throw ApiException.conflict("幂等键已被不同操作使用: " + request.commandKey());
        }
        try {
            ContainerInspectionView view = objectMapper.readValue(log.responseBody(),
                    ContainerInspectionView.class);
            boolean sameSemantic = view.inspectorId().equals(actorId)
                    && view.inspectedAt().equals(request.inspectedAt())
                    && view.result().name().equals(request.result())
                    && java.util.Objects.equals(view.note(), request.note());
            if (!sameSemantic) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + request.commandKey());
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.conflict("幂等键已被不同参数使用: " + request.commandKey());
        }
    }

    private SealedContainer lockContainer(String containerKey) {
        return containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
    }

    private SealedContainer requireContainer(String containerKey) {
        return containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireContainerCustodian(SealedContainer container, String actorId) {
        if (!container.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是容器负责人: " + actorId);
        }
    }

    private void requireContainerSealed(SealedContainer container) {
        if (container.status() == ContainerStatus.INSPECTION_FAILED) {
            throw ApiException.conflict("容器巡检失败待双人复核，禁止变更装载集合: "
                    + container.containerKey());
        }
    }

    private void requireLoadable(String targetContainerKey, Evidence evidence) {
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("证物当前状态不允许装入容器: " + evidence.evidenceKey());
        }
        if (evidence.containerKey() != null
                && containerRepository.findByKey(evidence.containerKey())
                .filter(c -> c.status() == ContainerStatus.INSPECTION_FAILED).isPresent()) {
            throw ApiException.conflict("证物所在容器巡检失败，禁止迁移: " + evidence.evidenceKey());
        }
    }

    private SealResult parseResult(String result) {
        try {
            return SealResult.valueOf(result);
        } catch (Exception e) {
            throw ApiException.badRequest("巡检结果只能为 PASS 或 FAIL");
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

    private ContainerView toContainerView(SealedContainer container) {
        List<String> keys = evidenceRepository.findByContainer(container.containerKey()).stream()
                .map(Evidence::evidenceKey)
                .sorted()
                .toList();
        return new ContainerView(container.containerKey(), container.custodianId(),
                container.status(), container.nextInspectionAt(), container.version(), keys,
                container.createdAt(), container.updatedAt());
    }

    private ContainerInspectionView toInspectionView(ContainerInspection inspection,
                                                     List<SnapshotView> snapshots) {
        return new ContainerInspectionView(inspection.id(), inspection.containerKey(),
                inspection.inspectorId(), inspection.result(), inspection.note(),
                inspection.inspectedAt(), inspection.containerVersion(), inspection.createdAt(),
                snapshots);
    }

    private SnapshotView toSnapshotView(ContainerInspectionSnapshot snapshot) {
        return new SnapshotView(snapshot.inspectionId(), snapshot.containerKey(),
                snapshot.evidenceKey(), snapshot.evidenceStatus(), snapshot.custodianId());
    }

    private ReviewView toReviewView(ContainerReview review) {
        return new ReviewView(review.containerKey(), review.inspectorId(), review.reviewerId(),
                review.note(), review.reviewedAt(), review.createdAt());
    }

    private EvidenceView toEvidenceView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.status(),
                evidence.containerKey(), evidence.createdAt(), evidence.updatedAt());
    }
}
