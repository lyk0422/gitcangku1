package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
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
 * 软件制品依赖锁定与替代策略 REST API。
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

    /** 恢复已撤回的制品版本。 */
    @PostMapping("/{name}/versions/{version}/restore")
    public ArtifactResponse restore(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.restoreArtifact(requestId, name, version);
    }

    /** 发布新版本替代策略，规则集合整体激活。 */
    @PostMapping("/policies")
    public ResponseEntity<PolicyResponse> publishPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody PublishPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.publishPolicy(requestId, request));
    }

    /** 查询全部已发布策略摘要，按版本升序。 */
    @GetMapping("/policies")
    public List<PolicyResponse> listPolicies() {
        return artifactService.listPolicies();
    }

    /** 查询当前激活的完整策略；从未发布时返回 404。 */
    @GetMapping("/policies/current")
    public PolicyResponse currentPolicy() {
        PolicyResponse response = artifactService.getCurrentPolicy();
        if (response == null) {
            throw com.example.starter.support.ApiException.notFound("尚未发布任何替代策略");
        }
        return response;
    }

    /** 按 policyVersion 查询完整策略。 */
    @GetMapping("/policies/{version}")
    public PolicyResponse getPolicy(@PathVariable @Positive long version) {
        return artifactService.getPolicy(version);
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

    /** 按 ID 查询单个锁文件（含冻结的替代解释）。 */
    @GetMapping("/locks/{id}")
    public LockFileResponse getLock(@PathVariable long id) {
        return artifactService.getLock(id);
    }
}
