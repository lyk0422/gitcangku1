package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DeprecationBatchRequest;
import com.example.starter.api.dto.DeprecationBatchResponse;
import com.example.starter.api.dto.DeprecationDiagnosisResponse;
import com.example.starter.api.dto.DeprecationEventView;
import com.example.starter.api.dto.DeprecationWindowView;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;
import java.util.List;

/**
 * 软件制品依赖锁定与弃用窗口 REST API。
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

    /** 创建锁文件；命中尚未生效弃用窗口时响应携带 DEPRECATION_WARNING 头与警告明细。 */
    @PostMapping("/locks")
    public ResponseEntity<LockFileResponse> lock(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody LockRequest request) {
        LockFileResponse body = artifactService.createLock(requestId, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (!body.deprecationWarnings().isEmpty()) {
            builder.header("X-Deprecation-Warning", "DEPRECATION_WARNING");
        }
        return builder.body(body);
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

    /** 批量设置弃用窗口：先校验完整最终替代图，任一失败整次不写入。 */
    @PostMapping("/deprecations")
    public DeprecationBatchResponse setDeprecations(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody DeprecationBatchRequest request) {
        return artifactService.setDeprecations(requestId, request);
    }

    /** 撤销弃用窗口（仅尚未生效窗口）。 */
    @PostMapping("/deprecations/{name}/versions/{version}/revoke")
    public DeprecationWindowView revokeDeprecation(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.revokeDeprecation(requestId, name, version);
    }

    /** 查询全部弃用窗口当前状态（含已撤销）。 */
    @GetMapping("/deprecations")
    public List<DeprecationWindowView> listDeprecations() {
        return artifactService.listDeprecations();
    }

    /** 查询全部弃用窗口历史事件（登记/撤销，只追加）。 */
    @GetMapping("/deprecations/events")
    public List<DeprecationEventView> listDeprecationEvents() {
        return artifactService.listDeprecationEvents();
    }

    /** 按制品版本查询弃用窗口当前状态。 */
    @GetMapping("/deprecations/{name}/versions/{version}")
    public DeprecationWindowView getDeprecation(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.getDeprecation(name, version);
    }

    /** 按制品版本查询弃用窗口历史事件。 */
    @GetMapping("/deprecations/{name}/versions/{version}/events")
    public List<DeprecationEventView> getDeprecationEvents(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.getDeprecationEvents(name, version);
    }

    /** 只读诊断预演：按解析时刻（缺省当前时钟）对根闭包做弃用裁决，不改变状态。 */
    @GetMapping("/diagnose")
    public DeprecationDiagnosisResponse diagnose(
            @RequestParam String rootName,
            @RequestParam @Positive int rootVersion,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return artifactService.diagnose(rootName, rootVersion, at);
    }
}
