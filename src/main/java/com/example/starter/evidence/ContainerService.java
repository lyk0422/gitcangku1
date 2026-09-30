package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerDetailView;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerInspectionView;
import com.example.starter.evidence.dto.ContainerIsolationView;
import com.example.starter.evidence.dto.ContainerPlaceRequest;
import com.example.starter.evidence.dto.ContainerReviewRequest;
import com.example.starter.evidence.dto.ContainerView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 容器巡检与隔离核心服务。
 * 锁顺序约定：先按字典序锁定涉及的容器行（SELECT ... FOR UPDATE），再锁定证物行，
 * 保证巡检确认与证物放入/移出竞争时按事务提交顺序生效，转移与隔离不会分别落到不同位置。
 * 巡检以 requestId 为幂等键：同键同参重放首次结果（含首次隔离清单），同键换封条结果或版本返回 409。
 */
@Service
public class ContainerService {

    static final String OP_CONTAINER_CREATE = "CONTAINER_CREATE";
    static final String OP_CONTAINER_PLACE = "CONTAINER_PLACE";
    static final String OP_CONTAINER_REMOVE = "CONTAINER_REMOVE";
    static final String OP_CONTAINER_REVIEW = "CONTAINER_REVIEW";

    private final ContainerRepository containerRepository;
    private final ContainerInspectionRepository inspectionRepository;
    private final ContainerIsolationRepository isolationRepository;
    private final EvidencePlacementRepository placementRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ContainerService(ContainerRepository containerRepository,
                            ContainerInspectionRepository inspectionRepository,
                            ContainerIsolationRepository isolationRepository,
                            EvidencePlacementRepository placementRepository,
                            EvidenceRepository evidenceRepository,
                            CommandLogRepository commandLogRepository,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.containerRepository = containerRepository;
        this.inspectionRepository = inspectionRepository;
        this.isolationRepository = isolationRepository;
        this.placementRepository = placementRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 创建容器：初始版本 0、状态 NORMAL。containerKey 全局唯一。
     */
    @Transactional
    public StoredResponse create(String actorId, ContainerCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (containerRepository.findByKey(request.containerKey()).isPresent()) {
            throw ApiException.conflict("容器已存在: " + request.containerKey());
        }
        LocalDateTime now = LocalDateTime.now();
        containerRepository.insert(request.containerKey(), request.location(), now);
        Container container = containerRepository.findByKey(request.containerKey()).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_CREATE, actorId, requestHash,
                201, toView(container, List.of()));
    }

    /**
     * 证物放入容器（含从源容器转移到目标容器）：仅当前保管人，证物须为 SEALED；
     * 源容器与目标容器版本均自增。异常容器复核前禁止放入或移出。
     */
    @Transactional
    public StoredResponse place(String actorId, String containerKey,
                                ContainerPlaceRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence probe = evidenceRepository.findByKey(request.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + request.evidenceKey()));
        Map<String, Container> locked = lockContainers(probe.containerKey(), containerKey);
        Container target = locked.get(containerKey);
        Evidence evidence = evidenceRepository.findByKeyForUpdate(request.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + request.evidenceKey()));
        // 并发下本事务可能在容器/证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 锁定证物行后复查所在容器：不在已锁定集合内说明并发移动已提交，拒绝本次以保证锁顺序
        if (evidence.containerKey() != null && !locked.containsKey(evidence.containerKey())) {
            throw ApiException.conflict("证物所在容器发生并发变化，请重试: " + request.evidenceKey());
        }
        requireCustodian(evidence, actorId);
        if (evidence.status() == EvidenceStatus.QUARANTINED) {
            throw ApiException.conflict("证物隔离待复核，禁止移动: " + request.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止放入容器: " + request.evidenceKey());
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("仅已封存证物可放入容器: " + request.evidenceKey());
        }
        if (target.status() == ContainerStatus.ABNORMAL) {
            throw ApiException.conflict("容器封条异常待复核，禁止放入: " + containerKey);
        }
        if (containerKey.equals(evidence.containerKey())) {
            throw ApiException.conflict("证物已在该容器内: " + request.evidenceKey());
        }
        Container source = evidence.containerKey() == null ? null : locked.get(evidence.containerKey());
        if (source != null && source.status() == ContainerStatus.ABNORMAL) {
            throw ApiException.conflict("容器封条异常待复核，复核前禁止移出: " + source.containerKey());
        }
        LocalDateTime now = LocalDateTime.now();
        if (source != null) {
            placementRepository.closeActive(request.evidenceKey(), now);
            containerRepository.bumpVersion(source.containerKey(), now);
        }
        evidenceRepository.updateContainer(request.evidenceKey(), containerKey, now);
        placementRepository.insert(request.evidenceKey(), containerKey, target.location(), now);
        containerRepository.bumpVersion(containerKey, now);
        Container updated = containerRepository.findByKey(containerKey).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_PLACE, actorId, requestHash,
                200, toView(updated, currentItems(containerKey)));
    }

    /**
     * 证物移出容器：仅当前保管人，证物须在该容器内；容器版本自增。
     * 异常容器复核前禁止移出。
     */
    @Transactional
    public StoredResponse remove(String actorId, String containerKey,
                                 ContainerPlaceRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Container container = containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        Evidence evidence = evidenceRepository.findByKeyForUpdate(request.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + request.evidenceKey()));
        // 并发下本事务可能在容器/证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireCustodian(evidence, actorId);
        if (container.status() == ContainerStatus.ABNORMAL) {
            throw ApiException.conflict("容器封条异常待复核，复核前禁止移出: " + containerKey);
        }
        if (!containerKey.equals(evidence.containerKey())) {
            throw ApiException.conflict("证物不在该容器内: " + request.evidenceKey());
        }
        LocalDateTime now = LocalDateTime.now();
        placementRepository.closeActive(request.evidenceKey(), now);
        evidenceRepository.updateContainer(request.evidenceKey(), null, now);
        containerRepository.bumpVersion(containerKey, now);
        Container updated = containerRepository.findByKey(containerKey).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_REMOVE, actorId, requestHash,
                200, toView(updated, currentItems(containerKey)));
    }

    /**
     * 容器巡检确认（非事务入口）：两名不同保管人以同一 requestId 先后确认同一容器版本。
     * 首次提交落 PENDING 记录（不改变证物状态）；第二人确认时若见证版本仍等于容器当前版本，
     * 封条异常则将当时箱内全部证物一次转为隔离待复核并保留原位置快照。
     * 并发首次提交同 requestId 触发唯一约束时，回滚后重走已存在分支（重放或作为第二人确认）。
     */
    public StoredResponse submitInspection(String actorId, String containerKey,
                                           ContainerInspectionRequest request) {
        try {
            return transactionTemplate.execute(tx -> doSubmitInspection(actorId, containerKey, request));
        } catch (DuplicateKeyException e) {
            return transactionTemplate.execute(tx -> doSubmitInspection(actorId, containerKey, request));
        }
    }

    private StoredResponse doSubmitInspection(String actorId, String containerKey,
                                              ContainerInspectionRequest request) {
        Container container = containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        Optional<ContainerInspection> existing = inspectionRepository.findByRequestId(request.requestId());
        LocalDateTime now = LocalDateTime.now();
        if (existing.isEmpty()) {
            ContainerInspectionView view = new ContainerInspectionView(request.requestId(), containerKey,
                    request.containerVersion(), request.sealIntact(), InspectionStatus.PENDING,
                    actorId, null, null, List.of(), now);
            String body = toJson(view);
            inspectionRepository.insert(request.requestId(), containerKey, request.containerVersion(),
                    request.sealIntact(), actorId, 201, body, now);
            return new StoredResponse(201, body);
        }
        ContainerInspection inspection = existing.get();
        if (!inspection.containerKey().equals(containerKey)) {
            throw ApiException.conflict("巡检请求键已用于其他容器: " + request.requestId());
        }
        if (inspection.sealIntact() != request.sealIntact()) {
            throw ApiException.conflict("同一巡检请求封条结果不一致: " + request.requestId());
        }
        if (inspection.containerVersion() != request.containerVersion()) {
            throw ApiException.conflict("巡检版本不一致，不改变证物状态: " + request.requestId());
        }
        if (inspection.status() == InspectionStatus.CONFIRMED
                || inspection.firstInspector().equals(actorId)) {
            // 已完成或本人重复提交：重放首次结果（含首次隔离清单）
            return new StoredResponse(inspection.responseStatus(), inspection.responseBody());
        }
        // 第二人确认：见证版本必须仍等于容器当前版本，否则巡检过期，不改变证物状态
        if (inspection.containerVersion() != container.version()) {
            throw ApiException.conflict("容器版本已变化，巡检版本不一致: " + containerKey);
        }
        List<ContainerIsolation> isolations = List.of();
        if (!request.sealIntact()) {
            containerRepository.updateStatus(containerKey, ContainerStatus.ABNORMAL, now);
            for (Evidence item : evidenceRepository.findByContainerKey(containerKey)) {
                evidenceRepository.updateStatus(item.evidenceKey(), EvidenceStatus.QUARANTINED, now);
                isolationRepository.insert(inspection.id(), item.evidenceKey(), containerKey,
                        container.location(), now);
            }
            isolations = isolationRepository.findByInspectionId(inspection.id());
        }
        ContainerInspectionView view = new ContainerInspectionView(inspection.requestId(), containerKey,
                inspection.containerVersion(), inspection.sealIntact(), InspectionStatus.CONFIRMED,
                inspection.firstInspector(), actorId, now,
                isolations.stream().map(this::toView).toList(), inspection.createdAt());
        String body = toJson(view);
        if (!inspectionRepository.confirm(inspection.id(), actorId, now, 200, body)) {
            // 容器行锁已串行化确认，理论上不可达；防御性回滚避免隔离落库而确认记录缺失
            throw ApiException.conflict("巡检已被并发确认: " + request.requestId());
        }
        return new StoredResponse(200, body);
    }

    /**
     * 复核放行：仅异常容器可复核；箱内全部待复核隔离记录放行、证物回到 SEALED、
     * 容器回到 NORMAL。巡检、隔离历史与带时刻的复核结论保留，位置轨迹不改写。
     */
    @Transactional
    public StoredResponse review(String actorId, String containerKey,
                                 ContainerReviewRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Container container = containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        // 并发下本事务可能在容器行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (container.status() != ContainerStatus.ABNORMAL) {
            throw ApiException.conflict("容器未处于封条异常待复核状态: " + containerKey);
        }
        LocalDateTime now = LocalDateTime.now();
        for (ContainerIsolation isolation : isolationRepository.findOpenByContainerKey(containerKey)) {
            if (!isolationRepository.release(isolation.id(), actorId, request.note(), now)) {
                throw ApiException.conflict("隔离记录已被并发复核: " + isolation.evidenceKey());
            }
            if (!evidenceRepository.releaseQuarantine(isolation.evidenceKey(), now)) {
                throw ApiException.conflict("证物未处于隔离待复核状态: " + isolation.evidenceKey());
            }
        }
        containerRepository.updateStatus(containerKey, ContainerStatus.NORMAL, now);
        return record(request.commandKey(), OP_CONTAINER_REVIEW, actorId, requestHash,
                200, detail(containerKey));
    }

    /**
     * 容器详情：当前状态与当前箱内清单 + 全部巡检记录（巡检人、封条结果、见证版本、
     * 确认时箱内隔离清单及每件隔离状态）。复核放行后历史保留。
     */
    @Transactional(readOnly = true)
    public ContainerDetailView detail(String containerKey) {
        Container container = containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
        List<ContainerInspectionView> inspections = inspectionRepository.findByContainerKey(containerKey)
                .stream()
                .map(inspection -> toView(inspection, isolationRepository.findByInspectionId(inspection.id())))
                .toList();
        return new ContainerDetailView(toView(container, currentItems(containerKey)), inspections);
    }

    private Map<String, Container> lockContainers(String sourceKey, String targetKey) {
        // 固定字典序锁定源容器与目标容器，避免与巡检确认或并发移动形成循环等待
        List<String> lockOrder = new ArrayList<>();
        if (sourceKey != null) {
            lockOrder.add(sourceKey);
        }
        if (!targetKey.equals(sourceKey)) {
            lockOrder.add(targetKey);
        }
        Collections.sort(lockOrder);
        Map<String, Container> locked = new HashMap<>();
        for (String key : lockOrder) {
            Container container = containerRepository.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("容器不存在: " + key));
            locked.put(key, container);
        }
        return locked;
    }

    private List<String> currentItems(String containerKey) {
        return evidenceRepository.findByContainerKey(containerKey).stream()
                .map(Evidence::evidenceKey)
                .toList();
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
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

    private ContainerView toView(Container container, List<String> currentItems) {
        return new ContainerView(container.containerKey(), container.location(), container.version(),
                container.status(), currentItems, container.createdAt(), container.updatedAt());
    }

    private ContainerInspectionView toView(ContainerInspection inspection,
                                           List<ContainerIsolation> isolations) {
        return new ContainerInspectionView(inspection.requestId(), inspection.containerKey(),
                inspection.containerVersion(), inspection.sealIntact(), inspection.status(),
                inspection.firstInspector(), inspection.secondInspector(), inspection.confirmedAt(),
                isolations.stream().map(this::toView).toList(), inspection.createdAt());
    }

    private ContainerIsolationView toView(ContainerIsolation isolation) {
        return new ContainerIsolationView(isolation.evidenceKey(), isolation.containerKey(),
                isolation.originalLocation(), isolation.status(), isolation.reviewNote(),
                isolation.reviewedBy(), isolation.reviewedAt(), isolation.createdAt());
    }
}
