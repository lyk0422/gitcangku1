package com.example.starter.api;

import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.DefinePolicyRequest;
import com.example.starter.api.dto.MigratePoliciesRequest;
import com.example.starter.api.dto.MigrationResponse;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.ProvenanceDiagnosticResponse;
import com.example.starter.api.dto.PublishLockRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;
import com.example.starter.api.dto.SubmitAttestationRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 制品来源证明策略与锁定图发布门禁 REST API。
 */
@RestController
@RequestMapping("/api/provenance")
@Validated
public class ProvenanceController {

    private final ProvenanceService provenanceService;

    public ProvenanceController(ProvenanceService provenanceService) {
        this.provenanceService = provenanceService;
    }

    /** 为锁定图定义新的策略版本。 */
    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> definePolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(name = "X-Operator", required = false) String operatorHeader,
            @Valid @RequestBody DefinePolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                provenanceService.definePolicy(requestId, operatorHeader, request));
    }

    /** 查询策略版本；不传 version 返回最新版本。 */
    @GetMapping("/policies/{lockName}")
    public PolicyResponse getPolicy(
            @PathVariable String lockName,
            @RequestParam(name = "version", required = false) Integer version) {
        return provenanceService.getPolicy(lockName, version);
    }

    /** 提交制品坐标来源证明。 */
    @PostMapping("/attestations")
    public ResponseEntity<AttestationResponse> submitAttestation(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(name = "X-Operator", required = false) String operatorHeader,
            @Valid @RequestBody SubmitAttestationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                provenanceService.submitAttestation(requestId, operatorHeader, request));
    }

    /** 撤销来源证明。 */
    @PostMapping("/attestations/{id}/revoke")
    public AttestationResponse revokeAttestation(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable @Positive long id) {
        return provenanceService.revokeAttestation(requestId, id);
    }

    /** 批量策略迁移（先预校验全部锁定图的最终命中，原子生效）。 */
    @PostMapping("/migrations")
    public ResponseEntity<MigrationResponse> migrate(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(name = "X-Operator", required = false) String operatorHeader,
            @Valid @RequestBody MigratePoliciesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                provenanceService.migratePolicies(requestId, operatorHeader, request));
    }

    /** 发布锁定图，固化策略与证明版本。 */
    @PostMapping("/releases")
    public ResponseEntity<ReleaseSnapshotResponse> publish(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(name = "X-Operator", required = false) String operatorHeader,
            @Valid @RequestBody PublishLockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                provenanceService.publishLock(requestId, operatorHeader, request));
    }

    /** 查询发布快照。 */
    @GetMapping("/releases/locks/{lockFileId}")
    public ReleaseSnapshotResponse getRelease(@PathVariable @Positive long lockFileId) {
        return provenanceService.getRelease(lockFileId);
    }

    /** 查询来源路径、策略版本与发布阻断诊断。 */
    @GetMapping("/diagnostics/locks/{lockFileId}")
    public ProvenanceDiagnosticResponse diagnose(@PathVariable @Positive long lockFileId) {
        return provenanceService.diagnose(lockFileId);
    }
}
