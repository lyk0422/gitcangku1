package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionRequestView;
import com.example.starter.evidence.dto.DestructionSubmitRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
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
 * 证物保全冻结与销毁申请 API。操作人通过 X-Actor-Id 请求头提供；
 * 冻结创建以 holdKey、销毁申请以 requestKey 兼作幂等命令键：
 * 同键同参重放返回首次结果，同键改参返回 409，失败不占键。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class HoldController {

    private final HoldService holdService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public HoldController(HoldService holdService, IdempotencyAdvisor idempotencyAdvisor) {
        this.holdService = holdService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建保全冻结：证物集合规范化排序，生效区间 UTC 左闭右开，不得覆盖过去；
     * 同一证物重叠有效冻结返回 409。
     */
    @PostMapping("/holds")
    public ResponseEntity<String> createHold(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(HoldService.OP_HOLD_CREATE, actorId,
                request.holdKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.holdKey(), hash,
                () -> holdService.createHold(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 批量解除冻结：先校验请求方与冻结版本，任一失败整批回滚。
     */
    @PostMapping("/holds/release")
    public ResponseEntity<String> releaseHolds(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody HoldReleaseRequest request) {
        String hash = idempotencyAdvisor.hash(HoldService.OP_HOLD_RELEASE, actorId,
                null, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.releaseHolds(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 提交销毁申请：校验最终证物状态、封签与所有有效冻结；
     * 任一命中冻结返回 422 并稳定列出 holdKey，不生成部分申请。
     */
    @PostMapping("/destruction-requests")
    public ResponseEntity<String> submitDestruction(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @Valid @RequestBody DestructionSubmitRequest request) {
        String hash = idempotencyAdvisor.hash(HoldService.OP_DESTRUCTION_SUBMIT, actorId,
                request.requestKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestKey(), hash,
                () -> holdService.submitDestruction(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 完成销毁：仅申请操作人，申请须仍待审；成功后证物进入 DESTROYED 终态。
     */
    @PostMapping("/destruction-requests/{requestKey}/complete")
    public ResponseEntity<String> completeDestruction(
            @RequestHeader(EvidenceController.ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String requestKey,
            @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(HoldService.OP_DESTRUCTION_COMPLETE, actorId,
                requestKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.completeDestruction(actorId, requestKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询指定证物当前时刻的有效冻结。
     */
    @GetMapping("/{evidenceKey}/holds/effective")
    public List<HoldView> listEffectiveHolds(@PathVariable String evidenceKey) {
        return holdService.listEffectiveHolds(evidenceKey);
    }

    /**
     * 查询指定证物的全部冻结历史快照（含已解除）。
     */
    @GetMapping("/{evidenceKey}/holds")
    public List<HoldView> listHoldHistory(@PathVariable String evidenceKey) {
        return holdService.listHoldHistory(evidenceKey);
    }

    /**
     * 查询指定证物的全部销毁申请（含阻断记录与冻结快照）。
     */
    @GetMapping("/{evidenceKey}/destruction-requests")
    public List<DestructionRequestView> listDestructionRequests(@PathVariable String evidenceKey) {
        return holdService.listDestructionRequests(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
