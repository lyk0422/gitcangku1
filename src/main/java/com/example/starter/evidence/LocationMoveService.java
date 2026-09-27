package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.error.MovePreconditionException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.LocationInventoryView;
import com.example.starter.evidence.dto.LocationView;
import com.example.starter.evidence.dto.MoveApplyRequest;
import com.example.starter.evidence.dto.MoveItemFailure;
import com.example.starter.evidence.dto.MoveOrderView;
import com.example.starter.evidence.dto.MoveSealSnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 证物位置封签（库位迁移）核心服务。
 *
 * 双人确认制：首人确认（申请）创建 AWAITING_SECOND 迁移单；首人确认后、第二人确认前可撤销；
 * 第二人确认在单事务内按统一顺序（迁移单行 → 全部证物行（按键升序）→ 目标库位行）加锁，
 * 复查完整集合前置条件后，同时更新全部证物库位、写入不可变双人迁移记录与逐件封签快照，
 * 任一不满足整单回滚。
 *
 * 前置失败（证物不在库、封条异常、库位不符、版本不符）返回 422 并逐项说明；
 * 状态冲突（目标库位停用、证物借出/待核验、单据已撤销/已完成、两名确认人相同）返回 409。
 */
@Service
public class LocationMoveService {

    private final StorageLocationRepository storageLocationRepository;
    private final LocationMoveOrderRepository moveOrderRepository;
    private final LocationMoveSealSnapshotRepository snapshotRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public LocationMoveService(StorageLocationRepository storageLocationRepository,
                               LocationMoveOrderRepository moveOrderRepository,
                               LocationMoveSealSnapshotRepository snapshotRepository,
                               EvidenceRepository evidenceRepository,
                               CommandLogRepository commandLogRepository,
                               ObjectMapper objectMapper) {
        this.storageLocationRepository = storageLocationRepository;
        this.moveOrderRepository = moveOrderRepository;
        this.snapshotRepository = snapshotRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 首人确认（迁移申请）：校验全部证物满足前置条件后创建待第二人确认迁移单。
     * moveKey 即命令键；同键同参（集合换序视为同参）重放：迁移已完成时返回完整迁移结果（含快照）。
     */
    @Transactional
    public StoredResponse applyMove(String actorId, MoveApplyRequest request, String requestHash) {
        StoredResponse replay = resolveApplyReplay(request.commandKey(), requestHash);
        if (replay != null) {
            return replay;
        }
        if (request.evidenceKeys().stream().anyMatch(k -> k == null || k.isBlank())) {
            throw ApiException.badRequest("证物集合包含空键");
        }
        List<String> keys = normalizeKeys(request.evidenceKeys());
        if (request.sourceLocationCode().equals(request.targetLocationCode())) {
            throw ApiException.badRequest("源库位与目标库位不能相同");
        }
        // 先锁目标库位，再锁证物行（按键升序），与第二人确认的加锁顺序保持一致。
        StorageLocation target = requireActiveTarget(request.targetLocationCode());
        requireSourceLocation(request.sourceLocationCode());
        List<Evidence> evidences = evidenceRepository.findByKeysForUpdateOrdered(keys);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志。
        replay = resolveApplyReplay(request.commandKey(), requestHash);
        if (replay != null) {
            return replay;
        }
        // 申请阶段：借出/待核验同样属于“不在可迁移库位”，按 422 逐项说明。
        validatePreconditions(keys, evidences, request.sourceLocationCode(),
                request.expectedVersion(), true);

        LocalDateTime now = LocalDateTime.now();
        moveOrderRepository.insert(request.commandKey(), keys, request.sourceLocationCode(),
                request.targetLocationCode(), request.expectedVersion(), actorId, now);
        LocationMoveOrder order = moveOrderRepository.findByKey(request.commandKey()).orElseThrow();
        return record(request.commandKey(), EvidenceService.OP_MOVE_APPLY, actorId, requestHash,
                200, toMoveView(order));
    }

    /**
     * 解析申请命令重放：同键改参 409；同键同参时若迁移已完成，重放完整迁移结果（单据+逐件快照），
     * 否则返回首次响应（待确认/已撤销态）；无记录返回 null。
     */
    private StoredResponse resolveApplyReplay(String moveKey, String requestHash) {
        Optional<CommandLogRepository.CommandLog> log = commandLogRepository.findByKey(moveKey);
        if (log.isEmpty()) {
            return null;
        }
        CommandLogRepository.CommandLog existing = log.get();
        if (!existing.requestHash().equals(requestHash)) {
            throw ApiException.conflict("幂等键已被不同参数使用: " + moveKey);
        }
        LocationMoveOrder current = moveOrderRepository.findByKey(moveKey).orElse(null);
        if (current != null && current.status() == LocationMoveStatus.COMPLETED) {
            List<MoveSealSnapshotView> snapshots = snapshotRepository.findByMoveKey(moveKey)
                    .stream().map(this::toSnapshotView).toList();
            return new StoredResponse(200,
                    toJson(new MoveResultView(toMoveView(current), snapshots)));
        }
        return new StoredResponse(existing.responseStatus(), existing.responseBody());
    }

    /**
     * 第二人确认：必须与首人不同；单事务内复查完整集合前置条件，
     * 原子更新全部证物库位并写入逐件封签快照；任一不满足整单回滚。
     */
    @Transactional
    public StoredResponse confirmMove(String actorId, String moveKey,
                                      String commandKey, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocationMoveOrder order = moveOrderRepository.findByKeyForUpdate(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        // 并发下本事务可能在迁移单行锁上等待；持锁后复查幂等日志。
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (order.status() == LocationMoveStatus.CANCELLED) {
            throw ApiException.conflict("迁移单已撤销，不可再确认: " + moveKey);
        }
        if (order.status() == LocationMoveStatus.COMPLETED) {
            throw ApiException.conflict("迁移单已完成: " + moveKey);
        }
        if (order.firstConfirmedBy().equals(actorId)) {
            throw ApiException.conflict("第二确认人必须与首人确认人不同: " + actorId);
        }
        // 目标库位停用：409（状态冲突）。
        StorageLocation target = storageLocationRepository
                .findByCodeForUpdate(order.targetLocationCode())
                .orElseThrow(() -> ApiException.notFound("目标库位不存在: " + order.targetLocationCode()));
        if (target.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("目标库位已停用，确认返回冲突: " + order.targetLocationCode());
        }
        List<Evidence> evidences =
                evidenceRepository.findByKeysForUpdateOrdered(order.evidenceKeys());

        // 证物借出/待核验：409；其余前置失败（不在库/封条异常/库位或版本不符）：422 整单回滚。
        validatePreconditions(order.evidenceKeys(), evidences, order.sourceLocationCode(),
                order.expectedVersion(), false);

        LocalDateTime now = LocalDateTime.now();
        for (Evidence evidence : evidences) {
            if (!evidenceRepository.updateLocation(evidence.evidenceKey(),
                    order.sourceLocationCode(), order.targetLocationCode(),
                    order.expectedVersion(), now)) {
                // 行锁内条件更新仍失败说明前置条件已不满足，整单回滚。
                throw new MovePreconditionException("迁移前置条件不满足，整单回滚", List.of(
                        new MoveItemFailure(evidence.evidenceKey(), "库位或版本已变化")));
            }
            snapshotRepository.insert(moveKey, evidence.evidenceKey(), evidence.sealNo(), true,
                    order.sourceLocationCode(), order.expectedVersion(), actorId, now);
        }
        if (!moveOrderRepository.complete(moveKey, actorId, now)) {
            throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
        }
        LocationMoveOrder completed = moveOrderRepository.findByKey(moveKey).orElseThrow();
        List<MoveSealSnapshotView> snapshots =
                snapshotRepository.findByMoveKey(moveKey).stream().map(this::toSnapshotView).toList();
        return record(commandKey, EvidenceService.OP_MOVE_CONFIRM, actorId, requestHash,
                200, new MoveResultView(toMoveView(completed), snapshots));
    }

    /**
     * 撤销迁移单：仅首人确认人可撤销，且单据仍待第二人确认；完成后不可撤销。
     */
    @Transactional
    public StoredResponse cancelMove(String actorId, String moveKey,
                                     String commandKey, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocationMoveOrder order = moveOrderRepository.findByKeyForUpdate(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (order.status() == LocationMoveStatus.COMPLETED) {
            throw ApiException.conflict("迁移单已完成，不可撤销: " + moveKey);
        }
        if (order.status() == LocationMoveStatus.CANCELLED) {
            throw ApiException.conflict("迁移单已撤销: " + moveKey);
        }
        if (!order.firstConfirmedBy().equals(actorId)) {
            throw ApiException.conflict("仅首人确认人可撤销迁移单: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!moveOrderRepository.cancel(moveKey, actorId, now)) {
            throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
        }
        LocationMoveOrder cancelled = moveOrderRepository.findByKey(moveKey).orElseThrow();
        return record(commandKey, EvidenceService.OP_MOVE_CANCEL, actorId, requestHash,
                200, toMoveView(cancelled));
    }

    /**
     * 创建库位：locationCode 全局唯一，初始状态 ACTIVE。
     */
    @Transactional
    public StoredResponse createLocation(String actorId, LocationCreateRequest request,
                                         String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (storageLocationRepository.findByCode(request.locationCode()).isPresent()) {
            throw ApiException.conflict("库位已存在: " + request.locationCode());
        }
        LocalDateTime now = LocalDateTime.now();
        storageLocationRepository.insert(request.locationCode(), request.locationName(), now);
        StorageLocation location =
                storageLocationRepository.findByCode(request.locationCode()).orElseThrow();
        return record(request.commandKey(), EvidenceService.OP_LOCATION_CREATE, actorId,
                requestHash, 201, toLocationView(location));
    }

    /**
     * 停用库位：停用后不可作为迁移目标（确认时返回 409），历史迁移与封签快照不受影响。
     */
    @Transactional
    public StoredResponse disableLocation(String actorId, String locationCode,
                                          CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        StorageLocation location = storageLocationRepository.findByCodeForUpdate(locationCode)
                .orElseThrow(() -> ApiException.notFound("库位不存在: " + locationCode));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (location.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("库位已停用: " + locationCode);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!storageLocationRepository.disable(locationCode, now)) {
            throw ApiException.conflict("库位已被并发停用: " + locationCode);
        }
        StorageLocation disabled = storageLocationRepository.findByCode(locationCode).orElseThrow();
        return record(request.commandKey(), EvidenceService.OP_LOCATION_DISABLE, actorId,
                requestHash, 200, toLocationView(disabled));
    }

    /**
     * 查询库位库存：库位信息与当前位于该库位的全部证物。
     */
    @Transactional(readOnly = true)
    public LocationInventoryView locationInventory(String locationCode) {
        StorageLocation location = storageLocationRepository.findByCode(locationCode)
                .orElseThrow(() -> ApiException.notFound("库位不存在: " + locationCode));
        List<Evidence> items = evidenceRepository.findByLocation(locationCode);
        return new LocationInventoryView(toLocationView(location),
                items.stream().map(this::toEvidenceView).toList());
    }

    /**
     * 查询全部迁移单（按创建顺序）。
     */
    @Transactional(readOnly = true)
    public List<MoveOrderView> listMoves() {
        return moveOrderRepository.findAll().stream().map(this::toMoveView).toList();
    }

    /**
     * 查询单个迁移单及其逐件封签核验快照（历史记录不可变）。
     */
    @Transactional(readOnly = true)
    public MoveResultView moveDetail(String moveKey) {
        LocationMoveOrder order = moveOrderRepository.findByKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        List<MoveSealSnapshotView> snapshots =
                snapshotRepository.findByMoveKey(moveKey).stream().map(this::toSnapshotView).toList();
        return new MoveResultView(toMoveView(order), snapshots);
    }

    /**
     * 按证物查询其全部迁移封签快照（按写入顺序）。
     */
    @Transactional(readOnly = true)
    public List<MoveSealSnapshotView> snapshotsByEvidence(String evidenceKey) {
        if (evidenceRepository.findByKey(evidenceKey).isEmpty()) {
            throw ApiException.notFound("证物不存在: " + evidenceKey);
        }
        return snapshotRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toSnapshotView).toList();
    }

    private StorageLocation requireActiveTarget(String locationCode) {
        StorageLocation location = storageLocationRepository.findByCodeForUpdate(locationCode)
                .orElseThrow(() -> ApiException.notFound("目标库位不存在: " + locationCode));
        if (location.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("目标库位已停用: " + locationCode);
        }
        return location;
    }

    private void requireSourceLocation(String locationCode) {
        if (storageLocationRepository.findByCode(locationCode).isEmpty()) {
            throw ApiException.notFound("源库位不存在: " + locationCode);
        }
    }

    /**
     * 逐件校验迁移前置条件；任一不满足收集为 422 逐项说明（确认阶段借出/待核验按 409 状态冲突）。
     */
    private void validatePreconditions(List<String> requestedKeys, List<Evidence> evidences,
                                       String sourceLocationCode, long expectedVersion,
                                       boolean applying) {
        List<MoveItemFailure> failures = new ArrayList<>();
        for (String key : requestedKeys) {
            Evidence evidence = evidences.stream()
                    .filter(e -> e.evidenceKey().equals(key)).findFirst().orElse(null);
            if (evidence == null) {
                failures.add(new MoveItemFailure(key, "证物不存在"));
                continue;
            }
            if (evidence.status() == EvidenceStatus.BORROWED) {
                if (applying) {
                    failures.add(new MoveItemFailure(key, "证物借出中，不在库"));
                } else {
                    throw ApiException.conflict("证物处于借出状态，确认返回冲突: " + key);
                }
            }
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                if (applying) {
                    failures.add(new MoveItemFailure(key, "证物待核验，不在库"));
                } else {
                    throw ApiException.conflict("证物处于待核验状态，确认返回冲突: " + key);
                }
            }
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                failures.add(new MoveItemFailure(key, "封条异常"));
            }
            if (evidence.locationCode() == null) {
                failures.add(new MoveItemFailure(key, "证物不在库（未分配库位）"));
            } else if (!evidence.locationCode().equals(sourceLocationCode)) {
                failures.add(new MoveItemFailure(key,
                        "当前库位与源库位不符: " + evidence.locationCode()));
            }
            if (evidence.locationVersion() != expectedVersion) {
                failures.add(new MoveItemFailure(key,
                        "库位版本不匹配: 期望 " + expectedVersion + " 实际 " + evidence.locationVersion()));
            }
        }
        if (!failures.isEmpty()) {
            throw new MovePreconditionException("迁移前置条件不满足", failures);
        }
    }

    /**
     * 规范化证物集合：去重并按升序排列；集合换序视为同参。
     */
    public static List<String> normalizeKeys(List<String> keys) {
        return new LinkedHashSet<>(keys).stream().sorted().toList();
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

    private LocationView toLocationView(StorageLocation location) {
        return new LocationView(location.locationCode(), location.locationName(),
                location.status().name(), location.createdAt(), location.updatedAt());
    }

    private com.example.starter.evidence.dto.EvidenceView toEvidenceView(Evidence evidence) {
        return new com.example.starter.evidence.dto.EvidenceView(evidence.evidenceKey(),
                evidence.caseKey(), evidence.category(), evidence.sealNo(), evidence.custodianId(),
                evidence.status(), evidence.locationCode(), evidence.locationVersion(),
                evidence.createdAt(), evidence.updatedAt());
    }

    private MoveOrderView toMoveView(LocationMoveOrder order) {
        return new MoveOrderView(order.moveKey(), order.evidenceKeys(),
                order.sourceLocationCode(), order.targetLocationCode(), order.expectedVersion(),
                order.requestedBy(), order.status().name(),
                order.firstConfirmedBy(), order.firstConfirmedAt(),
                order.secondConfirmedBy(), order.secondConfirmedAt(),
                order.cancelledBy(), order.cancelledAt(), order.createdAt());
    }

    private MoveSealSnapshotView toSnapshotView(LocationMoveSealSnapshot snapshot) {
        return new MoveSealSnapshotView(snapshot.moveKey(), snapshot.evidenceKey(),
                snapshot.sealNo(), snapshot.sealIntact(), snapshot.locationCode(),
                snapshot.locationVersion(), snapshot.checkedBy(), snapshot.checkedAt());
    }

    /**
     * 迁移完整结果：迁移单视图 + 逐件封签核验快照。
     *
     * @param move      迁移单视图
     * @param snapshots 逐件封签核验快照（仅完成态存在）
     */
    public record MoveResultView(MoveOrderView move, List<MoveSealSnapshotView> snapshots) {
    }
}
