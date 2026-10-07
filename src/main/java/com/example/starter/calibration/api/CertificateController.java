package com.example.starter.calibration.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.api.dto.RevokeCertificateRequest;
import com.example.starter.calibration.service.CertificateService;
import com.example.starter.calibration.service.IdempotencyService;

/**
 * 校准证书接口：创建、撤销、查询。写请求可携带全局 requestId 启用幂等去重。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService certificates;
    private final IdempotencyService idempotency;

    public CertificateController(CertificateService certificates, IdempotencyService idempotency) {
        this.certificates = certificates;
        this.idempotency = idempotency;
    }

    /**
     * 创建证书：201；参数非法 400（含非单调二次证书）；区间重叠 409；幂等改参 409。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestBody CreateCertificateRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("instrumentId", request.instrumentId());
        params.put("validFrom", request.validFrom());
        params.put("validTo", request.validTo());
        params.put("mode", request.mode());
        params.put("a", IdempotencyService.normalizeDecimal(request.a()));
        params.put("b", IdempotencyService.normalizeDecimal(request.b()));
        params.put("c", IdempotencyService.normalizeDecimal(request.c()));
        params.put("domainLo", IdempotencyService.normalizeDecimal(request.domainLo()));
        params.put("domainHi", IdempotencyService.normalizeDecimal(request.domainHi()));
        params.put("operator", request.operator());
        params.put("expectedVersion", request.expectedVersion());
        return idempotency.execute("CERTIFICATE_CREATE", request.instrumentId(), request.requestId(),
                request.operator(), params, HttpStatus.CREATED, () -> certificates.create(request));
    }

    /**
     * 撤销证书：200；不存在 404；重复撤销 409；幂等改参 409。请求体可选。
     */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<String> revoke(@PathVariable long id,
                                         @RequestBody(required = false) RevokeCertificateRequest request) {
        RevokeCertificateRequest body = request == null
                ? new RevokeCertificateRequest(null, null, null) : request;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("certificateId", id);
        params.put("operator", body.operator());
        params.put("expectedVersion", body.expectedVersion());
        return idempotency.execute("CERTIFICATE_REVOKE", "certificate:" + id, body.requestId(),
                body.operator(), params, HttpStatus.OK, () -> certificates.revoke(id));
    }

    /**
     * 查询证书：200；不存在 404。
     */
    @GetMapping("/{id}")
    public CertificateResponse get(@PathVariable long id) {
        return certificates.get(id);
    }
}
