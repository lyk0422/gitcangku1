package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorAvailabilityRequest;
import com.example.starter.api.dto.MirrorFailoverResponse;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorsRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    /** 为制品版本登记 1～3 个镜像源。 */
    @PostMapping("/{name}/versions/{version}/mirrors")
    public ResponseEntity<List<MirrorView>> registerMirrors(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody RegisterMirrorsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.registerMirrors(requestId, name, version, request));
    }

    /** 查询制品版本登记的镜像明细（含不可用），按优先级升序。 */
    @GetMapping("/{name}/versions/{version}/mirrors")
    public List<MirrorView> listMirrors(
            @PathVariable String name,
            @PathVariable @Positive int version) {
        return artifactService.listMirrors(name, version);
    }

    /** 标记镜像可用/不可用。 */
    @PutMapping("/{name}/versions/{version}/mirrors/{mirrorId}/availability")
    public MirrorView setMirrorAvailability(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @PathVariable String mirrorId,
            @Valid @RequestBody MirrorAvailabilityRequest request) {
        return artifactService.setMirrorAvailability(requestId, name, version,
                mirrorId, request.available());
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

    /**
     * 按锁文件查询固化的镜像清单；name 缺省返回全部名称，
     * 指定 name 时仅返回该名称的镜像快照。
     */
    @GetMapping("/locks/{id}/mirrors")
    public List<LockEntryResponse> listLockMirrors(
            @PathVariable long id,
            @RequestParam(name = "name", required = false) String name) {
        LockFileResponse lock = artifactService.getLock(id);
        if (name == null || name.isBlank()) {
            return lock.entries();
        }
        return lock.entries().stream()
                .filter(e -> e.name().equals(name.trim()))
                .toList();
    }

    /** 镜像故障切换：返回该名称当前第一个可用镜像，只读不改写锁文件。 */
    @GetMapping("/locks/{id}/mirrors/failover")
    public MirrorFailoverResponse failover(
            @PathVariable long id,
            @RequestParam("name") String name) {
        return artifactService.failoverMirror(id, name);
    }
}
