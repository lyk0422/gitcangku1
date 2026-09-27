package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.AttestationStatusResponse;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;
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
 * 软件制品依赖锁定与内容校验见证 REST API。
 */
@RestController
@RequestMapping("/api/artifacts")
@Validated
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /** 登记制品版本。 */
    @PostMapping
    public ResponseEntity<ArtifactResponse> register(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody RegisterArtifactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.registerArtifact(requestId, request));
    }

    /** 撤回制品版本。 */
    @PostMapping("/{name}/versions/{version}/withdraw")
    public ArtifactResponse withdraw(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.withdrawArtifact(requestId, name, version);
    }

    /** 为制品版本登记内容校验摘要。 */
    @PostMapping("/{name}/versions/{version}/checksum")
    public ResponseEntity<ChecksumResponse> registerChecksum(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Operator") String operator,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody RegisterChecksumRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.registerChecksum(requestId, name, version, request, operator));
    }

    /** 发布角色对制品版本摘要提交见证。 */
    @PostMapping("/{name}/versions/{version}/attestations")
    public ResponseEntity<AttestationResponse> attest(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Operator") String operator,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody AttestRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.attest(requestId, name, version, request, operator));
    }

    /** 撤销指定角色对制品版本的见证；只影响后续锁定，不改写旧锁文件。 */
    @PostMapping("/{name}/versions/{version}/attestations/{role}/revoke")
    public AttestationResponse revokeAttestation(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Operator") String operator,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @PathVariable String role) {
        return artifactService.revokeAttestation(requestId, name, version, role, operator);
    }

    /** 查询制品版本的摘要、见证明细与可否入锁诊断（只读）。 */
    @GetMapping("/{name}/versions/{version}/attestations")
    public AttestationStatusResponse attestationStatus(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.getAttestationStatus(name, version);
    }

    /** 创建锁文件。 */
    @PostMapping("/locks")
    public ResponseEntity<LockFileResponse> lock(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.createLock(requestId, request));
    }

    /** 查询全部历史锁文件。 */
    @GetMapping("/locks")
    public List<LockFileResponse> listLocks() {
        return artifactService.listLocks();
    }

    /** 按 ID 查询单个锁文件。 */
    @GetMapping("/locks/{id}")
    public LockFileResponse getLock(@PathVariable long id) {
        return artifactService.getLock(id);
    }
}
