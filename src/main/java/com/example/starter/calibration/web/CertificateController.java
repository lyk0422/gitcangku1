package com.example.starter.calibration.web;

import com.example.starter.calibration.service.CertificateService;
import com.example.starter.calibration.service.InputValidation;
import com.example.starter.calibration.web.dto.CertificateResponse;
import com.example.starter.calibration.web.dto.CreateCertificateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证书接口：创建、撤销、查询。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertificateController {

    private final CertificateService certificateService;

    public CertificateController(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    /**
     * 创建证书。区间重叠返回 409，区间非法返回 400。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateResponse create(@Valid @RequestBody CreateCertificateRequest request) {
        var certificate = certificateService.create(
                request.instrumentId(),
                InputValidation.parseInstant("validFrom", request.validFrom()),
                InputValidation.parseInstant("validTo", request.validTo()),
                InputValidation.parseDecimal("a", request.a()),
                InputValidation.parseDecimal("b", request.b()));
        return CertificateResponse.from(certificate);
    }

    /**
     * 撤销证书。不存在返回 404，已撤销返回 409。
     */
    @PostMapping("/{id}/revoke")
    public CertificateResponse revoke(@PathVariable long id) {
        return CertificateResponse.from(certificateService.revoke(id));
    }

    /**
     * 按 ID 查询证书。
     */
    @GetMapping("/{id}")
    public CertificateResponse get(@PathVariable long id) {
        return CertificateResponse.from(certificateService.get(id));
    }

    /**
     * 查询某仪器的全部证书。
     */
    @GetMapping
    public List<CertificateResponse> listByInstrument(@RequestParam String instrumentId) {
        return certificateService.listByInstrument(instrumentId).stream()
                .map(CertificateResponse::from)
                .toList();
    }
}
