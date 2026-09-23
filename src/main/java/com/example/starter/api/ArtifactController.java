package com.example.starter.api;

import com.example.starter.api.dto.AddSignatureRequest;
import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.KeyResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SignatureResponse;
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
 * 软件制品依赖锁定与签名信任 REST API。
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

    /** 发布签名信任策略版本。 */
    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> publishPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody PublishPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.publishPolicy(requestId, request));
    }

    /** 查询全部已发布策略（只读）。 */
    @GetMapping("/policies")
    public List<PolicyResponse> listPolicies() {
        return artifactService.listPolicies();
    }

    /** 按版本号查询策略（只读）。 */
    @GetMapping("/policies/{policyVersion}")
    public PolicyResponse getPolicy(@PathVariable @Positive long policyVersion) {
        return artifactService.getPolicy(policyVersion);
    }

    /** 撤销可信钥匙。 */
    @PostMapping("/keys/{keyId}/revoke")
    public KeyResponse revokeKey(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String keyId) {
        return artifactService.revokeKey(requestId, keyId);
    }

    /** 查询全部可信钥匙（含已撤销，只读）。 */
    @GetMapping("/keys")
    public List<KeyResponse> listKeys() {
        return artifactService.listKeys();
    }

    /** 按 keyId 查询可信钥匙（只读）。 */
    @GetMapping("/keys/{keyId}")
    public KeyResponse getKey(@PathVariable String keyId) {
        return artifactService.getKey(keyId);
    }

    /** 为制品版本追加签名。 */
    @PostMapping("/{name}/versions/{version}/signatures")
    public ResponseEntity<SignatureResponse> addSignature(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody AddSignatureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.addSignature(requestId, name, version, request));
    }

    /** 查询某制品版本的全部历史签名（只读）。 */
    @GetMapping("/{name}/versions/{version}/signatures")
    public List<SignatureResponse> listSignatures(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.listSignatures(name, version);
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
