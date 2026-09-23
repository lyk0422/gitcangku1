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

    /** 查询某制品版本的全部签名证据，按 keyId 升序。 */
    @GetMapping("/{name}/versions/{version}/signatures")
    public List<SignatureResponse> listSignatures(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.listSignatures(name, version);
    }

    /** 发布签名信任策略新版本。 */
    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> publishPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody PublishPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.publishPolicy(requestId, request));
    }

    /** 查询全部已发布策略（含未生效），按版本号升序。 */
    @GetMapping("/policies")
    public List<PolicyResponse> listPolicies() {
        return artifactService.listPolicies();
    }

    /** 查询解析时刻当前生效策略；尚无生效策略时返回 404。 */
    @GetMapping("/policies/effective")
    public PolicyResponse effectivePolicy() {
        PolicyResponse policy = artifactService.getEffectivePolicy();
        if (policy == null) {
            throw com.example.starter.support.ApiException.notFound("当前无生效签名信任策略");
        }
        return policy;
    }

    /** 按版本号查询策略。 */
    @GetMapping("/policies/{policyVersion}")
    public PolicyResponse getPolicy(@PathVariable long policyVersion) {
        return artifactService.getPolicy(policyVersion);
    }

    /** 撤销签名钥匙。 */
    @PostMapping("/keys/{keyId}/revoke")
    public KeyResponse revokeKey(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String keyId) {
        return artifactService.revokeKey(requestId, keyId);
    }

    /** 查询钥匙状态。 */
    @GetMapping("/keys/{keyId}")
    public KeyResponse getKey(@PathVariable String keyId) {
        return artifactService.getKey(keyId);
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
