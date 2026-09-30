package com.example.starter.api;

import com.example.starter.api.dto.ApproveRiskExceptionRequest;
import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CreateRiskExceptionRequest;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MarkRiskRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ReviseRiskExceptionRequest;
import com.example.starter.api.dto.RiskExceptionView;
import com.example.starter.api.dto.RiskResponse;
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

    /** 标记或更新制品版本的依赖风险等级。 */
    @PostMapping("/risks")
    public RiskResponse markRisk(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody MarkRiskRequest request) {
        return artifactService.markRisk(requestId, request);
    }

    /** 查询全部风险标记。 */
    @GetMapping("/risks")
    public List<RiskResponse> listRisks() {
        return artifactService.listRisks();
    }

    /** 创建高风险依赖豁免申请。 */
    @PostMapping("/risk-exceptions")
    public ResponseEntity<RiskExceptionView> createRiskException(
            @RequestHeader("X-Request-Id") String requestId,
            @Valid @RequestBody CreateRiskExceptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(artifactService.createRiskException(requestId, request));
    }

    /** 查询全部豁免申请（含审批人、当前有效性与适用版本）。 */
    @GetMapping("/risk-exceptions")
    public List<RiskExceptionView> listRiskExceptions() {
        return artifactService.listRiskExceptions();
    }

    /** 按 ID 查询豁免申请。 */
    @GetMapping("/risk-exceptions/{id}")
    public RiskExceptionView getRiskException(@PathVariable long id) {
        return artifactService.getRiskException(id);
    }

    /** 审批人确认豁免申请版本；两名不同审批人确认同一版本后生效。 */
    @PostMapping("/risk-exceptions/{id}/approvals")
    public RiskExceptionView approveRiskException(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable long id,
            @Valid @RequestBody ApproveRiskExceptionRequest request) {
        return artifactService.approveRiskException(requestId, id, request);
    }

    /** 修订 PENDING 豁免：原因/到期时刻更新，申请版本加一，既有审批作废。 */
    @PostMapping("/risk-exceptions/{id}/revise")
    public RiskExceptionView reviseRiskException(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable long id,
            @Valid @RequestBody ReviseRiskExceptionRequest request) {
        return artifactService.reviseRiskException(requestId, id, request);
    }

    /** 撤销已生效豁免；仅影响新锁定，不改写历史锁文件。 */
    @PostMapping("/risk-exceptions/{id}/revoke")
    public RiskExceptionView revokeRiskException(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable long id) {
        return artifactService.revokeRiskException(requestId, id);
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
