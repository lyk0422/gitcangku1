package com.example.starter.api;

import com.example.starter.api.dto.CapabilityExclusionRequest;
import com.example.starter.api.dto.CapabilityExclusionResponse;
import com.example.starter.api.dto.CapabilityPairView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 互斥能力规则 REST API：规则为无序能力对，全库最多 10 对，
 * 新增或撤销均推进仓库版本号，写操作按 requestId 幂等。
 */
@RestController
@RequestMapping("/api/capability-exclusions")
public class CapabilityExclusionController {

    private final ArtifactService artifactService;

    public CapabilityExclusionController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /** 新增互斥能力对。 */
    @PostMapping
    public ResponseEntity<CapabilityExclusionResponse> add(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody CapabilityExclusionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.addExclusion(requestId, operator, request));
    }

    /** 撤销互斥能力对。 */
    @PostMapping("/remove")
    public CapabilityExclusionResponse remove(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody CapabilityExclusionRequest request) {
        return artifactService.removeExclusion(requestId, operator, request);
    }

    /** 查询当前生效的全部互斥能力对。 */
    @GetMapping
    public List<CapabilityPairView> list() {
        return artifactService.listExclusions();
    }
}