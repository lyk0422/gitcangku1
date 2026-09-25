package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.MoveCreateRequest;
import com.example.starter.evidence.dto.MoveRecordView;
import com.example.starter.evidence.dto.MoveResultView;
import com.example.starter.evidence.dto.MoveView;
import com.example.starter.evidence.dto.SealSnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 证物库位迁移服务：迁移申请、双人确认、撤销与查询。
 * 锁顺序统一为：证物行（按业务键排序）→ 迁移单行 → 库位行（按编码排序），
 * 保证确认、撤销、借出与归还核验并发时按事务提交顺序裁决、不产生死锁。
 * 第二人确认在同一事务内校验完整集合前置条件并同时更新全部证物库位、
 * 写入不可变双人迁移记录与逐件封签快照；任一不满足整单回滚。
 */
@Service
public class MoveService {

    static final String OP_MOVE_CREATE = "MOVE_CREATE";
    static final String OP_MOVE_CONFIRM = "MOVE_CONFIRM";
    static final String OP_MOVE_CANCEL = "MOVE_CANCEL";

    private final MoveOrderRepository moveOrderRepository;
    private final MoveRecordRepository moveRecordRepository;
    private final SealSnapshotRepository sealSnapshotRepository;
    private final EvidenceRepository evidenceRepository;
    private final LocationRepository locationRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public MoveService(MoveOrderRepository moveOrderRepository,
                       MoveRecordRepository moveRecordRepository,
                       SealSnapshotRepository sealSnapshotRepository,
                       EvidenceRepository evidenceRepository,
                       LocationRepository locationRepository,
                       CommandLogRepository commandLogRepository,
                       ObjectMapper objectMapper) {
        this.moveOrderRepository = moveOrderRepository;
        this.moveRecordRepository = moveRecordRepository;
        this.sealSnapshotRepository = sealSnapshotRepository;
        this.evidenceRepository = evidenceRepository;
        this.locationRepository = locationRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建迁移申请：所有证物必须在库（SEALED）、封条正常且当前库位等于源库位，
     * 否则 422 并逐项说明；目标库位必须启用；expectedVersion 必须等于源库位当前库存版本。
     * 失败申请不占用 moveKey（事务回滚，幂等日志一并回滚）。
     */
    @Transactional
    public StoredResponse createMove(String actorId, MoveCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.moveKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.sourceLocation().equals(request.targetLocation())) {
            throw ApiException.badRequest("源库位与目标库位不能相同: " + request.sourceLocation());
        }
        StorageLocation source = locationRepository.findByCode(request.sourceLocation())
                .orElseThrow(() -> ApiException.notFound("源库位不存在: " + request.sourceLocation()));
        StorageLocation target = locationRepository.findByCode(request.targetLocation())
                .orElseThrow(() -> ApiException.notFound("目标库位不存在: " + request.targetLocation()));
        if (target.status() != LocationStatus.ACTIVE) {
            throw ApiException.conflict("目标库位已停用: " + request.targetLocation());
        }
        if (request.expectedVersion() != source.version()) {
            throw ApiException.conflict("源库位库存版本已变化: 期望 " + request.expectedVersion()
                    + "，当前 " + source.version());
        }
        List<String> failures = new ArrayList<>();
        for (String key : request.evidenceKeys()) {
            Optional<Evidence> found = evidenceRepository.findByKey(key);
            if (found.isEmpty()) {
                failures.add("证物不存在: " + key);
                continue;
            }
            Evidence evidence = found.get();
            switch (evidence.status()) {
                case SEAL_BROKEN -> failures.add("证物封条异常: " + key);
                case BORROWED -> failures.add("证物借出中: " + key);
                case TRANSFER_PENDING -> failures.add("证物待核验（待接收交接）: " + key);
                default -> {
                }
            }
            if (!evidence.locationCode().equals(request.sourceLocation())) {
                failures.add("证物当前库位不等于源库位: " + key + "（当前: " + evidence.locationCode() + "）");
            }
        }
        if (!failures.isEmpty()) {
            throw ApiException.unprocessable("迁移申请前置校验失败，逐项说明如下", failures);
        }
        LocalDateTime now = LocalDateTime.now();
        moveOrderRepository.insert(request.moveKey(), request.evidenceKeys(),
                request.sourceLocation(), request.targetLocation(), request.expectedVersion(),
                actorId, now);
        MoveOrder order = moveOrderRepository.findByKey(request.moveKey()).orElseThrow();
        return record(request.moveKey(), OP_MOVE_CREATE, actorId, requestHash, 201, toView(order));
    }

    /**
     * 确认迁移：同一迁移单需两名不同保管人先后确认。
     * 首人确认（PENDING → FIRST_CONFIRMED）校验证物前置条件、目标库位启用与版本；
     * 第二人确认在同一事务内复查完整集合仍满足前置条件，再原子执行整单迁移。
     * 目标库位被停用或证物处于借出、待核验状态时返回 409；封条异常返回 422。
     */
    @Transactional
    public StoredResponse confirm(String actorId, String moveKey,
                                  CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MoveOrder snapshot = moveOrderRepository.findByKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        // 统一锁顺序：先按业务键排序锁定全部证物行，再锁迁移单行，最后锁库位行。
        Map<String, Evidence> locked = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String key : snapshot.evidenceKeys()) {
            Optional<Evidence> found = evidenceRepository.findByKeyForUpdate(key);
            if (found.isPresent()) {
                locked.put(key, found.get());
            } else {
                missing.add(key);
            }
        }
        MoveOrder order = moveOrderRepository.findByKeyForUpdate(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        // 并发下本事务可能在行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Map<String, StorageLocation> locations = lockLocations(order);
        switch (order.status()) {
            case CANCELLED -> throw ApiException.conflict("迁移单已撤销，不可再确认: " + moveKey);
            case COMPLETED -> throw ApiException.conflict("迁移单已完成，不可重复确认: " + moveKey);
            case PENDING -> {
                requirePreconditions(order, locked, missing, locations);
                requireVersion(order, locations);
                LocalDateTime now = LocalDateTime.now();
                if (!moveOrderRepository.confirmFirst(moveKey, actorId, now)) {
                    throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
                }
                MoveOrder updated = moveOrderRepository.findByKey(moveKey).orElseThrow();
                return record(request.commandKey(), OP_MOVE_CONFIRM, actorId, requestHash,
                        200, toView(updated));
            }
            case FIRST_CONFIRMED -> {
                if (actorId.equals(order.firstConfirmer())) {
                    throw ApiException.conflict("迁移须两名不同保管人确认，第二人不能与首人相同: " + actorId);
                }
                requirePreconditions(order, locked, missing, locations);
                requireVersion(order, locations);
                return executeMove(actorId, order, locked, request.commandKey(), requestHash);
            }
            default -> throw new IllegalStateException("未知迁移单状态: " + order.status());
        }
    }

    /**
     * 撤销迁移单：首人确认后（含待确认）可撤销；撤销后不可再确认；完成后不可撤销。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String moveKey,
                                 CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        MoveOrder order = moveOrderRepository.findByKeyForUpdate(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey));
        // 并发下本事务可能在迁移单行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (order.status() == MoveStatus.COMPLETED) {
            throw ApiException.conflict("迁移单已完成，不可撤销: " + moveKey);
        }
        if (order.status() == MoveStatus.CANCELLED) {
            throw ApiException.conflict("迁移单已撤销: " + moveKey);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!moveOrderRepository.cancel(moveKey, now)) {
            throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
        }
        MoveOrder updated = moveOrderRepository.findByKey(moveKey).orElseThrow();
        return record(request.commandKey(), OP_MOVE_CANCEL, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 查询迁移单当前状态。
     */
    @Transactional(readOnly = true)
    public MoveView getMove(String moveKey) {
        return toView(moveOrderRepository.findByKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移单不存在: " + moveKey)));
    }

    /**
     * 查询不可变双人迁移记录（迁移完成后存在）。
     */
    @Transactional(readOnly = true)
    public MoveRecordView getRecord(String moveKey) {
        if (moveOrderRepository.findByKey(moveKey).isEmpty()) {
            throw ApiException.notFound("迁移单不存在: " + moveKey);
        }
        return toView(moveRecordRepository.findByMoveKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("迁移记录不存在（迁移未完成）: " + moveKey)));
    }

    /**
     * 查询迁移单逐件封签核验快照。
     */
    @Transactional(readOnly = true)
    public List<SealSnapshotView> listSnapshots(String moveKey) {
        if (moveOrderRepository.findByKey(moveKey).isEmpty()) {
            throw ApiException.notFound("迁移单不存在: " + moveKey);
        }
        return sealSnapshotRepository.findByMoveKey(moveKey).stream()
                .map(this::toView).toList();
    }

    /**
     * 第二人确认执行：同一事务内更新全部证物库位、双方库位库存版本，
     * 写入不可变双人迁移记录与逐件封签快照；任一失败整单回滚。
     */
    private StoredResponse executeMove(String actorId, MoveOrder order,
                                       Map<String, Evidence> locked,
                                       String commandKey, String requestHash) {
        LocalDateTime now = LocalDateTime.now();
        for (String key : order.evidenceKeys()) {
            Evidence evidence = locked.get(key);
            evidenceRepository.updateLocation(key, order.targetLocation(), now);
            sealSnapshotRepository.insert(order.moveKey(), key, evidence.sealNo(),
                    order.sourceLocation(), order.targetLocation(),
                    order.firstConfirmer(), actorId, now);
        }
        locationRepository.incrementVersion(order.sourceLocation(), now);
        locationRepository.incrementVersion(order.targetLocation(), now);
        moveRecordRepository.insert(order.moveKey(), order.evidenceKeys(),
                order.sourceLocation(), order.targetLocation(), order.expectedVersion(),
                order.firstConfirmer(), actorId, now);
        if (!moveOrderRepository.complete(order.moveKey(), actorId, now)) {
            throw ApiException.conflict("迁移单已被并发处理: " + order.moveKey());
        }
        MoveOrder completed = moveOrderRepository.findByKey(order.moveKey()).orElseThrow();
        MoveRecord moveRecord = moveRecordRepository.findByMoveKey(order.moveKey()).orElseThrow();
        List<SealSnapshotView> snapshots = sealSnapshotRepository.findByMoveKey(order.moveKey())
                .stream().map(this::toView).toList();
        MoveResultView result = new MoveResultView(toView(completed), toView(moveRecord), snapshots);
        return record(commandKey, OP_MOVE_CONFIRM, actorId, requestHash, 200, result);
    }

    /**
     * 按编码排序锁定源/目标库位行（保持与证据行、迁移单行一致的锁顺序末端）。
     */
    private Map<String, StorageLocation> lockLocations(MoveOrder order) {
        Map<String, StorageLocation> locations = new LinkedHashMap<>();
        for (String code : new TreeSet<>(List.of(order.sourceLocation(), order.targetLocation()))) {
            locations.put(code, locationRepository.findByCodeForUpdate(code)
                    .orElseThrow(() -> ApiException.conflict("库位不存在: " + code)));
        }
        return locations;
    }

    /**
     * 校验完整集合前置条件：目标库位启用；每件证物在库、封条正常、当前库位等于源库位。
     * 封条异常返回 422，其余冲突返回 409，均逐项说明。
     */
    private void requirePreconditions(MoveOrder order, Map<String, Evidence> locked,
                                      List<String> missing, Map<String, StorageLocation> locations) {
        StorageLocation target = locations.get(order.targetLocation());
        if (target.status() != LocationStatus.ACTIVE) {
            throw ApiException.conflict("目标库位已停用: " + order.targetLocation());
        }
        List<String> conflicts = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (String key : missing) {
            conflicts.add("证物不存在: " + key);
        }
        for (Map.Entry<String, Evidence> entry : locked.entrySet()) {
            String key = entry.getKey();
            Evidence evidence = entry.getValue();
            switch (evidence.status()) {
                case BORROWED -> conflicts.add("证物借出中: " + key);
                case TRANSFER_PENDING -> conflicts.add("证物待核验（待接收交接）: " + key);
                case SEAL_BROKEN -> broken.add("证物封条异常: " + key);
                default -> {
                }
            }
            if (!evidence.locationCode().equals(order.sourceLocation())) {
                conflicts.add("证物当前库位不等于源库位: " + key
                        + "（当前: " + evidence.locationCode() + "）");
            }
        }
        if (!broken.isEmpty()) {
            List<String> items = new ArrayList<>(broken);
            items.addAll(conflicts);
            throw ApiException.unprocessable("迁移确认前置校验失败，逐项说明如下", items);
        }
        if (!conflicts.isEmpty()) {
            throw ApiException.conflict("迁移确认前置校验失败，逐项说明如下", conflicts);
        }
    }

    /**
     * 校验源库位库存版本仍等于申请时的 expectedVersion（乐观并发控制）。
     */
    private void requireVersion(MoveOrder order, Map<String, StorageLocation> locations) {
        StorageLocation source = locations.get(order.sourceLocation());
        if (source.version() != order.expectedVersion()) {
            throw ApiException.conflict("源库位库存版本已变化: 期望 " + order.expectedVersion()
                    + "，当前 " + source.version());
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

    private MoveView toView(MoveOrder order) {
        return new MoveView(order.moveKey(), order.evidenceKeys(), order.sourceLocation(),
                order.targetLocation(), order.expectedVersion(), order.status(), order.createdBy(),
                order.firstConfirmer(), order.secondConfirmer(), order.createdAt(),
                order.decidedAt());
    }

    private MoveRecordView toView(MoveRecord record) {
        return new MoveRecordView(record.moveKey(), record.evidenceKeys(), record.sourceLocation(),
                record.targetLocation(), record.expectedVersion(), record.firstConfirmer(),
                record.secondConfirmer(), record.completedAt());
    }

    private SealSnapshotView toView(SealSnapshot snapshot) {
        return new SealSnapshotView(snapshot.moveKey(), snapshot.evidenceKey(), snapshot.sealNo(),
                snapshot.sealStatus(), snapshot.fromLocation(), snapshot.toLocation(),
                snapshot.firstConfirmer(), snapshot.secondConfirmer(), snapshot.createdAt());
    }
}
