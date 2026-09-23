package com.example.starter.restitution.web;

import com.example.starter.restitution.dto.AddEvidenceRequest;
import com.example.starter.restitution.dto.CaseDetailResponse;
import com.example.starter.restitution.dto.CaseResponse;
import com.example.starter.restitution.dto.CreateCaseRequest;
import com.example.starter.restitution.dto.DecisionRequest;
import com.example.starter.restitution.dto.DecisionResponse;
import com.example.starter.restitution.dto.RegisterClaimRequest;
import com.example.starter.restitution.service.IdempotentExecutor;
import com.example.starter.restitution.service.RestitutionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合成藏品归还裁决 HTTP 入口；身份取 X-Actor-Id，写操作须带全局 X-Request-Id。
 */
@RestController
@RequestMapping("/api/cases")
public class RestitutionController {

    private final RestitutionService service;
    private final IdempotentExecutor idempotency;

    public RestitutionController(RestitutionService service, IdempotentExecutor idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<CaseResponse> createCase(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody CreateCaseRequest request) {
        IdempotentExecutor.Outcome<CaseResponse> outcome = idempotency.execute(
                requestId, actor, "POST /api/cases", request, CaseResponse.class,
                HttpStatus.CREATED.value(), () -> service.createCase(request));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/{caseId}")
    public CaseDetailResponse getCase(@PathVariable String caseId) {
        return service.getCase(caseId);
    }

    @PostMapping("/{caseId}/claims")
    public ResponseEntity<CaseResponse> registerClaim(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String caseId,
            @Valid @RequestBody RegisterClaimRequest request) {
        IdempotentExecutor.Outcome<CaseResponse> outcome = idempotency.execute(
                requestId, actor, "POST /api/cases/{caseId}/claims:" + caseId,
                request, CaseResponse.class, HttpStatus.CREATED.value(),
                () -> service.registerClaim(caseId, request));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @DeleteMapping("/{caseId}/claims/{claimKey}")
    public ResponseEntity<CaseResponse> withdrawClaim(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String caseId,
            @PathVariable String claimKey) {
        IdempotentExecutor.Outcome<CaseResponse> outcome = idempotency.execute(
                requestId, actor,
                "DELETE /api/cases/{caseId}/claims/{claimKey}:" + caseId + ":" + claimKey,
                null, CaseResponse.class, HttpStatus.OK.value(),
                () -> service.withdrawClaim(caseId, claimKey));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{caseId}/claims/{claimKey}/evidences")
    public ResponseEntity<CaseResponse> addEvidence(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String caseId,
            @PathVariable String claimKey,
            @Valid @RequestBody AddEvidenceRequest request) {
        IdempotentExecutor.Outcome<CaseResponse> outcome = idempotency.execute(
                requestId, actor,
                "POST /api/cases/{caseId}/claims/{claimKey}/evidences:" + caseId + ":" + claimKey,
                request, CaseResponse.class, HttpStatus.CREATED.value(),
                () -> service.addEvidence(caseId, claimKey, request));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @DeleteMapping("/{caseId}/claims/{claimKey}/evidences/{evidenceKey}")
    public ResponseEntity<CaseResponse> revokeEvidence(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String caseId,
            @PathVariable String claimKey,
            @PathVariable String evidenceKey) {
        IdempotentExecutor.Outcome<CaseResponse> outcome = idempotency.execute(
                requestId, actor,
                "DELETE /api/cases/{caseId}/claims/{claimKey}/evidences/{evidenceKey}:"
                        + caseId + ":" + claimKey + ":" + evidenceKey,
                null, CaseResponse.class, HttpStatus.OK.value(),
                () -> service.revokeEvidence(caseId, claimKey, evidenceKey));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{caseId}/claims/{claimKey}/approvals")
    public ResponseEntity<CaseResponse> approve(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String caseId,
            @PathVariable String claimKey) {
        IdempotentExecutor.Outcome<CaseResponse> outcome = idempotency.execute(
                requestId, actor,
                "POST /api/cases/{caseId}/claims/{claimKey}/approvals:" + caseId + ":" + claimKey,
                null, CaseResponse.class, HttpStatus.CREATED.value(),
                () -> service.approve(caseId, claimKey, actor));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PostMapping("/{caseId}/decisions")
    public ResponseEntity<DecisionResponse> decide(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String caseId,
            @Valid @RequestBody DecisionRequest request) {
        IdempotentExecutor.Outcome<DecisionResponse> outcome = idempotency.execute(
                requestId, actor, "POST /api/cases/{caseId}/decisions:" + caseId,
                request, DecisionResponse.class, HttpStatus.OK.value(),
                () -> service.decide(caseId, request));
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
