package com.example.starter.web;

import com.example.starter.service.ArtifactService;
import com.example.starter.web.dto.ArtifactResponse;
import com.example.starter.web.dto.RegisterArtifactRequest;
import com.example.starter.web.dto.WithdrawRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 制品登记与撤回接口。
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /**
     * 登记新的制品版本（含0～10条依赖声明），创建后依赖不可改，仓库版本加一。
     */
    @PostMapping
    public ResponseEntity<ArtifactResponse> register(@Valid @RequestBody RegisterArtifactRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(artifactService.register(request));
    }

    /**
     * 撤回指定制品版本（不删除），仓库版本加一；历史锁文件不受影响。
     */
    @PostMapping("/{name}/versions/{version}/withdraw")
    public ResponseEntity<ArtifactResponse> withdraw(@PathVariable String name,
                                                     @PathVariable int version,
                                                     @Valid @RequestBody WithdrawRequest request) {
        return ResponseEntity.ok(artifactService.withdraw(name, version, request));
    }
}
