package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.MoveCreateRequest;
import com.example.starter.evidence.dto.MoveRecordView;
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
 * 证物库位迁移 API：迁移申请、双人确认、撤销与查询。
 * moveKey 为迁移幂等键，指纹含操作者、规范化证物集合、源目标库位与版本；
 * 同键同参成功重放完整结果，失败不占键；确认/撤销使用各自 commandKey 幂等。
 */
@RestController
@RequestMapping("/api/moves")
@Validated
public class MoveController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final MoveService moveService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public MoveController(MoveService moveService, IdempotencyAdvisor idempotencyAdvisor) {
        this.moveService = moveService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建迁移申请：全部证物须在库、封条正常且当前库位等于源库位，否则 422 逐项说明。
     */
    @PostMapping
    public ResponseEntity<String> createMove(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                             @Valid @RequestBody MoveCreateRequest request) {
        String hash = idempotencyAdvisor.hash(MoveService.OP_MOVE_CREATE, actorId,
                request.moveKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.moveKey(), hash,
                () -> moveService.createMove(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 确认迁移：两名不同保管人先后确认；第二人确认在同一事务内复查完整集合并原子执行迁移。
     */
    @PostMapping("/{moveKey}/confirm")
    public ResponseEntity<String> confirm(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String moveKey,
                                          @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(MoveService.OP_MOVE_CONFIRM, actorId,
                moveKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.confirm(actorId, moveKey, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销迁移单：首人确认后可撤销；撤销后不可再确认，完成后不可撤销。
     */
    @PostMapping("/{moveKey}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String moveKey,
                                         @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(MoveService.OP_MOVE_CANCEL, actorId,
                moveKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> moveService.cancel(actorId, moveKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询迁移单当前状态。
     */
    @GetMapping("/{moveKey}")
    public MoveView getMove(@PathVariable String moveKey) {
        return moveService.getMove(moveKey);
    }

    /**
     * 查询不可变双人迁移记录（迁移完成后存在）。
     */
    @GetMapping("/{moveKey}/record")
    public MoveRecordView getRecord(@PathVariable String moveKey) {
        return moveService.getRecord(moveKey);
    }

    /**
     * 查询迁移单逐件封签核验快照。
     */
    @GetMapping("/{moveKey}/snapshots")
    public List<SealSnapshotView> listSnapshots(@PathVariable String moveKey) {
        return moveService.listSnapshots(moveKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
