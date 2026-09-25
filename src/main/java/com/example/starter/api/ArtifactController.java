package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DiagnoseLockRequest;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LockDiagnosisResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockLicenseSnapshotResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetLicenseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * 软件制品依赖锁定 REST API：制品登记/撤回、许可证登记、命名空间策略与锁定。
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

    /** 登记或修订制品版本许可证；已撤回版本返回 409。 */
    @PutMapping("/{name}/versions/{version}/license")
    public LicenseResponse setLicense(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody SetLicenseRequest request) {
        return artifactService.setLicense(requestId, name, version, request);
    }

    /** 创建或修改命名空间许可证策略；期望版本不匹配返回 409。 */
    @PutMapping("/policies/{namespace}")
    public PolicyResponse upsertPolicy(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable @NotBlank String namespace,
            @Valid @RequestBody PolicyRequest request) {
        return artifactService.upsertPolicy(requestId, namespace, request);
    }

    /** 查询命名空间许可证策略。 */
    @GetMapping("/policies/{namespace}")
    public PolicyResponse getPolicy(@PathVariable String namespace) {
        return artifactService.getPolicy(namespace);
    }

    /** 创建锁文件；许可证策略违规返回 422 及违规诊断列表。 */
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

    /** 只读违规诊断：按当前仓库状态解析闭包并评估策略，不产生写入。 */
    @PostMapping("/locks/diagnose")
    public LockDiagnosisResponse diagnoseLock(@Valid @RequestBody DiagnoseLockRequest request) {
        return artifactService.diagnoseLock(request);
    }

    /** 按 ID 查询单个锁文件。 */
    @GetMapping("/locks/{id}")
    public LockFileResponse getLock(@PathVariable long id) {
        return artifactService.getLock(id);
    }

    /** 查询锁文件固化的许可证快照（含锁定时策略版本）。 */
    @GetMapping("/locks/{id}/licenses")
    public LockLicenseSnapshotResponse getLockLicenseSnapshot(@PathVariable long id) {
        return artifactService.getLockLicenseSnapshot(id);
    }
}
