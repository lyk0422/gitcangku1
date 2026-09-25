package com.example.starter.api;

import com.example.starter.api.dto.AttestationRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.CreatePolicyRequest;
import com.example.starter.api.dto.MigrationCheckRequest;
import com.example.starter.api.dto.MigrationCheckResponse;
import com.example.starter.api.dto.PolicyResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
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
 * 制品来源证明 REST API：策略版本、证明登记与撤销、批量迁移预校验。
 */
@RestController
@RequestMapping("/api/provenance")
@Validated
public class ProvenanceController {

    private final ArtifactService artifactService;

    public ProvenanceController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /** 追加一个来源策略版本（历史版本不可改写）。 */
    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> createPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody CreatePolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.createPolicyVersion(requestId, request));
    }

    /** 查询全部策略版本。 */
    @GetMapping("/policies")
    public List<PolicyResponse> listPolicies() {
        return artifactService.listPolicies();
    }

    /** 批量策略迁移预校验：候选策略对全部锁定图最终命中的评估，只读。 */
    @PostMapping("/policies/migrate-check")
    public MigrationCheckResponse migrateCheck(@Valid @RequestBody MigrationCheckRequest request) {
        return artifactService.checkMigration(request);
    }

    /** 登记坐标来源证明。 */
    @PostMapping("/attestations")
    public ResponseEntity<AttestationResponse> attest(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody AttestationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.attest(requestId, request));
    }

    /** 撤销坐标当前证明。 */
    @PostMapping("/attestations/{name}/versions/{version}/revoke")
    public AttestationResponse revoke(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.revokeAttestation(requestId, name, version);
    }
}
