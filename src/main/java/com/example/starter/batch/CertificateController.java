package com.example.starter.batch;

import com.example.starter.batch.dto.BatchBindRequest;
import com.example.starter.batch.dto.BatchBindResponse;
import com.example.starter.batch.dto.BindCertificateRequest;
import com.example.starter.batch.dto.DeclarationDiagnosticsResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 供应商合格证声明与批次绑定接口。时间一律为 ISO-8601 UTC，有效区间左闭右开。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    /**
     * 发布声明版本；操作人经 X-Actor-Id 请求头提供。
     */
    @PostMapping("/declarations")
    public ResponseEntity<String> publish(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody PublishDeclarationRequest request) {
        return stored(service.publish(actorId, request));
    }

    /**
     * 撤回声明版本：只影响后续绑定与放行，不改写历史快照。
     */
    @PostMapping("/declarations/{declarationKey}/versions/{version}/withdraw")
    public ResponseEntity<String> withdraw(
            @PathVariable String declarationKey,
            @PathVariable int version,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody WithdrawDeclarationRequest request) {
        return stored(service.withdraw(actorId, declarationKey, version, request.commandKey()));
    }

    /**
     * 声明版本明细列表：可按物料过滤，稳定排序。
     */
    @GetMapping("/declarations")
    public List<DeclarationResponse> listDeclarations(
            @RequestParam(name = "materialCode", required = false) String materialCode) {
        return service.listDeclarations(materialCode);
    }

    /**
     * 某声明键的版本历史，按版本号稳定排序。
     */
    @GetMapping("/declarations/{declarationKey}/history")
    public List<DeclarationResponse> declarationHistory(@PathVariable String declarationKey) {
        return service.declarationHistory(declarationKey);
    }

    /**
     * 匹配诊断：对给定物料、批号与时点逐个声明版本给出判定与原因。
     */
    @GetMapping("/diagnostics")
    public DeclarationDiagnosticsResponse diagnostics(
            @RequestParam String materialCode,
            @RequestParam String batchNo,
            @RequestParam(name = "at", required = false) Instant at) {
        return service.diagnostics(materialCode, batchNo, at);
    }

    /**
     * 单个批次绑定声明版本。
     */
    @PostMapping("/bindings")
    public ResponseEntity<String> bind(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BindCertificateRequest request) {
        return stored(service.bind(actorId, request));
    }

    /**
     * 批量绑定：任一失效声明整次回滚。
     */
    @PostMapping("/bindings/batch")
    public ResponseEntity<String> bindBatch(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody BatchBindRequest request) {
        return stored(service.bindBatch(actorId, request));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}