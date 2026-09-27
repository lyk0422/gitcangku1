package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.LocationInventoryView;
import com.example.starter.evidence.dto.MoveApplyRequest;
import com.example.starter.evidence.dto.MoveCancelRequest;
import com.example.starter.evidence.dto.MoveConfirmRequest;
import com.example.starter.evidence.dto.MoveOrderView;
import com.example.starter.evidence.dto.MoveSealSnapshotView;
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
 * 证物位置封签（库位迁移）API。
 * 迁移申请（首人确认）、第二人确认、撤销均携带幂等命令键；
 * moveKey 指纹含操作者、规范化证物集合（换序视为同参）、源/目标库位与版本，
 * 同键成功重放完整迁移结果，失败不占键。
 */
@RestController
@RequestMapping("/api")
@Validated
public class LocationMoveController {

    private final LocationMoveService locationMoveService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public LocationMoveController(LocationMoveService locationMoveService,
                                  IdempotencyAdvisor idempotencyAdvisor) {
        this.locationMoveService = locationMoveService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建库位：locationCode 全局唯一，初始 ACTIVE。
     */
    @PostMapping("/locations")
    public ResponseEntity<String> createLocation(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody LocationCreateRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_LOCATION_CREATE, actorId,
                request.locationCode(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> locationMoveService.createLocation(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 停用库位：停用后不可作为迁移目标，历史迁移不受影响。
     */
    @PostMapping("/locations/{locationCode}/disable")
    public ResponseEntity<String> disableLocation(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String locationCode,
            @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_LOCATION_DISABLE, actorId,
                locationCode, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> locationMoveService.disableLocation(actorId, locationCode, request, hash));
        return toEntity(response);
    }

    /**
     * 查询库位库存：库位信息与当前位于该库位的全部证物。
     */
    @GetMapping("/locations/{locationCode}/inventory")
    public LocationInventoryView locationInventory(@PathVariable String locationCode) {
        return locationMoveService.locationInventory(locationCode);
    }

    /**
     * 迁移申请（首人确认）：命令键即 moveKey；证物集合换序视为同参。
     */
    @PostMapping("/moves")
    public ResponseEntity<String> applyMove(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody MoveApplyRequest request) {
        // 规范化证物集合后计算指纹：换序请求得到相同指纹，视为同参。
        MoveApplyRequest canonical = new MoveApplyRequest(request.commandKey(),
                LocationMoveService.normalizeKeys(request.evidenceKeys()),
                request.sourceLocationCode(), request.targetLocationCode(),
                request.expectedVersion());
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_MOVE_APPLY, actorId,
                request.commandKey(), canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> locationMoveService.applyMove(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 第二人确认：与首人不同；单事务内复查集合并原子迁移、写入封签快照。
     */
    @PostMapping("/moves/confirm")
    public ResponseEntity<String> confirmMove(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody MoveConfirmRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_MOVE_CONFIRM, actorId,
                request.moveKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> locationMoveService.confirmMove(actorId, request.moveKey(),
                        request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 撤销迁移单：仅首人确认人，且单据仍待第二人确认。
     */
    @PostMapping("/moves/cancel")
    public ResponseEntity<String> cancelMove(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody MoveCancelRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_MOVE_CANCEL, actorId,
                request.moveKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> locationMoveService.cancelMove(actorId, request.moveKey(),
                        request.commandKey(), hash));
        return toEntity(response);
    }

    /**
     * 查询全部迁移单（按创建顺序）。
     */
    @GetMapping("/moves")
    public List<MoveOrderView> listMoves() {
        return locationMoveService.listMoves();
    }

    /**
     * 按证物查询其全部迁移封签核验快照。
     */
    @GetMapping("/moves/by-evidence/{evidenceKey}")
    public List<MoveSealSnapshotView> snapshotsByEvidence(@PathVariable String evidenceKey) {
        return locationMoveService.snapshotsByEvidence(evidenceKey);
    }

    /**
     * 查询单个迁移单及逐件封签核验快照。
     */
    @GetMapping("/moves/{moveKey}")
    public LocationMoveService.MoveResultView moveDetail(@PathVariable String moveKey) {
        return locationMoveService.moveDetail(moveKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
