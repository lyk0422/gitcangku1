package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LicenseBody;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseViolationResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetPolicyRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 软件制品依赖锁定 REST API。
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

    /** 登记/修改制品版本许可证；body 缺省 license 表示恢复 UNKNOWN。 */
    @PutMapping("/{name}/versions/{version}/license")
    public LicenseResponse setLicense(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody(required = false) LicenseBody body) {
        String license = body == null ? null : body.license();
        return artifactService.setLicense(requestId, name, version, license);
    }

    /** 创建或整体替换命名空间许可证策略。 */
    @PutMapping("/policies")
    public PolicyResponse setPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody SetPolicyRequest request) {
        return artifactService.setPolicy(requestId, request);
    }

    /** 查询命名空间当前许可证策略。 */
    @GetMapping("/policies/{namespace}")
    public PolicyResponse getPolicy(@PathVariable String namespace) {
        PolicyResponse policy = artifactService.getPolicy(namespace);
        if (policy == null) {
            throw com.example.starter.support.ApiException.notFound("命名空间策略不存在: " + namespace);
        }
        return policy;
    }

    /** 创建锁文件。 */
    @PostMapping("/locks")
    public ResponseEntity<LockFileResponse> lock(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.createLock(requestId, request));
    }

    /** 违规诊断：按当前仓库与策略试算锁定，返回稳定排序的违规明细，不落库。 */
    @PostMapping("/locks/diagnose")
    public List<LicenseViolationResponse> diagnose(@Valid @RequestBody LockRequest request) {
        return artifactService.diagnoseLock(request);
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
