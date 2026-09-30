package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseHoldsView;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 保留冻结 API。
 * 案件级：POST /api/cases/{caseKey}/retention-holds；
 * 证物级：POST /api/evidence/{evidenceKey}/retention-holds；
 * 解除：POST /api/retention-holds/{holdRequestId}/release；
 * 查询：GET /api/cases/{caseKey}/retention-holds、GET /api/evidence/{evidenceKey}/retention-holds。
 * 写操作的 commandKey 即冻结/解除 requestId：同键同参重放首次状态，改对象或到期时间返回 409。
 */
@RestController
@Validated
public class HoldController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final HoldService holdService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public HoldController(HoldService holdService, IdempotencyAdvisor idempotencyAdvisor) {
        this.holdService = holdService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建案件级保留冻结，作用于案件下全部证物。
     */
    @PostMapping("/api/cases/{caseKey}/retention-holds")
    public ResponseEntity<String> createCaseHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String caseKey,
                                                 @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(HoldService.OP_HOLD_CREATE, actorId,
                "CASE:" + caseKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.createCaseHold(actorId, caseKey, request, hash));
        return toEntity(response);
    }

    /**
     * 创建单件证物级保留冻结。
     */
    @PostMapping("/api/evidence/{evidenceKey}/retention-holds")
    public ResponseEntity<String> createEvidenceHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                     @PathVariable String evidenceKey,
                                                     @Valid @RequestBody HoldCreateRequest request) {
        String hash = idempotencyAdvisor.hash(HoldService.OP_HOLD_CREATE, actorId,
                "EVIDENCE:" + evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.createEvidenceHold(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 解除冻结：X-Actor-Id 必须不同于冻结创建人并写明原因。
     */
    @PostMapping("/api/retention-holds/{holdRequestId}/release")
    public ResponseEntity<String> releaseHold(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String holdRequestId,
                                              @Valid @RequestBody HoldReleaseRequest request) {
        String hash = idempotencyAdvisor.hash(HoldService.OP_HOLD_RELEASE, actorId,
                holdRequestId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> holdService.releaseHold(actorId, holdRequestId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询案件两级冻结（案件级 + 案件下证物的证物级）。
     */
    @GetMapping("/api/cases/{caseKey}/retention-holds")
    public CaseHoldsView caseHolds(@PathVariable String caseKey) {
        return holdService.caseHolds(caseKey);
    }

    /**
     * 查询单件证物的证物级冻结。
     */
    @GetMapping("/api/evidence/{evidenceKey}/retention-holds")
    public List<HoldView> evidenceHolds(@PathVariable String evidenceKey) {
        return holdService.evidenceHolds(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
