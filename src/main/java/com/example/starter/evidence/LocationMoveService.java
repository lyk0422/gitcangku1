package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.EvidenceShelveRequest;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.LocationStockView;
import com.example.starter.evidence.dto.LocationView;
import com.example.starter.evidence.dto.MoveCommandRequest;
import com.example.starter.evidence.dto.MoveCreateRequest;
import com.example.starter.evidence.dto.MoveView;
import com.example.starter.evidence.dto.SealSnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 证物位置封签核心服务：库位管理、位置迁移申请、双人确认、撤销与封签快照查询。
 *
 * 并发裁决：所有写操作先锁定迁移单行（SELECT ... FOR UPDATE），再按证物键字典序锁定集合内全部证物行，
 * 最后锁定目标库位行；统一加锁顺序避免死锁，确认/撤销/借出/归还按事务提交顺序生效。
 * 任一件证物不满足前置条件则整单回滚，不产生部分迁移。
 *
 * 幂等：成功结果写入 command_log，同 commandKey 同指纹重放首次结果；业务失败在写日志前抛出，不占用命令键。
 */
@Service
public class LocationMoveService {

    static final String OP_LOCATION_CREATE = "LOCATION_CREATE";
    static final String OP_LOCATION_DISABLE = "LOCATION_DISABLE";
    static final String OP_EVIDENCE_SHELVE = "EVIDENCE_SHELVE";
    static final String OP_MOVE_CREATE = "LOCATION_MOVE_CREATE";
    static final String OP_MOVE_CONFIRM_FIRST = "LOCATION_MOVE_CONFIRM_FIRST";
    static final String OP_MOVE_CONFIRM_SECOND = "LOCATION_MOVE_CONFIRM_SECOND";
    static final String OP_MOVE_CANCEL = "LOCATION_MOVE_CANCEL";

    private final StorageLocationRepository locationRepository;
    private final LocationMoveRepository moveRepository;
    private final EvidenceRepository evidenceRepository;
    private final SealSnapshotRepository snapshotRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public LocationMoveService(StorageLocationRepository locationRepository,
                               LocationMoveRepository moveRepository,
                               EvidenceRepository evidenceRepository,
                               SealSnapshotRepository snapshotRepository,
                               CommandLogRepository commandLogRepository,
                               ObjectMapper objectMapper) {
        this.locationRepository = locationRepository;
        this.moveRepository = moveRepository;
        this.evidenceRepository = evidenceRepository;
        this.snapshotRepository = snapshotRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 库位建档：location_code 全局唯一，初始 ACTIVE。
     */
    @Transactional
    public StoredResponse createLocation(String actorId, LocationCreateRequest request,
                                         String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (locationRepository.findByCode(request.locationCode()).isPresent()) {
            throw ApiException.conflict("库位已存在: " + request.locationCode());
        }
        LocalDateTime now = LocalDateTime.now();
        locationRepository.insert(request.locationCode(), request.description(), now);
        StorageLocation location = locationRepository.findByCode(request.locationCode()).orElseThrow();
        return record(request.commandKey(), OP_LOCATION_CREATE, actorId, requestHash,
                201, toView(location));
    }

    /**
     * 停用库位：停用后不可再作为迁移目标，历史库存与迁移记录不变。
     */
    @Transactional
    public StoredResponse disableLocation(String actorId, String locationCode,
                                          MoveCommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        StorageLocation location = requireLocation(locationCode);
        if (location.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("库位已停用: " + locationCode);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!locationRepository.disable(locationCode, now)) {
            throw ApiException.conflict("库位已被并发停用: " + locationCode);
        }
        StorageLocation updated = locationRepository.findByCode(locationCode).orElseThrow();
        return record(request.commandKey(), OP_LOCATION_DISABLE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 证物首次上架：仅当前保管人，证物须 SEALED 且未上架；目标库位须启用。上架后版本号加 1。
     * 已在库位上的证物不得通过本接口改位，必须走双人位置迁移。
     */
    @Transactional
    public StoredResponse shelve(String actorId, String evidenceKey, EvidenceShelveRequest request,
                                 String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("仅已封存证物可上架，当前状态: " + evidence.status());
        }
        if (evidence.locationCode() != null) {
            throw ApiException.conflict("证物已在库位，变更库位须走位置迁移: " + evidence.locationCode());
        }
        StorageLocation target = requireLocation(request.locationCode());
        if (target.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("目标库位已停用: " + request.locationCode());
        }
        LocalDateTime now = LocalDateTime.now();
        evidenceRepository.updateLocation(evidenceKey, request.locationCode(), now);
        Evidence updated = evidenceRepository.findByKey(evidenceKey).orElseThrow();
        return record(request.commandKey(), OP_EVIDENCE_SHELVE, actorId, requestHash,
                200, toEvidenceView(updated));
    }

    /**
     * 位置迁移申请：校验证物集合（逐项 422）、源/目标库位与 expectedVersion，冻结规范化集合。
     * 集合换序、重复键视为同参（指纹相同）。
     */
    @Transactional
    public StoredResponse createMove(String actorId, MoveCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> keys = normalizeKeys(request.evidenceKeys());
        if (request.sourceLocationCode().equals(request.targetLocationCode())) {
            throw ApiException.badRequest("源库位与目标库位不能相同");
        }
        requireLocation(request.sourceLocationCode());
        requireLocation(request.targetLocationCode());
        if (moveRepository.findByKey(request.moveKey()).isPresent()) {
            throw ApiException.conflict("位置迁移单已存在: " + request.moveKey());
        }
        List<MovePreconditionException.ItemError> errors = validateItems(keys,
                request.sourceLocationCode(), request.expectedVersion());
        if (!errors.isEmpty()) {
            throw new MovePreconditionException(errors);
        }
        LocalDateTime now = LocalDateTime.now();
        moveRepository.insert(request.moveKey(), keys, request.sourceLocationCode(),
                request.targetLocationCode(), request.expectedVersion(), now);
        LocationMove move = moveRepository.findByKey(request.moveKey()).orElseThrow();
        return record(request.commandKey(), OP_MOVE_CREATE, actorId, requestHash,
                201, toView(move, keys));
    }

    /**
     * 首人确认：单据须 AWAITING_FIRST；在事务内复查完整集合前置条件后推进到 AWAITING_SECOND。
     * 目标库位停用、证物借出/待核验返回 409；在库/封条/库位/版本不满足逐项 422。
     */
    @Transactional
    public StoredResponse confirmFirst(String actorId, String moveKey, MoveCommandRequest request,
                                       String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocationMove move = lockMove(moveKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> keys = moveRepository.findItemKeys(moveKey);
        switch (move.status()) {
            case AWAITING_SECOND -> throw ApiException.conflict("迁移单已完成首人确认，等待不同的第二保管人确认: " + moveKey);
            case COMPLETED -> throw ApiException.conflict("迁移单已双人确认完成，不可再确认: " + moveKey);
            case CANCELLED -> throw ApiException.conflict("迁移单已撤销，不可再确认: " + moveKey);
            default -> {
            }
        }
        checkConfirmGuards(move, keys);
        LocalDateTime now = LocalDateTime.now();
        if (!moveRepository.markFirstConfirmed(move.id(), actorId, now)) {
            throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
        }
        LocationMove updated = moveRepository.findByKey(moveKey).orElseThrow();
        return record(request.commandKey(), OP_MOVE_CONFIRM_FIRST, actorId, requestHash,
                200, toView(updated, keys));
    }

    /**
     * 第二人确认：必须与首人不同；持锁复查完整集合后，在一个事务内
     * 逐件写入迁移前封签快照、同时更新全部证物库位（版本各加 1），单据置 COMPLETED。
     * 任一不满足整单回滚：不产生快照、不改任何库位。
     */
    @Transactional
    public StoredResponse confirmSecond(String actorId, String moveKey, MoveCommandRequest request,
                                        String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocationMove move = lockMove(moveKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> keys = moveRepository.findItemKeys(moveKey);
        switch (move.status()) {
            case AWAITING_FIRST -> throw ApiException.conflict("迁移单尚未经首人确认: " + moveKey);
            case COMPLETED -> throw ApiException.conflict("迁移单已双人确认完成，不可再确认: " + moveKey);
            case CANCELLED -> throw ApiException.conflict("迁移单已撤销，不可再确认: " + moveKey);
            default -> {
            }
        }
        if (move.firstConfirmer().equals(actorId)) {
            throw ApiException.badRequest("第二确认保管人必须与首人不同: " + actorId);
        }
        checkConfirmGuards(move, keys);
        LocalDateTime now = LocalDateTime.now();
        if (!moveRepository.markCompleted(move.id(), actorId, now)) {
            throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
        }
        // 先写逐件封签快照（迁移前状态），再原子更新全部证物库位；与本事务同生共死。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdate(keys);
        for (Evidence evidence : locked) {
            snapshotRepository.insert(moveKey, evidence, move.firstConfirmer(), actorId, now);
            evidenceRepository.updateLocation(evidence.evidenceKey(), move.targetLocationCode(), now);
        }
        LocationMove updated = moveRepository.findByKey(moveKey).orElseThrow();
        return record(request.commandKey(), OP_MOVE_CONFIRM_SECOND, actorId, requestHash,
                200, toView(updated, keys));
    }

    /**
     * 撤销迁移单：仅首人确认后（AWAITING_SECOND）且仅首人可撤销；撤销后不可再确认，完成后不可撤销。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String moveKey, MoveCommandRequest request,
                                 String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocationMove move = lockMove(moveKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> keys = moveRepository.findItemKeys(moveKey);
        switch (move.status()) {
            case AWAITING_FIRST -> throw ApiException.conflict("迁移单尚未经首人确认，不可撤销: " + moveKey);
            case COMPLETED -> throw ApiException.conflict("迁移单已双人确认完成，不可撤销: " + moveKey);
            case CANCELLED -> throw ApiException.conflict("迁移单已撤销，不可重复撤销: " + moveKey);
            default -> {
            }
        }
        if (!move.firstConfirmer().equals(actorId)) {
            throw ApiException.conflict("仅首人确认保管人可撤销迁移单: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!moveRepository.markCancelledAfterFirst(move.id(), now)) {
            throw ApiException.conflict("迁移单已被并发处理: " + moveKey);
        }
        LocationMove updated = moveRepository.findByKey(moveKey).orElseThrow();
        return record(request.commandKey(), OP_MOVE_CANCEL, actorId, requestHash,
                200, toView(updated, keys));
    }

    /**
     * 查询库位库存：库位信息 + 当前在库的全部证物（按证物键字典序）。
     */
    @Transactional(readOnly = true)
    public LocationStockView stock(String locationCode) {
        StorageLocation location = requireLocation(locationCode);
        List<EvidenceView> items = evidenceRepository.findByLocation(locationCode).stream()
                .map(this::toEvidenceView).toList();
        return new LocationStockView(toView(location), items);
    }

    /**
     * 查询迁移单（含冻结的规范化证物集合）。
     */
    @Transactional(readOnly = true)
    public MoveView getMove(String moveKey) {
        LocationMove move = moveRepository.findByKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("位置迁移单不存在: " + moveKey));
        return toView(move, moveRepository.findItemKeys(moveKey));
    }

    /**
     * 查询迁移单逐件封签核验快照（未完成时为空列表，完成后不可变）。
     */
    @Transactional(readOnly = true)
    public List<SealSnapshotView> snapshots(String moveKey) {
        moveRepository.findByKey(moveKey)
                .orElseThrow(() -> ApiException.notFound("位置迁移单不存在: " + moveKey));
        return snapshotRepository.findByMoveKey(moveKey).stream()
                .map(this::toSnapshotView).toList();
    }

    /**
     * 规范化证物集合：去重并按字典序排序；集合换序与重复键视为同参。
     */
    public static List<String> normalizeKeys(List<String> evidenceKeys) {
        return new LinkedHashSet<>(evidenceKeys).stream().sorted().toList();
    }

    private void checkConfirmGuards(LocationMove move, List<String> keys) {
        // 锁定目标库位：停用库位在确认阶段返回 409。
        StorageLocation target = locationRepository.findByCodeForUpdate(move.targetLocationCode())
                .orElseThrow(() -> ApiException.notFound("目标库位不存在: " + move.targetLocationCode()));
        if (target.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("目标库位已停用，确认被拒绝: " + move.targetLocationCode());
        }
        // 按字典序锁定集合内全部证物行，与借出/归还/交接/撤销并发时按提交顺序裁决。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdate(keys);
        for (Evidence evidence : locked) {
            if (evidence.status() == EvidenceStatus.BORROWED) {
                throw ApiException.conflict("证物处于借出状态，确认被拒绝: " + evidence.evidenceKey());
            }
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                throw ApiException.conflict("证物处于待核验（交接在途）状态，确认被拒绝: " + evidence.evidenceKey());
            }
        }
        // 封条、在库库位、版本不满足：逐项 422，整单不生效。
        List<MovePreconditionException.ItemError> errors = validateLocked(locked, keys,
                move.sourceLocationCode(), move.expectedVersion());
        if (!errors.isEmpty()) {
            throw new MovePreconditionException(errors);
        }
    }

    private List<MovePreconditionException.ItemError> validateItems(List<String> keys,
                                                                    String sourceLocationCode,
                                                                    long expectedVersion) {
        List<MovePreconditionException.ItemError> errors = new ArrayList<>();
        for (String key : keys) {
            Evidence evidence = evidenceRepository.findByKey(key).orElse(null);
            if (evidence == null) {
                errors.add(new MovePreconditionException.ItemError(key, "证物不存在"));
                continue;
            }
            String itemError = checkOne(evidence, sourceLocationCode, expectedVersion);
            if (itemError != null) {
                errors.add(new MovePreconditionException.ItemError(key, itemError));
            }
        }
        return errors;
    }

    private List<MovePreconditionException.ItemError> validateLocked(
            List<Evidence> locked, List<String> keys, String sourceLocationCode,
            long expectedVersion) {
        List<MovePreconditionException.ItemError> errors = new ArrayList<>();
        for (String key : keys) {
            Evidence evidence = locked.stream().filter(e -> e.evidenceKey().equals(key)).findFirst()
                    .orElse(null);
            if (evidence == null) {
                errors.add(new MovePreconditionException.ItemError(key, "证物不存在"));
                continue;
            }
            String itemError = checkOne(evidence, sourceLocationCode, expectedVersion);
            if (itemError != null) {
                errors.add(new MovePreconditionException.ItemError(key, itemError));
            }
        }
        return errors;
    }

    private String checkOne(Evidence evidence, String sourceLocationCode, long expectedVersion) {
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            return "封条异常（SEAL_BROKEN），禁止迁移";
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            return "证物已借出，不在库";
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            return "证物处于待核验（交接在途）状态，不在库";
        }
        if (evidence.locationCode() == null) {
            return "证物未上架，不在库";
        }
        if (!evidence.locationCode().equals(sourceLocationCode)) {
            return "当前库位 " + evidence.locationCode() + " 不等于源库位 " + sourceLocationCode;
        }
        if (evidence.version() != expectedVersion) {
            return "证物版本不匹配：期望 " + expectedVersion + "，实际 " + evidence.version();
        }
        return null;
    }

    private StorageLocation requireLocation(String locationCode) {
        return locationRepository.findByCode(locationCode)
                .orElseThrow(() -> ApiException.notFound("库位不存在: " + locationCode));
    }

    private LocationMove lockMove(String moveKey) {
        return moveRepository.findByKeyForUpdate(moveKey)
                .orElseThrow(() -> ApiException.notFound("位置迁移单不存在: " + moveKey));
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

    private LocationView toView(StorageLocation location) {
        return new LocationView(location.locationCode(), location.description(), location.status(),
                location.createdAt(), location.disabledAt());
    }

    private EvidenceView toEvidenceView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.status(),
                evidence.locationCode(), evidence.version(),
                evidence.createdAt(), evidence.updatedAt());
    }

    private MoveView toView(LocationMove move, List<String> keys) {
        return new MoveView(move.moveKey(), keys, move.sourceLocationCode(),
                move.targetLocationCode(), move.expectedVersion(), move.status(),
                move.firstConfirmer(), move.secondConfirmer(), move.createdAt(),
                move.firstConfirmedAt(), move.completedAt(), move.cancelledAt());
    }

    private SealSnapshotView toSnapshotView(SealSnapshot snapshot) {
        return new SealSnapshotView(snapshot.moveKey(), snapshot.evidenceKey(), snapshot.sealNo(),
                snapshot.evidenceStatus(), snapshot.custodianId(), snapshot.locationCode(),
                snapshot.version(), snapshot.firstConfirmer(), snapshot.secondConfirmer(),
                snapshot.snapshotAt());
    }
}
