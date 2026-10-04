package com.example.starter.batch;

import com.example.starter.batch.dto.BatchCertificatesResponse;
import com.example.starter.batch.dto.BindCertificatesRequest;
import com.example.starter.batch.dto.CertificateDiagnosticsResponse;
import com.example.starter.batch.dto.CertificateHistoryResponse;
import com.example.starter.batch.dto.CertificateResponse;
import com.example.starter.batch.dto.MergeBatchesRequest;
import com.example.starter.batch.dto.PublishCertificateRequest;
import com.example.starter.batch.dto.WithdrawCertificateRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;

/**
 * 批次合格证匹配接口：声明发布/撤回/查询、批量绑定、合批与匹配诊断。
 */
@RestController
@RequestMapping("/api")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * 发布合格证声明版本；操作人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/certificates")
    public ResponseEntity<String> publish(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody PublishCertificateRequest request) {
        return stored(service.publish(actorId, request));
    }

    /**
     * 撤回声明版本：只影响后续绑定与放行，不改写历史。
     */
    @PostMapping("/certificates/{declarationKey}/withdraw")
    public ResponseEntity<String> withdraw(
            @PathVariable String declarationKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WithdrawCertificateRequest request) {
        return stored(service.withdraw(actorId, declarationKey, request));
    }

    /**
     * 声明最新版本明细。
     */
    @GetMapping("/certificates/{declarationKey}")
    public CertificateResponse detail(@PathVariable String declarationKey) {
        return service.detail(declarationKey);
    }

    /**
     * 声明版本历史：全部版本按 version 升序。
     */
    @GetMapping("/certificates/{declarationKey}/history")
    public CertificateHistoryResponse history(@PathVariable String declarationKey) {
        return service.history(declarationKey);
    }

    /**
     * 匹配诊断：对指定物料与批号，在给定 UTC 时点（缺省当前）评估全部声明版本。
     */
    @GetMapping("/certificates/diagnostics")
    public CertificateDiagnosticsResponse diagnostics(
            @RequestParam String materialCode,
            @RequestParam String batchNo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return service.diagnostics(materialCode, batchNo, at);
    }

    /**
     * 批量绑定：任一声明失效、物料或批号不匹配即整次 422 回滚。
     */
    @PostMapping("/batches/certificate-bindings")
    public ResponseEntity<String> bind(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindCertificatesRequest request) {
        return stored(service.bind(actorId, request));
    }

    /**
     * 批次合格证明细：全部绑定与放行快照。
     */
    @GetMapping("/batches/{batchKey}/certificates")
    public BatchCertificatesResponse batchCertificates(@PathVariable String batchKey) {
        return service.batchCertificates(batchKey);
    }

    /**
     * 合批：2～5 个 RELEASED 父批合并为新批，重新校验全部父物料的声明匹配。
     */
    @PostMapping("/batches/merge")
    public ResponseEntity<String> merge(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody MergeBatchesRequest request) {
        return stored(service.merge(actorId, request));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}