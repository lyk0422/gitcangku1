package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ItemRejection;
import com.example.starter.evidence.dto.MoveApplyRequest;
import com.example.starter.evidence.dto.MoveSealSnapshotView;
import com.example.starter.evidence.dto.MoveView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 库位迁移核心服务。
 * 申请：全部证物须在库（SEALED）、封条正常且当前库位等于源库位，否则 422 逐项说明，失败不占键。
 * 确认：两名不同保管人先后确认；第二人确认时在同一事务内锁定迁移单、源/目标库位与完整证物集合，
 * 重新校验前置条件后原子更新全部证物库位、递增源/目标库位库存版本并写入逐件封签快照，
 * 任一不满足整单回滚。撤销：仅首人确认后可撤销，撤销后不可再确认，完成后不可撤销。
 */
@Service
public class MoveService {

    static final String OP_MOVE_APPLY = "MOVE_APPLY";
    static final String OP_MOVE_CONFIRM = "MOVE_CONFIRM";
    static final String OP_MOVE_CANCEL = "MOVE_CANCEL";

    private final MoveOrderRepository moveOrderRepository;
    private final MoveSealSnapshotRepository snapshotRepository;
    private final StorageLocationRepository locationRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public MoveService(MoveOrderRepository moveOrderRepository,
                       MoveSealSnapshotRepository snapshotRepository,
                       StorageLocationRepository locationRepository,
                       EvidenceRepository evidenceRepository,
                       CommandLogRepository commandLogRepository,
                       ObjectMapper objectMapper) {
        this.moveOrderRepository = moveOrderRepository;
        this.snapshotRepository = snapshotRepository;
        this.locationRepository = locationRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 规范化证物集合：去重后按字典序升序。换序或含重复的申请视为同参。
     */
    public static List<String> normalizeKeys(List<String> evidenceKeys) {
        return evidenceKeys.stream().distinct().sorted().toList();
    }

    /**
     * 迁移申请（事务内）。moveKey 已存在时同参重放完整迁移结果（200）、改参 409；
     * 前置校验失败抛出 422/409/404，不落库、不占键。
     */
    @Transactional
    public StoredResponse apply(String actorId, MoveApplyRequest request, String requestHash) {
        Optional<MoveOrder> existing = moveOrderRepository.findByKey(request.moveKey());
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        if (request.sourceLocation().equals(request.targetLocation())) {
            throw ApiException.badRequest("源库位与目标库位不能相同");
        }
        StorageLocation source = locationRepository.findByCode(request.sourceLocation())
                .orElseThrow(() -> ApiException.notFound("源库位不存在: " + request.sourceLocation()));
        StorageLocation target = locationRepository.findByCode(request.targetLocation())
                .orElseThrow(() -> ApiException.notFound("目标库位不存在: " + request.targetLocation()));
        if (target.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("目标库位已停用: " + request.targetLocation());
        }
        if (source.version() != request.expectedVersion()) {
            throw ApiException.conflict("源库位库存版本不匹配: 期望 " + request.expectedVersion()
                    + "，实际 " + source.version());
        }
        Map<String, Evidence> byKey = lockEvidenceSet(request.evidenceKeys());
        List<ItemRejection> rejections = new ArrayList<>();
        for (String key : request.evidenceKeys()) {
            Evidence evidence = byKey.get(key);
            if (evidence == null) {
                rejections.add(new ItemRejection(key, "证物不存在"));
            } else if (evidence.status() == EvidenceStatus.BORROWED) {
                rejections.add(new ItemRejection(key, "证物处于借出状态，不在库"));
            } else if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                rejections.add(new ItemRejection(key, "证物处于待接收交接状态，不在库"));
            } else if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                rejections.add(new ItemRejection(key, "证物封条异常"));
            } else if (!request.sourceLocation().equals(evidence.locationCode())) {
                rejections.add(new ItemRejection(key,
                        "当前库位与源库位不符: 实际 " + evidence.locationCode()));
            }
        }
        if (!rejections.isEmpty()) {
            throw new MoveRejectionException("迁移申请前置校验失败，共 " + rejections.size() + " 项不满足",
                    rejections);
        }
        LocalDateTime now = LocalDateTime.now();
        moveOrderRepository.insert(request.moveKey(), requestHash, actorId, request.evidenceKeys(),
                request.sourceLocation(), request.targetLocation(), request.expectedVersion(), now);
        MoveOrder order = moveOrderRepository.findByKey(request.moveKey()).orElseThrow();
        return new StoredResponse(201, toJson(toView(order)));
    }

    /**
     * 并发申请导致 move_key 唯一冲突后的重放：读取先提交事务的迁移单，同参返回完整迁移结果。
     */
    public StoredResponse replayAfterDuplicate(String moveKey, String requestHash) {
        Optional<MoveOrder> existing = moveOrderRepository.findByKey(moveKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }
        throw ApiException.conflict("资源键冲突，目标已存在");
    }

    /**
     * 双人确认（事务内）。首人确认：PENDING -> FIRST_CONFIRMED；
     * 第二人（须与首人不同）确认：整单校验并原子执行迁移 -> COMPLETED。
     */
    @Transactional
    public StoredResponse confirm(String actorId, String moveKey,
                                  CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MoveOrder order = lockOrder(moveKey);
        // 并发下本事务可能在迁移单行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = LocalDateTime.now();
        switch (order.status()) {
            case CANCELLED -> throw ApiException.conflict("迁移单已撤销，不可再确认: " + moveKey);
            case COMPLETED -> throw ApiException.conflict("迁移单已完成，不可重复确认: " + moveKey);
            case PENDING -> {
                if (!moveOrderRepository.markFirstConfirmed(order.id(), actorId, now)) {
                    throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
                }
                MoveOrder updated = moveOrderRepository.findByKey(moveKey).orElseThrow();
                return record(request.commandKey(), OP_MOVE_CONFIRM, actorId, requestHash,
                        200, toView(updated));
            }
            case FIRST_CONFIRMED -> {
                if (order.firstConfirmer().equals(actorId)) {
                    throw ApiException.conflict("迁移须由两名不同保管人确认: " + actorId);
                }
                executeMove(order, actorId, now);
                if (!moveOrderRepository.markCompleted(order.id(), actorId, now)) {
                    throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
                }
                MoveOrder updated = moveOrderRepository.findByKey(moveKey).orElseThrow();
                return record(request.commandKey(), OP_MOVE_CONFIRM, actorId, requestHash,
                        200, toView(updated));
            }
            default -> throw new IllegalStateException("未知迁移单状态: " + order.status());
        }
    }

    /**
     * 撤销迁移单（事务内）：仅首人确认后（FIRST_CONFIRMED）可撤销，
     * 仅申请人或首确认人可操作；撤销后不可再确认，完成后不可撤销。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String moveKey,
                                 CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MoveOrder order = lockOrder(moveKey);
        // 并发下本事务可能在迁移单行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime now = LocalDateTime.now();
        switch (order.status()) {
            case PENDING -> throw ApiException.conflict("迁移单尚未首人确认，不可撤销: " + moveKey);
            case COMPLETED -> throw ApiException.conflict("迁移单已完成，不可撤销: " + moveKey);
            case CANCELLED -> throw ApiException.conflict("迁移单已撤销: " + moveKey);
            case FIRST_CONFIRMED -> {
                if (!actorId.equals(order.applicantId()) && !actorId.equals(order.firstConfirmer())) {
                    throw ApiException.conflict("仅申请人或首确认人可撤销迁移单: " + actorId);
                }
                if (!moveOrderRepository.markCancelled(order.id(), actorId, now)) {
                    throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
                }
                MoveOrder updated = moveOrderRepository.findByKey(moveKey).orElseThrow();
                return record(request.commandKey(), OP_MOVE_CANCEL, actorId, requestHash,
                        200, toView(updated));
            }
            default -> throw new IllegalStateException("未知迁移单状态: " + order.status());
        }
    }

    /**
     * 查询迁移单（含完成后的逐件封签快照）。
     */
    @Transactional(readOnly = true)
    public MoveView getMove(String moveKey) {
        MoveOrder order = moveOrderRepository.findByKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        return toView(order);
    }

    /**
     * 查询迁移单的逐件封签快照。
     */
    @Transactional(readOnly = true)
    public List<MoveSealSnapshotView> listSnapshots(String moveKey) {
        moveOrderRepository.findByKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        return snapshotRepository.findByMoveKey(moveKey).stream().map(this::toView).toList();
    }

    /**
     * 第二人确认的执行体：锁定源/目标库位（按库位键升序，避免并发迁移死锁）与完整证物集合，
     * 校验全部前置条件后原子迁移；任一不满足抛异常整单回滚。
     */
    private void executeMove(MoveOrder order, String secondConfirmer, LocalDateTime now) {
        Map<String, StorageLocation> locations = new LinkedHashMap<>();
        for (String code : Stream.of(order.sourceLocation(), order.targetLocation()).sorted().toList()) {
            locations.put(code, locationRepository.findByCodeForUpdate(code)
                    .orElseThrow(() -> ApiException.notFound("库位不存在: " + code)));
        }
        StorageLocation target = locations.get(order.targetLocation());
        StorageLocation source = locations.get(order.sourceLocation());
        if (target.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("目标库位已停用，禁止确认迁移: " + order.targetLocation());
        }
        if (source.version() != order.expectedVersion()) {
            throw ApiException.conflict("源库位库存版本已变化，迁移申请已失效: 期望 "
                    + order.expectedVersion() + "，实际 " + source.version());
        }
        Map<String, Evidence> byKey = lockEvidenceSet(order.evidenceKeys());
        for (String key : order.evidenceKeys()) {
            Evidence evidence = byKey.get(key);
            if (evidence == null) {
                throw ApiException.conflict("证物不存在，整单回滚: " + key);
            }
            if (evidence.status() == EvidenceStatus.BORROWED) {
                throw ApiException.conflict("证物处于借出状态，禁止迁移，整单回滚: " + key);
            }
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                throw ApiException.conflict("证物处于待接收交接状态，禁止迁移，整单回滚: " + key);
            }
            if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
                throw ApiException.conflict("证物封条异常，禁止迁移，整单回滚: " + key);
            }
            if (!order.sourceLocation().equals(evidence.locationCode())) {
                throw ApiException.conflict("证物当前库位已变化，整单回滚: " + key);
            }
        }
        for (String key : order.evidenceKeys()) {
            evidenceRepository.updateLocation(key, order.targetLocation(), now);
        }
        locationRepository.bumpVersion(order.sourceLocation(), now);
        locationRepository.bumpVersion(order.targetLocation(), now);
        for (String key : order.evidenceKeys()) {
            Evidence evidence = byKey.get(key);
            snapshotRepository.insert(order.moveKey(), key, evidence.sealNo(),
                    order.sourceLocation(), order.targetLocation(),
                    order.firstConfirmer(), secondConfirmer, now);
        }
    }

    /**
     * 按规范化（升序）键集合锁定证物行，保证并发迁移/借出/归还按固定顺序取锁。
     */
    private Map<String, Evidence> lockEvidenceSet(List<String> evidenceKeys) {
        return evidenceRepository.findByKeysForUpdate(evidenceKeys).stream()
                .collect(Collectors.toMap(Evidence::evidenceKey, Function.identity()));
    }

    private MoveOrder lockOrder(String moveKey) {
        return moveOrderRepository.findByKeyForUpdate(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
    }

    private StoredResponse replay(MoveOrder order, String requestHash) {
        if (!order.requestHash().equals(requestHash)) {
            throw ApiException.conflict("迁移键已被不同参数使用: " + order.moveKey());
        }
        return new StoredResponse(200, toJson(toView(order)));
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

    private MoveView toView(MoveOrder order) {
        List<MoveSealSnapshotView> snapshots = snapshotRepository.findByMoveKey(order.moveKey())
                .stream().map(this::toView).toList();
        return new MoveView(order.moveKey(), order.applicantId(), order.evidenceKeys(),
                order.sourceLocation(), order.targetLocation(), order.expectedVersion(),
                order.status(), order.firstConfirmer(), order.firstConfirmedAt(),
                order.secondConfirmer(), order.cancelledBy(), order.decidedAt(),
                order.createdAt(), snapshots);
    }

    private MoveSealSnapshotView toView(MoveSealSnapshot snapshot) {
        return new MoveSealSnapshotView(snapshot.evidenceKey(), snapshot.sealNo(),
                snapshot.fromLocation(), snapshot.toLocation(),
                snapshot.firstConfirmer(), snapshot.secondConfirmer(), snapshot.snapshotAt());
    }
}
