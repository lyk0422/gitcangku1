package com.example.starter.certificate;

import com.example.starter.batch.StoredResponse;
import com.example.starter.certificate.dto.BindCertificatesRequest;
import com.example.starter.certificate.dto.DeclarationDetailResponse;
import com.example.starter.certificate.dto.DeclarationResponse;
import com.example.starter.certificate.dto.PublishDeclarationRequest;
import com.example.starter.certificate.dto.WithdrawDeclarationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 供应商合格证声明与批次绑定接口。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * 发布版本化合格证声明；X-Actor-Id 为发布人。
     */
    @PostMapping("/declarations")
    public ResponseEntity<String> publish(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody PublishDeclarationRequest request) {
        return stored(service.publish(actorId, request));
    }

    /**
     * 撤回声明版本：只影响后续绑定与放行，不改写历史；X-Actor-Id 为操作人。
     */
    @PostMapping("/declarations/{declarationKey}/versions/{version}/withdraw")
    public ResponseEntity<String> withdraw(
            @PathVariable String declarationKey,
            @PathVariable int version,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WithdrawDeclarationRequest request) {
        return stored(service.withdraw(declarationKey, version, actorId, request));
    }

    /**
     * 声明列表：可按 materialCode 过滤；按 声明键、版本号 稳定排序。
     */
    @GetMapping("/declarations")
    public List<DeclarationResponse> list(
            @RequestParam(name = "materialCode", required = false) String materialCode) {
        return service.listDeclarations(materialCode);
    }

    /**
     * 声明明细：同一声明键的全部版本，按版本号升序。
     */
    @GetMapping("/declarations/{declarationKey}")
    public DeclarationDetailResponse detail(@PathVariable String declarationKey) {
        return service.declarationDetail(declarationKey);
    }

    /**
     * 批量绑定合格证：任一失效声明或不匹配整次回滚；X-Actor-Id 为操作人。
     */
    @PostMapping("/bindings")
    public ResponseEntity<String> bind(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindCertificatesRequest request) {
        return stored(service.bind(actorId, request));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
