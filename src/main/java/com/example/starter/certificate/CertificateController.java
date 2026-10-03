package com.example.starter.certificate;

import com.example.starter.batch.StoredResponse;
import com.example.starter.certificate.dto.BatchBindRequest;
import com.example.starter.certificate.dto.BatchCertificateResponse;
import com.example.starter.certificate.dto.BindCertificateRequest;
import com.example.starter.certificate.dto.DeclarationDetailResponse;
import com.example.starter.certificate.dto.DiagnosticsResponse;
import com.example.starter.certificate.dto.MergeRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合格证声明与批次绑定接口。所有变更操作要求 X-Actor-Id 请求头标识操作者；
 * 时间统一 UTC，有效区间左闭右开。
 */
@RestController
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * 发布合格证声明版本。
     */
    @PostMapping("/api/declarations")
    public ResponseEntity<String> publish(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody PublishDeclarationRequest request) {
        return stored(service.publish(actorId, request));
    }

    /**
     * 声明明细：业务键 + 全部版本，按版本号升序。
     */
    @GetMapping("/api/declarations/{declarationKey}")
    public DeclarationDetailResponse detail(@PathVariable String declarationKey) {
        return service.declarationDetail(declarationKey);
    }

    /**
     * 匹配诊断：对给定物料与批号，在评估时点逐个评估全部声明版本。
     */
    @GetMapping("/api/declarations/diagnostics")
    public DiagnosticsResponse diagnostics(@RequestParam String materialCode,
                                           @RequestParam String batchNo,
                                           @RequestParam(required = false) String at) {
        return service.diagnostics(materialCode, batchNo, at);
    }

    /**
     * 撤回声明版本：只影响后续绑定与放行，不改写历史。
     */
    @PostMapping("/api/declarations/{declarationKey}/versions/{version}/withdraw")
    public ResponseEntity<String> withdraw(
            @PathVariable String declarationKey,
            @PathVariable int version,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WithdrawDeclarationRequest request) {
        return stored(service.withdraw(declarationKey, version, actorId, request));
    }

    /**
     * 单批次绑定合格证声明版本。
     */
    @PostMapping("/api/batches/{batchKey}/certificate-binding")
    public ResponseEntity<String> bind(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindCertificateRequest request) {
        return stored(service.bind(batchKey, actorId, request));
    }

    /**
     * 批量绑定：任一失效或冲突整次回滚。
     */
    @PostMapping("/api/certificate-bindings/batch")
    public ResponseEntity<String> batchBind(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BatchBindRequest request) {
        return stored(service.batchBind(actorId, request));
    }

    /**
     * 合批：2～5 个当前可用的 RELEASED 父批合并为一个全新子批，重新校验全部父批物料。
     */
    @PostMapping("/api/batches/merge")
    public ResponseEntity<String> merge(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody MergeRequest request) {
        return stored(service.merge(actorId, request));
    }

    /**
     * 批次合格证明细：当前绑定与放行快照。
     */
    @GetMapping("/api/batches/{batchKey}/certificate")
    public BatchCertificateResponse batchCertificate(@PathVariable String batchKey) {
        return service.batchCertificate(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}