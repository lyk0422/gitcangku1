package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionSubmitRequest;
import com.example.starter.evidence.dto.DestructionView;
import com.example.starter.evidence.dto.HoldBatchReleaseRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldView;
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
 * 证物保全冻结与销毁申请双向门禁 API。
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果，同键改参返回 409，失败不占键。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class RetentionController {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private final RetentionService retentionService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public RetentionController(RetentionService retentionService,
                               IdempotencyAdvisor idempotencyAdvisor) {
        this.retentionService = retentionService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 建立保全冻结：证物集合服务端规范化排序；区间重叠或证物已销毁返回 422/409。
     */
    @PostMapping("/holds")
    public ResponseEntity<String> createHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                             @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_HOLD_CREATE, actorId, "", request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> retentionService.createHold(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 批量解除冻结：逐项校验请求方与版本，任一失败整批回滚。
     */
    @PostMapping("/holds/batch-release")
    public ResponseEntity<String> batchRelease(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                               @Valid @RequestBody HoldBatchReleaseRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_HOLD_BATCH_RELEASE, actorId,
                "", request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> retentionService.batchReleaseHolds(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 提交销毁申请：先校验证物最终状态、封签与全部有效冻结；命中冻结返回 422 且不生成申请。
     */
    @PostMapping("/destruction-requests")
    public ResponseEntity<String> submitDestruction(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody DestructionSubmitRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_DESTRUCTION_SUBMIT, actorId,
                "", request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> retentionService.submitDestruction(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 完成销毁：仅申请提交方；被阻断申请须重新提交，解除/到期不自动批准。
     */
    @PostMapping("/destruction-requests/{requestKey}/complete")
    public ResponseEntity<String> completeDestruction(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String requestKey,
            @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_DESTRUCTION_COMPLETE, actorId,
                requestKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> retentionService.completeDestruction(actorId, requestKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询证物当前有效冻结。
     */
    @GetMapping("/{evidenceKey}/holds/effective")
    public List<HoldView> effectiveHolds(@PathVariable String evidenceKey) {
        return retentionService.listEffectiveHolds(evidenceKey);
    }

    /**
     * 查询证物冻结历史（含未生效/已过期/已解除）。
     */
    @GetMapping("/{evidenceKey}/holds/history")
    public List<HoldView> holdHistory(@PathVariable String evidenceKey) {
        return retentionService.listHoldHistory(evidenceKey);
    }

    /**
     * 查询销毁申请详情与不可变阻断快照。
     */
    @GetMapping("/destruction-requests/{requestKey}")
    public DestructionView destruction(@PathVariable String requestKey) {
        return retentionService.getDestruction(requestKey);
    }

    /**
     * 查询一件证物关联的销毁申请与阻断历史。
     */
    @GetMapping("/{evidenceKey}/destruction-requests")
    public List<DestructionView> destructionHistory(@PathVariable String evidenceKey) {
        return retentionService.listDestructionHistory(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
