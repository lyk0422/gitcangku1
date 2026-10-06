package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

import java.util.List;

/**
 * 软件制品依赖锁定 REST API。
 *
 * <p>写操作幂等身份包含 X-Operator 操作者头（缺省 anonymous）。
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
            @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator,
            @Valid @RequestBody RegisterArtifactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.registerArtifact(requestId, operator, request));
    }

    /** 撤回制品版本；可携带 expectedRepositoryVersion 做乐观校验。 */
    @PostMapping("/{name}/versions/{version}/withdraw")
    public ArtifactResponse withdraw(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator,
            @PathVariable String name,
            @PathVariable @Positive int version,
            @RequestParam(required = false) @PositiveOrZero Long expectedRepositoryVersion) {
        return artifactService.withdrawArtifact(requestId, operator, name, version,
                expectedRepositoryVersion);
    }

    /** 创建锁文件（旧单根扁平规则：全部依赖按 PUBLIC 处理）。 */
    @PostMapping("/locks")
    public ResponseEntity<LockFileResponse> lock(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.createLock(requestId, operator, request));
    }

    /** 创建带私有作用域的锁文件（新锁入口）。 */
    @PostMapping("/locks/scoped")
    public ResponseEntity<LockFileResponse> scopedLock(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator,
            @Valid @RequestBody LockRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.createScopedLock(requestId, operator, request));
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
