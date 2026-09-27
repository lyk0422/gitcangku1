package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.MoveApplyRequest;
import com.example.starter.evidence.dto.MoveSealSnapshotView;
import com.example.starter.evidence.dto.MoveView;
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
 * 库位迁移 API。操作人通过 X-Actor-Id 请求头提供。
 * 申请以 moveKey 为幂等键：同键同参（证物集合换序视为同参）重放完整迁移结果，
 * 同键改参返回 409，校验失败不占键；确认与撤销携带 commandKey 保证单次生效。
 */
@RestController
@RequestMapping("/api/evidence-moves")
@Validated
public class MoveController {

    private final MoveService moveService;
    private final MoveOrchestrator moveOrchestrator;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public MoveController(MoveService moveService, MoveOrchestrator moveOrchestrator,
                          IdempotencyAdvisor idempotencyAdvisor) {
        this.moveService = moveService;
        this.moveOrchestrator = moveOrchestrator;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 迁移申请：全部证物须在库、封条正常且当前库位等于源库位，否则 422 逐项说明。
     */
    @PostMapping
    public ResponseEntity<String> apply(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                        @Valid @RequestBody MoveApplyRequest request) {
        StoredResponse response = moveOrchestrator.apply(actorId, request);
        return toEntity(response);
    }

    /**
     * 双人确认：首人确认进入待第二人确认；第二人（与首人不同）确认时整单校验并原子迁移。
     */
    @PostMapping("/{moveKey}/confirm")
    public ResponseEntity<String> confirm(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String moveKey,
                                          @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(MoveService.OP_MOVE_CONFIRM, actorId,
                moveKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.confirm(actorId, moveKey, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销迁移单：仅首人确认后可撤销；撤销后不可再确认，完成后不可撤销。
     */
    @PostMapping("/{moveKey}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String moveKey,
                                         @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(MoveService.OP_MOVE_CANCEL, actorId,
                moveKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.cancel(actorId, moveKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询迁移单（含完成后的逐件封签快照）。
     */
    @GetMapping("/{moveKey}")
    public MoveView getMove(@PathVariable String moveKey) {
        return moveService.getMove(moveKey);
    }

    /**
     * 查询迁移单的逐件封签快照。
     */
    @GetMapping("/{moveKey}/seal-snapshots")
    public List<MoveSealSnapshotView> listSnapshots(@PathVariable String moveKey) {
        return moveService.listSnapshots(moveKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
