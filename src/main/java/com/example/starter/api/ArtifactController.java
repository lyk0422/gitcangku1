package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.BatchDeprecationResponse;
import com.example.starter.api.dto.DeprecationEventResponse;
import com.example.starter.api.dto.DeprecationRequest;
import com.example.starter.api.dto.DeprecationResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
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

    /** 登记单个弃用窗口。 */
    @PostMapping("/deprecations")
    public ResponseEntity<DeprecationResponse> registerDeprecation(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody DeprecationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.registerDeprecation(requestId, request));
    }

    /** 批量设置弃用窗口：完整替代图先校验，任一失败整次不写入。 */
    @PostMapping("/deprecations/batch")
    public ResponseEntity<BatchDeprecationResponse> batchDeprecations(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody BatchDeprecationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.batchDeprecations(requestId, request));
    }

    /** 撤销尚未生效的弃用窗口。 */
    @PostMapping("/deprecations/{id}/revoke")
    public DeprecationResponse revokeDeprecation(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable @Positive long id) {
        return artifactService.revokeDeprecation(requestId, id);
    }

    /** 查询全部有效弃用窗口（只读）。 */
    @GetMapping("/deprecations")
    public List<DeprecationResponse> listDeprecations() {
        return artifactService.listActiveDeprecations();
    }

    /** 查询弃用事件历史（登记、批量调整、撤销，只追加）。 */
    @GetMapping("/deprecations/history")
    public List<DeprecationEventResponse> deprecationHistory() {
        return artifactService.listDeprecationHistory();
    }

    /** 按 ID 查询单个弃用窗口（含已撤销）。 */
    @GetMapping("/deprecations/{id}")
    public DeprecationResponse getDeprecation(@PathVariable @Positive long id) {
        return artifactService.getDeprecation(id);
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
}
