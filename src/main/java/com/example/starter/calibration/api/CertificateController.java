package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.api.dto.CreateQuadraticCertificateRequest;
import com.example.starter.calibration.service.CertificateService;
import com.example.starter.calibration.service.IdemOutcome;

/**
 * 校准证书接口：创建（线性/二次）、撤销、查询。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService certificates;

    public CertificateController(CertificateService certificates) {
        this.certificates = certificates;
    }

    /**
     * 创建线性证书：201；参数非法 400；区间重叠 409。
     */
    @PostMapping
    public ResponseEntity<CertificateResponse> create(@RequestBody CreateCertificateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(certificates.create(request));
    }

    /**
     * 创建二次证书（幂等）：201；参数非法或非单调 400；区间重叠 409；
     * requestId 重放返回首次完整响应，改参 409。
     */
    @PostMapping("/quadratic")
    public ResponseEntity<String> createQuadratic(@RequestBody CreateQuadraticCertificateRequest request) {
        IdemOutcome outcome = certificates.createQuadratic(request);
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }

    /**
     * 撤销证书：200；不存在 404；重复撤销 409。
     */
    @PostMapping("/{id}/revoke")
    public CertificateResponse revoke(@PathVariable long id) {
        return certificates.revoke(id);
    }

    /**
     * 查询证书：200；不存在 404。
     */
    @GetMapping("/{id}")
    public CertificateResponse get(@PathVariable long id) {
        return certificates.get(id);
    }
}
