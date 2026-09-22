package com.example.starter.artifact;

import java.util.List;

import com.example.starter.artifact.dto.LockFileResponse;
import com.example.starter.artifact.dto.LockFileSummary;
import com.example.starter.artifact.dto.LockRequest;
import com.example.starter.artifact.dto.RegisterArtifactRequest;
import com.example.starter.artifact.dto.RepositoryView;
import com.example.starter.artifact.dto.RetractRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 软件制品依赖锁定 REST API。
 */
@RestController
@RequestMapping("/api")
public class ArtifactController {

    private final IdempotentExecutor idempotentExecutor;
    private final ArtifactService artifactService;
    private final LockService lockService;

    public ArtifactController(IdempotentExecutor idempotentExecutor,
                              ArtifactService artifactService, LockService lockService) {
        this.idempotentExecutor = idempotentExecutor;
        this.artifactService = artifactService;
        this.lockService = lockService;
    }

    /**
     * 登记制品版本。name + version 联合唯一，创建后依赖不可改。
     */
    @PostMapping("/artifacts")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterArtifactRequest request) {
        return idempotentExecutor.execute(request.requestId(), "register", request,
                HttpStatus.CREATED, () -> artifactService.register(request));
    }

    /**
     * 撤回制品版本：仅标记不删除，仓库版本加一。
     */
    @PostMapping("/artifacts/{name}/versions/{version}/retract")
    public ResponseEntity<String> retract(@PathVariable String name, @PathVariable int version,
                                          @Valid @RequestBody RetractRequest request) {
        RetractPayload payload = new RetractPayload(name, version);
        return idempotentExecutor.execute(request.requestId(), "retract", payload,
                HttpStatus.OK, () -> artifactService.retract(name, version));
    }

    /**
     * 依赖锁定：指定精确根版本与期望仓库版本，返回首个完整可行的精确版本集合。
     */
    @PostMapping("/locks")
    public ResponseEntity<String> lock(@Valid @RequestBody LockRequest request) {
        return idempotentExecutor.execute(request.requestId(), "lock", request,
                HttpStatus.CREATED, () -> lockService.lock(request));
    }

    /**
     * 查询全部历史锁文件摘要，按 id 升序。
     */
    @GetMapping("/locks")
    public List<LockFileSummary> listLocks() {
        return lockService.listLocks();
    }

    /**
     * 查询锁文件详情，条目按名称升序；制品后来撤回不影响已生成的锁文件。
     */
    @GetMapping("/locks/{lockFileId}")
    public LockFileResponse getLock(@PathVariable long lockFileId) {
        return lockService.getLock(lockFileId);
    }

    /**
     * 查询仓库整体视图：当前仓库版本号与全部制品版本（含已撤回）。
     */
    @GetMapping("/repository")
    public RepositoryView repository() {
        return artifactService.repositoryView();
    }

    /**
     * 撤回请求的幂等负载（路径参数参与同键异参检测）。
     */
    private record RetractPayload(String name, int version) {
    }
}
