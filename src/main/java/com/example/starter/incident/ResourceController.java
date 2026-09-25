package com.example.starter.incident;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.LeaseAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.CredentialView;
import com.example.starter.incident.dto.Responses.LeaseBatchView;
import com.example.starter.incident.dto.Responses.ResourceCredentialsView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.RiskLeaseListView;
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
 * 共享资源资质与租约 REST API。写操作均要求 X-Actor-Id 请求头标识操作人。
 */
@RestController
@RequestMapping("/api")
public class ResourceController {

    private final ResourceLeaseService service;

    public ResourceController(ResourceLeaseService service) {
        this.service = service;
    }

    /**
     * 登记共享资源：初始版本 1。
     */
    @PostMapping("/resources")
    public ResponseEntity<ResourceView> registerResource(@RequestHeader("X-Actor-Id") String actor,
                                                         @RequestBody ResourceRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerResource(actor, req));
    }

    /**
     * 查询资源全部资质（含已撤销）与当前版本（只读）。
     */
    @GetMapping("/resources/{resourceKey}/credentials")
    public ResourceCredentialsView listCredentials(@PathVariable String resourceKey) {
        return service.listCredentials(resourceKey);
    }

    /**
     * 登记资源资质（UTC 半开区间有效期；已撤销同代码可重新登记）。
     */
    @PostMapping("/resources/{resourceKey}/credentials")
    public CredentialView registerCredential(@PathVariable String resourceKey,
                                             @RequestHeader("X-Actor-Id") String actor,
                                             @RequestBody CredentialRegisterRequest req) {
        return service.registerCredential(resourceKey, actor, req);
    }

    /**
     * 提前撤销资源资质：未来有效的高危租约转入 CREDENTIAL_RISK 并写不可变风险记录。
     */
    @PostMapping("/resources/{resourceKey}/credentials/{credentialCode}/revoke")
    public CredentialView revokeCredential(@PathVariable String resourceKey,
                                           @PathVariable String credentialCode,
                                           @RequestHeader("X-Actor-Id") String actor,
                                           @RequestBody CredentialRevokeRequest req) {
        return service.revokeCredential(resourceKey, credentialCode, actor, req);
    }

    /**
     * 查询资源处于 CREDENTIAL_RISK 的租约及不可变风险记录（只读）。
     */
    @GetMapping("/resources/{resourceKey}/risk-leases")
    public RiskLeaseListView riskLeases(@PathVariable String resourceKey) {
        return service.riskLeasesByResource(resourceKey);
    }

    /**
     * 批量租约分配：为多个高危任务分配同一资源，单事务全部创建，任一失败整单回滚。
     */
    @PostMapping("/leases")
    public LeaseBatchView assignLeases(@RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody LeaseAssignRequest req) {
        return service.assignLeases(actor, req);
    }
}
