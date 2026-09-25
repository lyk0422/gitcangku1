package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAllocateRequest;
import com.example.starter.incident.dto.Requests.LeaseReplaceRequest;
import com.example.starter.incident.dto.Responses.CredentialRevokeView;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.LeaseAllocateView;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.ResourceCredentialsView;
import com.example.starter.incident.dto.Responses.RiskLeasesView;
import com.example.starter.incident.dto.Responses.TaskGateView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件资源资质与租约 REST API。
 * 资质撤销、租约分配/替换要求 X-Actor-Id；资质登记不要求指挥权（资源侧操作）。
 */
@RestController
@RequestMapping("/api")
public class CredentialLeaseController {

    private final CredentialLeaseService service;

    public CredentialLeaseController(CredentialLeaseService service) {
        this.service = service;
    }

    /**
     * 登记资源资质（首次登记或对仍有效资质续期，版本 +1）。
     */
    @PostMapping("/resources/{resourceId}/credentials/{credentialCode}")
    public ResponseEntity<CredentialView> register(@PathVariable String resourceId,
                                                   @PathVariable String credentialCode,
                                                   @RequestBody CredentialRegisterRequest req) {
        // 路径参数为准，请求体内 resourceId/credentialCode 仅用于文档一致性
        CredentialRegisterRequest normalized = new CredentialRegisterRequest(req.commandKey(),
                resourceId, credentialCode, req.validFrom(), req.validUntil());
        return ResponseEntity.status(HttpStatus.OK).body(service.register(normalized));
    }

    /**
     * 查询资源全部资质（按资质代码字典序）。
     */
    @GetMapping("/resources/{resourceId}/credentials")
    public ResourceCredentialsView listCredentials(@PathVariable String resourceId) {
        return service.listCredentials(resourceId);
    }

    /**
     * 提前撤销资源资质：未来有效的高危租约转 CREDENTIAL_RISK 并写不可变风险记录。
     */
    @PostMapping("/resources/{resourceId}/credentials/{credentialCode}/revoke")
    public CredentialRevokeView revoke(@PathVariable String resourceId,
                                       @PathVariable String credentialCode,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody CredentialRevokeRequest req) {
        return service.revoke(resourceId, credentialCode, actor, req);
    }

    /**
     * 批量分配资源租约：先统一校验，再单事务创建全部租约，任一失败整单回滚。
     */
    @PostMapping("/leases")
    public LeaseAllocateView allocate(@RequestHeader("X-Actor-Id") String actor,
                                      @RequestBody LeaseAllocateRequest req) {
        return service.allocate(actor, req);
    }

    /**
     * 以合格资源替换任务当前租约，清除资质风险门禁。
     */
    @PostMapping("/incidents/{incidentKey}/tasks/{taskKey}/lease/replace")
    public LeaseView replace(@PathVariable String incidentKey, @PathVariable String taskKey,
                             @RequestHeader("X-Actor-Id") String actor,
                             @RequestBody LeaseReplaceRequest req) {
        return service.replace(incidentKey, taskKey, actor, req);
    }

    /**
     * 查询事件风险租约（不可变资质风险记录）。
     */
    @GetMapping("/incidents/{incidentKey}/credential-risks")
    public RiskLeasesView listRisks(@PathVariable String incidentKey) {
        return service.listRisks(incidentKey);
    }

    /**
     * 查询任务门禁原因（资质风险、依赖门禁、租约资质覆盖）。
     */
    @GetMapping("/incidents/{incidentKey}/tasks/{taskKey}/gate")
    public TaskGateView taskGate(@PathVariable String incidentKey, @PathVariable String taskKey) {
        return service.taskGate(incidentKey, taskKey);
    }
}
