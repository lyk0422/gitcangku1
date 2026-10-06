package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ScopedLockFileResponse;
import com.example.starter.api.dto.WithdrawRequest;
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
 *
 * <p>写操作均要求 X-Request-Id 幂等键；可选 X-Operator 头标识操作者，
 * 参与幂等身份。旧单根锁入口保持全图同名一版；/locks/scoped 启用 PRIVATE 作用域。
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
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody RegisterArtifactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.registerArtifact(requestId, operator, request));
    }

    /** 撤回制品版本；请求体可选，携带期望仓库版本号。 */
    @PostMapping("/{name}/versions/{version}/withdraw")
    public ArtifactResponse withdraw(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @Valid @RequestBody(required = false) WithdrawRequest body) {
        Long expectedRepositoryVersion = body == null ? null : body.expectedRepositoryVersion();
        return artifactService.withdrawArtifact(requestId, operator, name, version,
                expectedRepositoryVersion);
    }

    /** 创建锁文件（旧单根入口，全图同名只能一版）。 */
    @PostMapping("/locks")
    public ResponseEntity<LockFileResponse> lock(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.createLock(requestId, operator, request));
    }

    /** 创建作用域锁文件（新锁入口，启用 PRIVATE 作用域）。 */
    @PostMapping("/locks/scoped")
    public ResponseEntity<ScopedLockFileResponse> scopedLock(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.createScopedLock(requestId, operator, request));
    }

    /** 查询全部历史锁文件。 */
    @GetMapping("/locks")
    public List<LockFileResponse> listLocks() {
        return artifactService.listLocks();
    }

    /** 查询全部历史作用域锁文件。 */
    @GetMapping("/locks/scoped")
    public List<ScopedLockFileResponse> listScopedLocks() {
        return artifactService.listScopedLocks();
    }

    /** 按 ID 查询单个作用域锁文件。 */
    @GetMapping("/locks/scoped/{id}")
    public ScopedLockFileResponse getScopedLock(@PathVariable long id) {
        return artifactService.getScopedLock(id);
    }

    /** 按 ID 查询单个锁文件。 */
    @GetMapping("/locks/{id}")
    public LockFileResponse getLock(@PathVariable long id) {
        return artifactService.getLock(id);
    }
}
