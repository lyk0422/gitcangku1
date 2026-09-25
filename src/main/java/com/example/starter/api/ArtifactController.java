package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.ProvenanceResponse;
import com.example.starter.api.dto.PublishDiagnosticResponse;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;
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

    /** 发布锁定图：按当前策略版本校验，provenanceKey 同键重放。 */
    @PostMapping("/locks/{id}/publish")
    public ResponseEntity<PublishResponse> publish(
            @PathVariable long id,
            @Valid @RequestBody PublishRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.publishLock(id, request));
    }

    /** 查询锁定图来源路径（已发布返回冻结快照）。 */
    @GetMapping("/locks/{id}/provenance")
    public ProvenanceResponse provenance(@PathVariable long id) {
        return artifactService.getProvenance(id);
    }

    /** 查询锁定图发布阻断诊断。 */
    @GetMapping("/locks/{id}/publish-diagnostic")
    public PublishDiagnosticResponse publishDiagnostic(@PathVariable long id) {
        return artifactService.getPublishDiagnostic(id);
    }
}
