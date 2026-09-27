package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CertService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件签名证书链：登记、轮换、撤销、查询，以及证书域拒绝原因查询。
 */
@RestController
@RequestMapping("/api")
public class CertController {

    private final CertService certService;

    public CertController(CertService certService) {
        this.certService = certService;
    }

    @PostMapping("/firmwares/{firmwareVersion}/cert-chains")
    public CertChainView register(@PathVariable String firmwareVersion,
                                  @Valid @RequestBody RegisterCertChainRequest request) {
        return certService.register(firmwareVersion, request);
    }

    @GetMapping("/firmwares/{firmwareVersion}/cert-chains")
    public CertChainListResponse listChains(@PathVariable String firmwareVersion) {
        return certService.listChains(firmwareVersion);
    }

    @PostMapping("/firmwares/{firmwareVersion}/cert-chains/rotate")
    public CertChainView rotate(@PathVariable String firmwareVersion,
                                @Valid @RequestBody RotateCertChainRequest request) {
        return certService.rotate(firmwareVersion, request);
    }

    @PostMapping("/firmwares/{firmwareVersion}/cert-chains/{chainVersion}/revoke")
    public CertChainView revoke(@PathVariable String firmwareVersion, @PathVariable String chainVersion,
                                @Valid @RequestBody RevokeCertChainRequest request) {
        return certService.revoke(firmwareVersion, chainVersion, request);
    }

    @GetMapping("/cert-rejections")
    public CertRejectionListResponse rejections() {
        return certService.listRejections();
    }
}
