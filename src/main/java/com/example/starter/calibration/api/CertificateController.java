package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.service.CertificateService;

/**
 * 校准证书（标准器证书）接口：创建、撤销、查询、时间线。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService certificates;

    public CertificateController(CertificateService certificates) {
        this.certificates = certificates;
    }

    /**
     * 创建证书：201；参数非法 400；区间重叠 409。
     */
    @PostMapping
    public ResponseEntity<CertificateResponse> create(@RequestBody CreateCertificateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(certificates.create(request));
    }

    /**
     * 撤销证书：200；不存在 404；重复撤销 409。
     */
    @PostMapping("/{id}/revoke")
    public CertificateResponse revoke(@PathVariable long id) {
        return certificates.revoke(id);
    }

    /**
     * 证书时间线：某仪器全部证书（含已撤销），按有效期起点升序；instrumentId 缺失 400。
     */
    @GetMapping("/timeline")
    public List<CertificateResponse> timeline(@RequestParam String instrumentId) {
        return certificates.timeline(instrumentId);
    }

    /**
     * 查询证书：200；不存在 404。
     */
    @GetMapping("/{id}")
    public CertificateResponse get(@PathVariable long id) {
        return certificates.get(id);
    }
}
