package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.api.dto.RevokeRequest;
import com.example.starter.calibration.service.CertificateService;

/**
 * 校准证书接口：创建（线性/曲线）、撤销、查询。创建后不可修改，仅可撤销。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService certificates;

    public CertificateController(CertificateService certificates) {
        this.certificates = certificates;
    }

    /**
     * 创建证书：201；参数非法 400；区间重叠 409；requestId 异参重放 409。
     */
    @PostMapping
    public ResponseEntity<CertificateResponse> create(@RequestBody CreateCertificateRequest request,
                                                      @RequestHeader(value = "X-Actor-Id", required = false)
                                                      String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(certificates.create(request, actor));
    }

    /**
     * 撤销证书：200；不存在 404；重复撤销 409；requestId 异参重放 409。
     */
    @PostMapping("/{id}/revoke")
    public CertificateResponse revoke(@PathVariable long id,
                                      @RequestBody(required = false) RevokeRequest request,
                                      @RequestHeader(value = "X-Actor-Id", required = false) String actor) {
        return certificates.revoke(id, request, actor);
    }

    /**
     * 查询证书：200；不存在 404。
     */
    @GetMapping("/{id}")
    public CertificateResponse get(@PathVariable long id) {
        return certificates.get(id);
    }
}
