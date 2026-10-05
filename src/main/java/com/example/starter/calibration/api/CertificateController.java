package com.example.starter.calibration.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.service.CertificateService;
import com.example.starter.calibration.service.IdempotencyService;

/**
 * 校准证书接口：创建（线性 LINEAR 与曲线 CURVE）、撤销、查询。
 * 写入支持 requestId 幂等：同键同参重放首次响应，异参 409，失败不占键。
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
     * 创建证书：201；参数非法 400；区间重叠 409；requestId 异参重放 409。
     */
    @PostMapping
    public ResponseEntity<CertificateResponse> create(
            @RequestBody CreateCertificateRequest request,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor) {
        CertificateResponse body = idempotency.execute(request.requestId(), "CERTIFICATE_CREATE",
                actor == null ? "" : actor.trim(), "certificate:" + request.instrumentId(),
                request, request.expectedVersion(),
                () -> certificates.create(request), CertificateResponse.class);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * 撤销证书：200；不存在 404；重复撤销 409；requestId 异参重放 409。
     */
    @PostMapping("/{id}/revoke")
    public CertificateResponse revoke(@PathVariable long id,
                                      @RequestParam(required = false) String requestId,
                                      @RequestParam(required = false) String expectedVersion,
                                      @RequestHeader(value = "X-Actor-Id", required = false) String actor) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        return idempotency.execute(requestId, "CERTIFICATE_REVOKE",
                actor == null ? "" : actor.trim(), "certificate:" + id,
                params, expectedVersion,
                () -> certificates.revoke(id), CertificateResponse.class);
    }

    /**
     * 查询证书：200；不存在 404。
     */
    @GetMapping("/{id}")
    public CertificateResponse get(@PathVariable long id) {
        return certificates.get(id);
    }
}
