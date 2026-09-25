package com.example.starter.container;

import com.example.starter.container.dto.BlockReasonView;
import com.example.starter.container.dto.ContainerCreateRequest;
import com.example.starter.container.dto.ContainerDetailView;
import com.example.starter.container.dto.ContainerInspectRequest;
import com.example.starter.container.dto.ContainerInspectResultView;
import com.example.starter.container.dto.ContainerInspectionView;
import com.example.starter.container.dto.ContainerItemSnapshotView;
import com.example.starter.container.dto.ContainerItemsRequest;
import com.example.starter.container.dto.ContainerItemsResultView;
import com.example.starter.container.dto.ContainerReviewRequest;
import com.example.starter.container.dto.ContainerReviewResultView;
import com.example.starter.container.dto.ContainerReviewView;
import com.example.starter.container.dto.ContainerView;
import com.example.starter.container.dto.PendingVerificationView;
import com.example.starter.error.ApiException;
import com.example.starter.evidence.CommandLogRepository;
import com.example.starter.evidence.Evidence;
import com.example.starter.evidence.EvidenceRepository;
import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.StoredResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 封存容器巡检核心服务。所有写操作在单事务内完成：
 * 先锁定容器行（SELECT ... FOR UPDATE），FAIL 巡检另按证物键排序锁定每件装载证物行，
 * 再校验状态机并落库，成功后才写幂等日志（业务失败抛异常回滚，不占用命令键）。
 * 巡检、借出、迁移、归还核验与复核并发均按事务提交顺序裁决：
 * FAIL 巡检先提交则借出/迁移得到 409；借出先提交则 FAIL 巡检因证物非 SEALED 整单回滚。
 */
@Service
public class ContainerService {

    static final String OP_CONTAINER_CREATE = "CONTAINER_CREATE";
    static final String OP_CONTAINER_LOAD = "CONTAINER_LOAD";
    static final String OP_CONTAINER_UNLOAD = "CONTAINER_UNLOAD";
    static final String OP_CONTAINER_INSPECT = "CONTAINER_INSPECT";
    static final String OP_CONTAINER_REVIEW = "CONTAINER_REVIEW";

    /**
     * 解除 INSPECTION_FAILED 所需的不同复核保管人数。
     */
    static final int REQUIRED_REVIEWERS = 2;

    private final SealedContainerRepository containerRepository;
    private final ContainerItemRepository itemRepository;
    private final ContainerInspectionRepository inspectionRepository;
    private final ContainerItemSnapshotRepository snapshotRepository;
    private final ContainerReviewRepository reviewRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public ContainerService(SealedContainerRepository containerRepository,
                            ContainerItemRepository itemRepository,
                            ContainerInspectionRepository inspectionRepository,
                            ContainerItemSnapshotRepository snapshotRepository,
                            ContainerReviewRepository reviewRepository,
                            EvidenceRepository evidenceRepository,
                            CommandLogRepository commandLogRepository,
                            ObjectMapper objectMapper) {
        this.containerRepository = containerRepository;
        this.itemRepository = itemRepository;
        this.inspectionRepository = inspectionRepository;
        this.snapshotRepository = snapshotRepository;
        this.reviewRepository = reviewRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建封存容器，初始 SEALED，版本 0。
     */
    @Transactional
    public StoredResponse createContainer(String actorId, ContainerCreateRequest request,
                                          String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (containerRepository.findById(request.containerId()).isPresent()) {
            throw ApiException.conflict("容器已存在: " + request.containerId());
        }
        LocalDateTime now = LocalDateTime.now();
        containerRepository.insert(request.containerId(), request.nextInspectionAt(), now);
        SealedContainer container = containerRepository.findById(request.containerId()).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_CREATE, actorId, requestHash,
                201, toView(container));
    }

    /**
     * 装载证物：容器须 SEALED（FAIL 集合冻结）；每件证物须存在、SEALED 且未装入任何容器。
     */
    @Transactional
    public StoredResponse loadItems(String actorId, String containerId,
                                    ContainerItemsRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SealedContainer container = lockContainer(containerId);
        // 持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireSealedContainer(container);
        List<String> keys = normalizeKeys(request.evidenceKeys());
        // 按键排序锁定证物行，避免与 FAIL 巡检等事务出现死锁。
        List<Evidence> evidences = lockEvidences(keys);
        for (Evidence evidence : evidences) {
            if (evidence.status() != EvidenceStatus.SEALED) {
                throw ApiException.conflict("仅 SEALED 证物可装入容器: " + evidence.evidenceKey());
            }
            Optional<String> loaded = itemRepository.findContainerOfEvidence(evidence.evidenceKey());
            if (loaded.isPresent()) {
                throw ApiException.conflict(
                        "证物已装入容器 " + loaded.get() + ": " + evidence.evidenceKey());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (String key : keys) {
            itemRepository.insert(containerId, key, now);
        }
        containerRepository.bumpVersion(containerId, now);
        SealedContainer updated = containerRepository.findById(containerId).orElseThrow();
        ContainerItemsResultView body = new ContainerItemsResultView(toView(updated), keys);
        return record(request.commandKey(), OP_CONTAINER_LOAD, actorId, requestHash, 200, body);
    }

    /**
     * 移出证物：容器须 SEALED（FAIL 集合冻结）；每件证物当前必须在本容器中。
     */
    @Transactional
    public StoredResponse unloadItems(String actorId, String containerId,
                                      ContainerItemsRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SealedContainer container = lockContainer(containerId);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireSealedContainer(container);
        List<String> keys = normalizeKeys(request.evidenceKeys());
        List<String> current = itemRepository.findEvidenceKeys(containerId);
        for (String key : keys) {
            if (!current.contains(key)) {
                throw ApiException.notFound("证物不在容器中: " + key);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (String key : keys) {
            itemRepository.delete(containerId, key);
        }
        containerRepository.bumpVersion(containerId, now);
        SealedContainer updated = containerRepository.findById(containerId).orElseThrow();
        ContainerItemsResultView body = new ContainerItemsResultView(toView(updated), keys);
        return record(request.commandKey(), OP_CONTAINER_UNLOAD, actorId, requestHash, 200, body);
    }

    /**
     * 容器巡检。允许提前巡检；nextInspectionAt 必须严格晚于 inspectedAt；FAIL 说明非空。
     * PASS 只更新下次巡检时刻与记录，不改写历史 FAIL，也不解除 INSPECTION_FAILED；
     * FAIL 在同一事务内转容器 INSPECTION_FAILED、全部装载证物转 PENDING_VERIFICATION
     * 并写逐件快照，任一件不允许标记（非 SEALED）时整单回滚。
     */
    @Transactional
    public StoredResponse inspect(String actorId, String containerId,
                                  ContainerInspectRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        InspectionResult result = parseResult(request.result());
        if (result == InspectionResult.FAIL && (request.note() == null || request.note().isBlank())) {
            throw ApiException.badRequest("FAIL 巡检说明不能为空");
        }
        if (!request.nextInspectionAt().isAfter(request.inspectedAt())) {
            throw ApiException.badRequest("下一次巡检时刻必须严格晚于实际巡检时刻");
        }
        SealedContainer container = lockContainer(containerId);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.containerVersion() != container.version()) {
            throw ApiException.conflict("容器版本已变化，请刷新后重试: " + containerId);
        }
        List<String> itemKeys = itemRepository.findEvidenceKeys(containerId);
        // 排序锁定全部装载证物行：与借出/迁移事务按证物行锁串行裁决。
        List<Evidence> lockedItems = lockEvidences(itemKeys);

        LocalDateTime now = LocalDateTime.now();
        long inspectionId = inspectionRepository.insert(containerId, request.commandKey(), actorId,
                container.version(), request.inspectedAt(), request.nextInspectionAt(),
                result, request.note(), now);

        List<ContainerItemSnapshotView> snapshots = List.of();
        if (result == InspectionResult.FAIL) {
            if (container.status() == ContainerStatus.INSPECTION_FAILED) {
                // 已在失败待复核状态：装载证物为 PENDING_VERIFICATION，不允许再次整体标记，整单回滚。
                throw ApiException.conflict("容器已处于巡检失败待复核状态: " + containerId);
            }
            int no = 1;
            for (Evidence item : lockedItems) {
                if (item.status() != EvidenceStatus.SEALED) {
                    // 任一证物状态不允许标记（如已借出/待接收）：抛异常回滚整个事务。
                    throw ApiException.conflict(
                            "证物当前状态不允许标记为待核验: " + item.evidenceKey());
                }
            }
            for (Evidence item : lockedItems) {
                evidenceRepository.updateStatus(item.evidenceKey(),
                        EvidenceStatus.PENDING_VERIFICATION, now);
                snapshotRepository.insert(inspectionId, containerId, item.evidenceKey(),
                        EvidenceStatus.SEALED, item.custodianId(), item.sealNo(), no, now);
                no++;
            }
            if (containerRepository.markFailedWithVersion(containerId, container.version(),
                    request.nextInspectionAt(), now) != 1) {
                throw ApiException.conflict("容器已被并发事务变更: " + containerId);
            }
            snapshots = snapshotRepository.findByInspectionId(inspectionId).stream()
                    .map(this::toSnapshotView).toList();
        } else {
            // PASS 仅更新下次巡检时刻；容器若处于 INSPECTION_FAILED 也不改写状态与历史 FAIL。
            if (containerRepository.updateNextInspectionWithVersion(containerId,
                    container.version(), request.nextInspectionAt(), now) != 1) {
                throw ApiException.conflict("容器已被并发事务变更: " + containerId);
            }
        }

        ContainerInspection saved = inspectionRepository.findByInspectKey(request.commandKey())
                .orElseThrow();
        SealedContainer updated = containerRepository.findById(containerId).orElseThrow();
        ContainerInspectResultView body = new ContainerInspectResultView(
                toInspectionView(saved), toView(updated), snapshots);
        return record(request.commandKey(), OP_CONTAINER_INSPECT, actorId, requestHash,
                200, body);
    }

    /**
     * 复核封签：仅 INSPECTION_FAILED 容器；同一保管人至多一次；
     * 两名不同保管人完成后容器恢复 SEALED，仍处 PENDING_VERIFICATION 的装载证物恢复 SEALED。
     */
    @Transactional
    public StoredResponse review(String actorId, String containerId,
                                 ContainerReviewRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        SealedContainer container = lockContainer(containerId);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (container.status() != ContainerStatus.INSPECTION_FAILED) {
            throw ApiException.conflict("容器不处于巡检失败状态，无需复核: " + containerId);
        }
        if (reviewRepository.find(containerId, actorId).isPresent()) {
            throw ApiException.conflict("保管人已完成过复核，须由不同保管人复核: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        reviewRepository.insert(containerId, actorId, request.note(), now);
        int distinct = reviewRepository.countByContainerId(containerId);
        boolean restored = false;
        if (distinct >= REQUIRED_REVIEWERS) {
            // 排序锁定装载证物：仅恢复仍为 PENDING_VERIFICATION 的证物；
            // 已被单物证物核验改为其他状态的证物保持其状态。
            List<String> itemKeys = itemRepository.findEvidenceKeys(containerId);
            for (Evidence item : lockEvidences(itemKeys)) {
                evidenceRepository.updateStatusFrom(item.evidenceKey(),
                        EvidenceStatus.PENDING_VERIFICATION, EvidenceStatus.SEALED, now);
            }
            if (containerRepository.markSealedWithVersion(containerId, container.version(), now) != 1) {
                throw ApiException.conflict("容器已被并发事务变更: " + containerId);
            }
            restored = true;
        }
        SealedContainer updated = containerRepository.findById(containerId).orElseThrow();
        ContainerReviewResultView body = new ContainerReviewResultView(
                toView(updated), actorId, distinct, restored);
        return record(request.commandKey(), OP_CONTAINER_REVIEW, actorId, requestHash,
                200, body);
    }

    /**
     * 查询容器巡检详情、逐件快照与复核记录。
     */
    @Transactional(readOnly = true)
    public ContainerDetailView containerDetail(String containerId) {
        SealedContainer container = containerRepository.findById(containerId)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerId));
        List<String> items = itemRepository.findEvidenceKeys(containerId);
        List<ContainerInspectionView> inspections = inspectionRepository.findByContainerId(containerId)
                .stream().map(this::toInspectionView).toList();
        List<ContainerItemSnapshotView> snapshots = snapshotRepository.findByContainerId(containerId)
                .stream().map(this::toSnapshotView).toList();
        List<ContainerReviewView> reviews = reviewRepository.findByContainerId(containerId)
                .stream().map(this::toReviewView).toList();
        return new ContainerDetailView(toView(container), items, inspections, snapshots, reviews);
    }

    /**
     * 查询待核验证物（PENDING_VERIFICATION）；custodianId 非空时按保管人过滤。
     */
    @Transactional(readOnly = true)
    public List<PendingVerificationView> listPendingVerification(String custodianId) {
        return itemRepository.findPendingVerification(custodianId);
    }

    /**
     * 查询证物借出/迁移阻断原因（处于 INSPECTION_FAILED 容器时 blocked=true）。
     */
    @Transactional(readOnly = true)
    public BlockReasonView blockReason(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        Optional<String> containerId = itemRepository.findContainerOfEvidence(evidenceKey);
        if (containerId.isEmpty()) {
            return new BlockReasonView(evidenceKey, null, false, null, null);
        }
        SealedContainer container = containerRepository.findById(containerId.get()).orElseThrow();
        if (container.status() == ContainerStatus.INSPECTION_FAILED) {
            return new BlockReasonView(evidenceKey, container.containerId(), true,
                    "CONTAINER_INSPECTION_FAILED",
                    "容器巡检失败待双人复核封签，证物不得新借出或迁移: " + container.containerId());
        }
        return new BlockReasonView(evidenceKey, container.containerId(), false, null, null);
    }

    /**
     * 借出/迁移门禁：证物处于 INSPECTION_FAILED 容器时抛出 409。
     * 由 EvidenceService 在证物行锁内、同事务调用，按事务提交顺序裁决并发。
     */
    @Transactional(readOnly = true)
    public void assertNotBlockedByFailedContainer(String evidenceKey) {
        Optional<String> containerId = itemRepository.findContainerOfEvidence(evidenceKey);
        if (containerId.isEmpty()) {
            return;
        }
        SealedContainer container = containerRepository.findById(containerId.get()).orElseThrow();
        if (container.status() == ContainerStatus.INSPECTION_FAILED) {
            throw ApiException.conflict(
                    "容器巡检失败待双人复核封签，证物不得新借出或迁移: " + evidenceKey);
        }
    }

    private SealedContainer lockContainer(String containerId) {
        return containerRepository.findByIdForUpdate(containerId)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerId));
    }

    private void requireSealedContainer(SealedContainer container) {
        if (container.status() == ContainerStatus.INSPECTION_FAILED) {
            throw ApiException.conflict("巡检失败容器集合冻结，不得变更: " + container.containerId());
        }
    }

    private List<Evidence> lockEvidences(List<String> keys) {
        return keys.stream()
                .sorted()
                .map(key -> evidenceRepository.findByKeyForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("证物不存在: " + key)))
                .toList();
    }

    /**
     * 集合参数规范化：去重并按字典序排序；因此集合换序视为同参。
     */
    private List<String> normalizeKeys(List<String> keys) {
        return keys.stream().distinct().sorted().toList();
    }

    private InspectionResult parseResult(String result) {
        try {
            return InspectionResult.valueOf(result);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("巡检结果只能为 PASS 或 FAIL: " + result);
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

    private ContainerView toView(SealedContainer container) {
        return new ContainerView(container.containerId(), container.status().name(),
                container.nextInspectionAt(), container.version(),
                container.createdAt(), container.updatedAt());
    }

    private ContainerInspectionView toInspectionView(ContainerInspection inspection) {
        return new ContainerInspectionView(inspection.id(), inspection.containerId(),
                inspection.inspectKey(), inspection.inspectorId(), inspection.containerVersion(),
                inspection.inspectedAt(), inspection.nextInspectionAt(),
                inspection.result().name(), inspection.note(), inspection.createdAt());
    }

    private ContainerItemSnapshotView toSnapshotView(ContainerItemSnapshot snapshot) {
        return new ContainerItemSnapshotView(snapshot.inspectionId(), snapshot.containerId(),
                snapshot.evidenceKey(), snapshot.evidenceStatus().name(), snapshot.custodianId(),
                snapshot.sealNo(), snapshot.snapshotNo());
    }

    private ContainerReviewView toReviewView(ContainerReview review) {
        return new ContainerReviewView(review.containerId(), review.custodianId(),
                review.note(), review.createdAt());
    }
}
