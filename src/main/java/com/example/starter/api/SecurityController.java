package com.example.starter.api;

import com.example.starter.api.dto.AdvisoryRequest;
import com.example.starter.api.dto.AdvisoryResponse;
import com.example.starter.api.dto.ExceptionRequest;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.PublishSnapshotResponse;
import com.example.starter.api.dto.VulnerabilityHitResponse;
import jakarta.validation.Valid;
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
 * 漏洞公告、豁免与锁定图发布门禁 REST API。
 */
@RestController
@RequestMapping("/api/security")
@Validated
public class SecurityController {

    private final SecurityService securityService;

    public SecurityController(SecurityService securityService) {
        this.securityService = securityService;
    }

    /** 写入/更新漏洞公告。 */
    @PostMapping("/advisories")
    public ResponseEntity<AdvisoryResponse> upsertAdvisory(
            @Valid @RequestBody AdvisoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(securityService.upsertAdvisory(request));
    }

    /** 列出全部漏洞公告。 */
    @GetMapping("/advisories")
    public List<AdvisoryResponse> listAdvisories() {
        return securityService.listAdvisories();
    }

    /** 创建/确认豁免（两名不同审核人各调用一次）。 */
    @PostMapping("/locks/{lockFileId}/exceptions")
    public ExceptionResponse confirmException(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Reviewer") String reviewer,
            @PathVariable long lockFileId,
            @Valid @RequestBody ExceptionRequest request) {
        if (request.lockFileId() != lockFileId) {
            throw com.example.starter.support.ApiException.badRequest(
                    "请求体 lockFileId 与路径不一致");
        }
        return securityService.confirmException(requestId, request, reviewer);
    }

    /** 撤销豁免。 */
    @PostMapping("/exceptions/{exceptionId}/revoke")
    public ExceptionResponse revokeException(
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("X-Reviewer") String reviewer,
            @PathVariable long exceptionId) {
        return securityService.revokeException(requestId, exceptionId, reviewer);
    }

    /** 查询某锁定图的全部豁免。 */
    @GetMapping("/locks/{lockFileId}/exceptions")
    public List<ExceptionResponse> listExceptions(@PathVariable long lockFileId) {
        return securityService.listExceptions(lockFileId);
    }

    /** 查询某锁定图当前漏洞命中与豁免作用域。 */
    @GetMapping("/locks/{lockFileId}/vulnerabilities")
    public List<VulnerabilityHitResponse> listVulnerabilityHits(@PathVariable long lockFileId) {
        return securityService.listVulnerabilityHits(lockFileId);
    }

    /** 发布锁定图（通过漏洞门禁后复制不可变快照）。 */
    @PostMapping("/locks/{lockFileId}/publish")
    public ResponseEntity<PublishSnapshotResponse> publish(
            @RequestHeader("X-Request-Id") String requestId,
            @PathVariable long lockFileId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(securityService.publish(requestId, lockFileId));
    }

    /** 查询某锁定图的历史发布快照。 */
    @GetMapping("/locks/{lockFileId}/publishes")
    public List<PublishSnapshotResponse> listPublishes(@PathVariable long lockFileId) {
        return securityService.listPublishes(lockFileId);
    }
}
