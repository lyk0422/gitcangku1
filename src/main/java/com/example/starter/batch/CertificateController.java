package com.example.starter.batch;

import com.example.starter.batch.dto.BindCertificatesRequest;
import com.example.starter.batch.dto.CertificateDetailResponse;
import com.example.starter.batch.dto.CertificateDiagnosisResponse;
import com.example.starter.batch.dto.DeclarationHistoryResponse;
import com.example.starter.batch.dto.DeclarationResponse;
import com.example.starter.batch.dto.PublishDeclarationRequest;
import com.example.starter.batch.dto.WithdrawDeclarationRequest;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 供应商合格证声明与批次绑定接口。
 */
@RestController
@RequestMapping("/api")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * 发布版本化合格证声明；X-Actor-Id 为发布操作人。
     */
    @PostMapping("/declarations")
    public ResponseEntity<String> publish(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody PublishDeclarationRequest request) {
        return stored(service.publish(actorId, request));
    }

    /**
     * 撤回声明版本：只影响后续绑定与放行，不改写历史。
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
     * 声明历史：全部版本按版本号升序。
     */
    @GetMapping("/declarations/{declarationKey}/history")
    public DeclarationHistoryResponse history(@PathVariable String declarationKey) {
        return service.history(declarationKey);
    }

    /**
     * 声明版本明细。
     */
    @GetMapping("/declarations/{declarationKey}/versions/{version}")
    public DeclarationResponse getVersion(@PathVariable String declarationKey,
                                          @PathVariable int version) {
        return service.getVersion(declarationKey, version);
    }

    /**
     * 批量绑定批次与声明版本：任一失效或不匹配整次回滚；X-Actor-Id 为绑定操作人。
     */
    @PostMapping("/batches/certificate-bindings")
    public ResponseEntity<String> bindBulk(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindCertificatesRequest request) {
        return stored(service.bindBulk(actorId, request));
    }

    /**
     * 批次合格证明细：当前绑定与放行快照。
     */
    @GetMapping("/batches/{batchKey}/certificate")
    public CertificateDetailResponse batchCertificate(@PathVariable String batchKey) {
        return service.batchCertificate(batchKey);
    }

    /**
     * 批次放行诊断（只读）：列出当前不满足合格证放行条件的全部原因。
     */
    @GetMapping("/batches/{batchKey}/certificate/diagnosis")
    public CertificateDiagnosisResponse diagnose(@PathVariable String batchKey) {
        return service.diagnose(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
