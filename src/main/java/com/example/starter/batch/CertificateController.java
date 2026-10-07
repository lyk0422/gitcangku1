package com.example.starter.batch;

import com.example.starter.batch.dto.BindCertificatesRequest;
import com.example.starter.batch.dto.BindingResponse;
import com.example.starter.batch.dto.DeclarationVersionResponse;
import com.example.starter.batch.dto.MergeBatchesRequest;
import com.example.starter.batch.dto.PublishDeclarationRequest;
import com.example.starter.batch.dto.ReleaseCheckResponse;
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

import java.util.List;

/**
 * 供应商合格证声明与批次合格证匹配接口。
 */
@RestController
@RequestMapping("/api")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * 发布合格证声明新版本；X-Actor-Id 为发布操作人。
     */
    @PostMapping("/declarations")
    public ResponseEntity<String> publish(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody PublishDeclarationRequest request) {
        return stored(service.publish(actorId, request));
    }

    /**
     * 撤回声明全部 ACTIVE 版本，只影响后续绑定与放行。
     */
    @PostMapping("/declarations/{declarationKey}/withdraw")
    public ResponseEntity<String> withdraw(
            @PathVariable String declarationKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WithdrawDeclarationRequest request) {
        return stored(service.withdraw(declarationKey, actorId, request));
    }

    /**
     * 全部声明版本列表，按 (declarationKey, version) 稳定排序。
     */
    @GetMapping("/declarations")
    public List<DeclarationVersionResponse> listDeclarations() {
        return service.listDeclarations();
    }

    /**
     * 某声明业务键的全部版本明细，按版本号升序。
     */
    @GetMapping("/declarations/{declarationKey}")
    public List<DeclarationVersionResponse> declarationDetail(@PathVariable String declarationKey) {
        return service.declarationDetail(declarationKey);
    }

    /**
     * 批量绑定合格证声明版本；任一失效整次回滚。
     */
    @PostMapping("/batches/{batchKey}/certificate-bindings")
    public ResponseEntity<String> bind(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindCertificatesRequest request) {
        return stored(service.bind(batchKey, actorId, request));
    }

    /**
     * 某批次的全部合格证绑定，按绑定顺序稳定排序。
     */
    @GetMapping("/batches/{batchKey}/certificate-bindings")
    public List<BindingResponse> listBindings(@PathVariable String batchKey) {
        return service.listBindings(batchKey);
    }

    /**
     * 合批：2～5 个 RELEASED 父批合并为一个全新子批，须覆盖全部父物料。
     */
    @PostMapping("/batches/merge")
    public ResponseEntity<String> merge(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody MergeBatchesRequest request) {
        return stored(service.merge(actorId, request));
    }

    /**
     * 放行门禁诊断（只读）：有效绑定声明的实际值、要求值与差额。
     */
    @GetMapping("/batches/{batchKey}/release-check")
    public ReleaseCheckResponse releaseCheck(@PathVariable String batchKey) {
        return service.releaseCheck(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
