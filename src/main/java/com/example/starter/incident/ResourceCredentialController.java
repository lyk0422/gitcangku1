package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Responses.CredentialView;
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
 * 资源凭据 REST API：登记新版本、撤销（不可逆）、按资源查询全部版本。
 * 撤销要求 X-Actor-Id；登记以 commandKey 幂等。
 */
@RestController
@RequestMapping("/api/resources")
public class ResourceCredentialController {

    private final IncidentService service;

    public ResourceCredentialController(IncidentService service) {
        this.service = service;
    }

    /**
     * 登记资源凭据新版本：校验类型、区域与 UTC 失效时刻，(resourceKey, version) 唯一。
     */
    @PostMapping("/{resourceKey}/credentials")
    public ResponseEntity<CredentialView> register(@PathVariable String resourceKey,
                                                   @RequestBody CredentialRegisterRequest req) {
        // 路径资源键优先于请求体，保持资源层级语义
        CredentialRegisterRequest body = new CredentialRegisterRequest(req.commandKey(),
                resourceKey, req.version(), req.credentialType(), req.region(), req.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerCredential(body));
    }

    /**
     * 撤销指定凭据版本（不可逆）。
     */
    @PostMapping("/{resourceKey}/credentials/{version}/revoke")
    public CredentialView revoke(@PathVariable String resourceKey, @PathVariable String version,
                                 @RequestHeader("X-Actor-Id") String actor,
                                 @RequestBody CredentialRevokeRequest req) {
        return service.revokeCredential(resourceKey, version, actor, req);
    }

    /**
     * 查询资源全部凭据版本（按登记顺序）。
     */
    @GetMapping("/{resourceKey}/credentials")
    public List<CredentialView> list(@PathVariable String resourceKey) {
        return service.listCredentials(resourceKey);
    }
}
