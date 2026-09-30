package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.ExceptionApproveRequest;
import com.example.starter.api.dto.ExceptionCreateRequest;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.ExceptionRevokeRequest;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MarkRiskRequest;
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
 * 软件制品依赖锁定 REST API：制品登记/撤回/风险标记、依赖风险豁免审批、锁文件与历史查询。
 */
@RestController
@Validated
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /** 登记制品版本。 */
    @PostMapping("/api/artifacts")
    public ResponseEntity<ArtifactResponse> register(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody RegisterArtifactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.registerArtifact(requestId, request));
    }

    /** 撤回制品版本。 */
    @PostMapping("/api/artifacts/{name}/versions/{version}/withdraw")
    public ArtifactResponse withdraw(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.withdrawArtifact(requestId, name, version);
    }

    /** 标记制品版本的依赖风险级别。 */
    @PostMapping("/api/artifacts/{name}/versions/{version}/risk")
    public ArtifactResponse markRisk(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody MarkRiskRequest request) {
        return artifactService.markRisk(requestId, name, version, request);
    }

    /** 创建依赖风险豁免申请。 */
    @PostMapping("/api/dependency-exceptions")
    public ResponseEntity<ExceptionResponse> createException(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody ExceptionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.createException(requestId, request));
    }

    /** 审批人确认豁免申请。 */
    @PostMapping("/api/dependency-exceptions/{id}/approvals")
    public ExceptionResponse approveException(
            @PathVariable @Positive long id,
            @Valid @RequestBody ExceptionApproveRequest request) {
        return artifactService.approveException(id, request);
    }

    /** 撤销已生效豁免。 */
    @PostMapping("/api/dependency-exceptions/{id}/revocation")
    public ExceptionResponse revokeException(
            @PathVariable @Positive long id,
            @Valid @RequestBody ExceptionRevokeRequest request) {
        return artifactService.revokeException(id, request);
    }

    /** 查询全部豁免申请当前状态。 */
    @GetMapping("/api/dependency-exceptions")
    public List<ExceptionResponse> listExceptions() {
        return artifactService.listExceptions();
    }

    /** 按 ID 查询单个豁免申请。 */
    @GetMapping("/api/dependency-exceptions/{id}")
    public ExceptionResponse getException(@PathVariable @Positive long id) {
        return artifactService.getException(id);
    }

    /** 创建锁文件。 */
    @PostMapping("/api/artifacts/locks")
    public ResponseEntity<LockFileResponse> lock(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.createLock(requestId, request));
    }

    /** 查询全部历史锁文件。 */
    @GetMapping("/api/artifacts/locks")
    public List<LockFileResponse> listLocks() {
        return artifactService.listLocks();
    }

    /** 按 ID 查询单个锁文件。 */
    @GetMapping("/api/artifacts/locks/{id}")
    public LockFileResponse getLock(@PathVariable long id) {
        return artifactService.getLock(id);
    }
}
