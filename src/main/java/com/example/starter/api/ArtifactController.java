package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.BatchDeprecationResponse;
import com.example.starter.api.dto.DeprecationDiagnosticsResponse;
import com.example.starter.api.dto.DeprecationResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RevokeDeprecationResponse;
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

    /** 创建锁文件。 */
    @PostMapping("/locks")
    public ResponseEntity<LockFileResponse> lock(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.createLock(requestId, request));
    }

    /** 批量登记弃用窗口：先校验完整替代图，任一失败整次不写入。 */
    @PostMapping("/deprecations")
    public ResponseEntity<BatchDeprecationResponse> registerDeprecations(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody BatchDeprecationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.registerDeprecations(requestId, request));
    }

    /** 撤销尚未生效的弃用窗口（记录保留）。 */
    @PostMapping("/{name}/versions/{version}/deprecation/revoke")
    public RevokeDeprecationResponse revokeDeprecation(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.revokeDeprecation(requestId, name, version);
    }

    /** 查询全部弃用窗口历史（含已撤销），按名称、版本升序。 */
    @GetMapping("/deprecations")
    public List<DeprecationResponse> listDeprecations() {
        return artifactService.listDeprecations();
    }

    /** 弃用窗口诊断查询：按当前时钟统计待生效/已生效/已撤销。 */
    @GetMapping("/deprecations/diagnostics")
    public DeprecationDiagnosticsResponse deprecationDiagnostics() {
        return artifactService.getDeprecationDiagnostics();
    }

    /** 查询单个制品版本的弃用窗口明细。 */
    @GetMapping("/{name}/versions/{version}/deprecation")
    public DeprecationResponse getDeprecation(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.getDeprecation(name, version);
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
