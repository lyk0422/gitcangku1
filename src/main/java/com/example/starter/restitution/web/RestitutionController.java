package com.example.starter.restitution.web;

import com.example.starter.restitution.service.RestitutionService;
import com.example.starter.restitution.web.dto.AddEvidenceRequest;
import com.example.starter.restitution.web.dto.CaseResponse;
import com.example.starter.restitution.web.dto.ClaimResponse;
import com.example.starter.restitution.web.dto.CreateCaseRequest;
import com.example.starter.restitution.web.dto.DecideRequest;
import com.example.starter.restitution.web.dto.DecisionResponse;
import com.example.starter.restitution.web.dto.EvidenceResponse;
import com.example.starter.restitution.web.dto.RegisterClaimRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 藏品归还裁决 HTTP 入口；身份取 X-Actor-Id，写操作必须携带 X-Request-Id。
 */
@RestController
@RequestMapping("/api/cases")
public class RestitutionController {

    private final RestitutionService service;

    public RestitutionController(RestitutionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaseResponse createCase(@RequestHeader("X-Actor-Id") String actorId,
                                   @RequestHeader("X-Request-Id") String requestId,
                                   @Valid @RequestBody CreateCaseRequest req) {
        return service.createCase(actorId, requestId, req.artifactNos());
    }

    @GetMapping("/{caseKey}")
    public CaseResponse getCase(@PathVariable String caseKey) {
        return service.getCase(caseKey);
    }

    @PostMapping("/{caseKey}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimResponse registerClaim(@RequestHeader("X-Actor-Id") String actorId,
                                       @RequestHeader("X-Request-Id") String requestId,
                                       @PathVariable String caseKey,
                                       @Valid @RequestBody RegisterClaimRequest req) {
        return service.registerClaim(actorId, requestId, caseKey, req);
    }

    @PostMapping("/{caseKey}/claims/{claimKey}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> withdrawClaim(@RequestHeader("X-Actor-Id") String actorId,
                                              @RequestHeader("X-Request-Id") String requestId,
                                              @PathVariable String caseKey,
                                              @PathVariable String claimKey) {
        service.withdrawClaim(actorId, requestId, caseKey, claimKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{caseKey}/claims")
    public List<ClaimResponse> listClaims(@PathVariable String caseKey) {
        return service.listClaims(caseKey);
    }

    @PostMapping("/{caseKey}/claims/{claimKey}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceResponse addEvidence(@RequestHeader("X-Actor-Id") String actorId,
                                        @RequestHeader("X-Request-Id") String requestId,
                                        @PathVariable String caseKey,
                                        @PathVariable String claimKey,
                                        @Valid @RequestBody AddEvidenceRequest req) {
        return service.addEvidence(actorId, requestId, caseKey, claimKey, req);
    }

    @PostMapping("/{caseKey}/evidence/{evidenceKey}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> revokeEvidence(@RequestHeader("X-Actor-Id") String actorId,
                                               @RequestHeader("X-Request-Id") String requestId,
                                               @PathVariable String caseKey,
                                               @PathVariable String evidenceKey) {
        service.revokeEvidence(actorId, requestId, caseKey, evidenceKey);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{caseKey}/claims/{claimKey}/evidence")
    public List<EvidenceResponse> listEvidence(@PathVariable String caseKey,
                                               @PathVariable String claimKey) {
        return service.listEvidence(caseKey, claimKey);
    }

    @PostMapping("/{caseKey}/claims/{claimKey}/approvals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> approve(@RequestHeader("X-Actor-Id") String actorId,
                                        @RequestHeader("X-Request-Id") String requestId,
                                        @PathVariable String caseKey,
                                        @PathVariable String claimKey) {
        service.approve(actorId, requestId, caseKey, claimKey);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{caseKey}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionResponse decide(@RequestHeader("X-Actor-Id") String actorId,
                                   @RequestHeader("X-Request-Id") String requestId,
                                   @PathVariable String caseKey,
                                   @Valid @RequestBody DecideRequest req) {
        return service.decide(actorId, requestId, caseKey, req);
    }

    @GetMapping("/{caseKey}/decision")
    public DecisionResponse getDecision(@PathVariable String caseKey) {
        return service.getDecision(caseKey);
    }
}
