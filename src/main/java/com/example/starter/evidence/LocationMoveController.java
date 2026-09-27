package com.example.starter.evidence;

import com.example.starter.evidence.dto.EvidenceShelveRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.LocationStockView;
import com.example.starter.evidence.dto.MoveCommandRequest;
import com.example.starter.evidence.dto.MoveCreateRequest;
import com.example.starter.evidence.dto.MoveView;
import com.example.starter.evidence.dto.SealSnapshotView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 证物位置封签 API：库位建档/停用/库存查询、证物上架，以及位置迁移申请、双人确认、撤销与封签快照查询。
 * 操作人通过 X-Actor-Id 提供；写操作携带 commandKey 幂等。
 * 迁移指纹包含操作者、规范化证物集合（去重、字典序）、源/目标库位与 expectedVersion，集合换序视为同参。
 */
@RestController
@Validated
public class LocationMoveController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final LocationMoveService moveService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public LocationMoveController(LocationMoveService moveService,
                                  IdempotencyAdvisor idempotencyAdvisor) {
        this.moveService = moveService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 库位建档：location_code 全局唯一，初始 ACTIVE。
     */
    @PostMapping("/api/locations")
    public ResponseEntity<String> createLocation(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @Valid @RequestBody LocationCreateRequest request) {
        String hash = idempotencyAdvisor.hash(LocationMoveService.OP_LOCATION_CREATE, actorId,
                request.locationCode(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.createLocation(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 停用库位：停用后不可再作为迁移目标。
     */
    @PostMapping("/api/locations/{locationCode}/disable")
    public ResponseEntity<String> disableLocation(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                  @PathVariable String locationCode,
                                                  @Valid @RequestBody MoveCommandRequest request) {
        String hash = idempotencyAdvisor.hash(LocationMoveService.OP_LOCATION_DISABLE, actorId,
                locationCode, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.disableLocation(actorId, locationCode, request, hash));
        return toEntity(response);
    }

    /**
     * 查询库位库存：库位信息 + 当前在库证物。
     */
    @GetMapping("/api/locations/{locationCode}/stock")
    public LocationStockView stock(@PathVariable String locationCode) {
        return moveService.stock(locationCode);
    }

    /**
     * 证物首次上架：仅当前保管人，证物须 SEALED 且未上架。
     */
    @PostMapping("/api/evidence/{evidenceKey}/shelve")
    public ResponseEntity<String> shelve(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String evidenceKey,
                                         @Valid @RequestBody EvidenceShelveRequest request) {
        String hash = idempotencyAdvisor.hash(LocationMoveService.OP_EVIDENCE_SHELVE, actorId,
                evidenceKey + "|" + request.locationCode(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.shelve(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 位置迁移申请：含证物集合、源库位、目标库位与 expectedVersion。
     */
    @PostMapping("/api/location-moves")
    public ResponseEntity<String> createMove(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                             @Valid @RequestBody MoveCreateRequest request) {
        // 指纹基于规范化（去重、字典序）后的证物集合，集合换序/重复键视为同参。
        MoveCreateRequest canonical = new MoveCreateRequest(request.commandKey(), request.moveKey(),
                LocationMoveService.normalizeKeys(request.evidenceKeys()),
                request.sourceLocationCode(), request.targetLocationCode(), request.expectedVersion());
        String hash = idempotencyAdvisor.hash(LocationMoveService.OP_MOVE_CREATE, actorId,
                request.moveKey(), canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.createMove(actorId, canonical, hash));
        return toEntity(response);
    }

    /**
     * 首人确认：复查集合前置条件后等待第二名不同保管人。
     */
    @PostMapping("/api/location-moves/{moveKey}/confirm-first")
    public ResponseEntity<String> confirmFirst(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                               @PathVariable String moveKey,
                                               @Valid @RequestBody MoveCommandRequest request) {
        String hash = idempotencyAdvisor.hash(LocationMoveService.OP_MOVE_CONFIRM_FIRST, actorId,
                moveKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.confirmFirst(actorId, moveKey, request, hash));
        return toEntity(response);
    }

    /**
     * 第二人确认：与首人不同，一个事务内复查、整单更新库位并写双人记录与逐件封签快照。
     */
    @PostMapping("/api/location-moves/{moveKey}/confirm-second")
    public ResponseEntity<String> confirmSecond(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @PathVariable String moveKey,
                                                @Valid @RequestBody MoveCommandRequest request) {
        String hash = idempotencyAdvisor.hash(LocationMoveService.OP_MOVE_CONFIRM_SECOND, actorId,
                moveKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.confirmSecond(actorId, moveKey, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销迁移单：仅首人确认后、仅首人可撤销。
     */
    @PostMapping("/api/location-moves/{moveKey}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String moveKey,
                                         @Valid @RequestBody MoveCommandRequest request) {
        String hash = idempotencyAdvisor.hash(LocationMoveService.OP_MOVE_CANCEL, actorId,
                moveKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.cancel(actorId, moveKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询迁移单（含规范化证物集合）。
     */
    @GetMapping("/api/location-moves/{moveKey}")
    public MoveView getMove(@PathVariable String moveKey) {
        return moveService.getMove(moveKey);
    }

    /**
     * 查询迁移单逐件封签核验快照。
     */
    @GetMapping("/api/location-moves/{moveKey}/snapshots")
    public List<SealSnapshotView> snapshots(@PathVariable String moveKey) {
        return moveService.snapshots(moveKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
