package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorDetailView;
import com.example.starter.api.dto.MirrorResponse;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorRequest;
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
 * 软件制品依赖锁定与镜像源回退 REST API。
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

    /** 为制品版本登记镜像源。 */
    @PostMapping("/{name}/versions/{version}/mirrors")
    public ResponseEntity<MirrorResponse> registerMirror(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody RegisterMirrorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.registerMirror(requestId, name, version, request));
    }

    /** 标记镜像源不可用。 */
    @PostMapping("/{name}/versions/{version}/mirrors/{mirrorId}/unavailable")
    public MirrorResponse markMirrorUnavailable(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @PathVariable String mirrorId) {
        return artifactService.setMirrorAvailability(requestId, name, version, mirrorId, false);
    }

    /** 恢复镜像源可用。 */
    @PostMapping("/{name}/versions/{version}/mirrors/{mirrorId}/available")
    public MirrorResponse markMirrorAvailable(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @PathVariable String mirrorId) {
        return artifactService.setMirrorAvailability(requestId, name, version, mirrorId, true);
    }

    /** 查询制品版本镜像登记明细（含不可用）。 */
    @GetMapping("/{name}/versions/{version}/mirrors")
    public List<MirrorDetailView> listMirrors(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.listMirrors(name, version);
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

    /** 镜像故障切换查询：锁文件中该名称锁定版本当前优先级最高的可用镜像。 */
    @GetMapping("/locks/{id}/entries/{name}/failover-mirror")
    public MirrorView resolveMirror(@PathVariable long id, @PathVariable String name) {
        return artifactService.resolveMirror(id, name);
    }
}
