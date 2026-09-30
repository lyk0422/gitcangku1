package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseHoldsView;
import com.example.starter.evidence.dto.DestructionRequest;
import com.example.starter.evidence.dto.EvidenceHoldsView;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 保留冻结与销毁申请 API。写操作携带 commandKey 保证幂等：
 * 同键同参重放返回首次结果（含首次 409），同键改参返回 409。
 * 销毁申请被有效冻结阻断时以 409 正常响应提交 REJECTED 历史，证物状态不变。
 */
@RestController
@Validated
public class RetentionHoldController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final RetentionHoldService holdService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public RetentionHoldController(RetentionHoldService holdService,
                                   IdempotencyAdvisor idempotencyAdvisor) {
        this.holdService = holdService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建案件级冻结：作用于案件下全部证物，UTC 到期时刻后自动不再阻断。
     */
    @PostMapping("/api/cases/{caseKey}/holds")
    public ResponseEntity<String> createCaseHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String caseKey,
                                                 @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionHoldService.OP_HOLD_CASE, actorId,
                caseKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.createCaseHold(actorId, caseKey, request, hash));
        return toEntity(response);
    }

    /**
     * 创建单件证物级冻结。
     */
    @PostMapping("/api/evidence/{evidenceKey}/holds")
    public ResponseEntity<String> createEvidenceHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                     @PathVariable String evidenceKey,
                                                     @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionHoldService.OP_HOLD_EVIDENCE, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.createEvidenceHold(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 解除冻结：须由不同于创建人的保管人签明原因；解除只影响后续销毁申请。
     */
    @PostMapping("/api/holds/{holdKey}/release")
    public ResponseEntity<String> releaseHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String holdKey,
                                              @Valid @RequestBody HoldReleaseRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionHoldService.OP_HOLD_RELEASE, actorId,
                holdKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.releaseHold(actorId, holdKey, request, hash));
        return toEntity(response);
    }

    /**
     * 销毁申请：同时检查证物级与案件级有效冻结；被阻断返回 409 且证物状态不变。
     */
    @PostMapping("/api/evidence/{evidenceKey}/destructions")
    public ResponseEntity<String> requestDestruction(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                     @PathVariable String evidenceKey,
                                                     @Valid @RequestBody DestructionRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionHoldService.OP_DESTROY, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.requestDestruction(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询案件全部案件级冻结（含生效、已解除、已到期）。
     */
    @GetMapping("/api/cases/{caseKey}/holds")
    public CaseHoldsView listCaseHolds(@PathVariable String caseKey) {
        return holdService.listCaseHolds(caseKey);
    }

    /**
     * 查询证物两级冻结：案件级 + 证物级，展示有效状态、到期与解除链。
     */
    @GetMapping("/api/evidence/{evidenceKey}/holds")
    public EvidenceHoldsView listEvidenceHolds(@PathVariable String evidenceKey) {
        return holdService.listEvidenceHolds(evidenceKey);
    }

    /**
     * 查询证物全部销毁申请历史（含被拒记录）。
     */
    @GetMapping("/api/evidence/{evidenceKey}/destructions")
    public List<com.example.starter.evidence.dto.DestructionView> listDestructions(
            @PathVariable String evidenceKey) {
        return holdService.listDestructions(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
