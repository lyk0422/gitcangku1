package com.example.starter.evidence;

import com.example.starter.evidence.dto.DestructionApplyRequest;
import com.example.starter.evidence.dto.DestructionView;
import com.example.starter.evidence.dto.HoldChainView;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
import com.example.starter.evidence.dto.HoldView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.function.Supplier;

/**
 * 保留冻结与销毁 API。操作人通过 X-Actor-Id 提供；
 * 冻结、解除、销毁各以 requestId 作为全局幂等键：同键同参重放首次结果，同键改参返回 409。
 * 并发同键插入触发唯一约束冲突时，在事务外重试一次，由服务层回放先提交事务的首次结果。
 */
@RestController
@RequestMapping("/api")
@Validated
public class RetentionController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final RetentionService retentionService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public RetentionController(RetentionService retentionService,
                               IdempotencyAdvisor idempotencyAdvisor) {
        this.retentionService = retentionService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建案件级冻结：覆盖案件下全部证物，到期时刻为 UTC。
     */
    @PostMapping("/cases/{caseKey}/holds")
    public ResponseEntity<String> placeCaseHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @PathVariable String caseKey,
                                                @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_HOLD_PLACE, actorId,
                "CASE:" + caseKey, request);
        return toEntity(withDuplicateRetry(
                () -> retentionService.placeCaseHold(actorId, caseKey, request, hash)));
    }

    /**
     * 创建单件证物冻结。
     */
    @PostMapping("/evidence/{evidenceKey}/holds")
    public ResponseEntity<String> placeEvidenceHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                    @PathVariable String evidenceKey,
                                                    @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_HOLD_PLACE, actorId,
                "EVIDENCE:" + evidenceKey, request);
        return toEntity(withDuplicateRetry(
                () -> retentionService.placeEvidenceHold(actorId, evidenceKey, request, hash)));
    }

    /**
     * 解除冻结：须由不同于创建人的保管人签明原因；解除只影响后续销毁申请。
     */
    @PostMapping("/holds/{holdId}/release")
    public ResponseEntity<String> releaseHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable Long holdId,
                                              @Valid @RequestBody HoldReleaseRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_HOLD_RELEASE, actorId,
                "HOLD:" + holdId, request);
        return toEntity(withDuplicateRetry(
                () -> retentionService.releaseHold(actorId, holdId, request, hash)));
    }

    /**
     * 销毁申请：同时检查证物级与案件级有效冻结；被拒返回 409 且证物状态不变，裁决落库。
     */
    @PostMapping("/evidence/{evidenceKey}/destructions")
    public ResponseEntity<String> applyDestruction(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                   @PathVariable String evidenceKey,
                                                   @Valid @RequestBody DestructionApplyRequest request) {
        String hash = idempotencyAdvisor.hash(RetentionService.OP_DESTRUCTION_APPLY, actorId,
                evidenceKey, request);
        return toEntity(withDuplicateRetry(
                () -> retentionService.applyDestruction(actorId, evidenceKey, request, hash)));
    }

    /**
     * 查询单件证物的证物级冻结（含有效状态、到期与解除链）。
     */
    @GetMapping("/evidence/{evidenceKey}/holds")
    public List<HoldView> listEvidenceHolds(@PathVariable String evidenceKey) {
        return retentionService.listEvidenceHolds(evidenceKey);
    }

    /**
     * 查询案件两级冻结链与案件下全部销毁申请历史。
     */
    @GetMapping("/cases/{caseKey}/holds")
    public HoldChainView caseHoldChain(@PathVariable String caseKey) {
        return retentionService.caseHoldChain(caseKey);
    }

    /**
     * 查询单件证物的全部销毁申请历史（含过去被拒记录）。
     */
    @GetMapping("/evidence/{evidenceKey}/destructions")
    public List<DestructionView> listDestructions(@PathVariable String evidenceKey) {
        return retentionService.listDestructions(evidenceKey);
    }

    /**
     * 并发同 requestId 写入时唯一约束冲突：先提交事务已落库，重试一次走重放分支。
     */
    private StoredResponse withDuplicateRetry(Supplier<StoredResponse> call) {
        try {
            return call.get();
        } catch (DuplicateKeyException e) {
            return call.get();
        }
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
