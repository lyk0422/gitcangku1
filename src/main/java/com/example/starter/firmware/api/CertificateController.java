package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CertificateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 固件版本、签名证书链（登记/轮换/撤销/查询）与兼容矩阵。
 */
@RestController
@RequestMapping("/api/firmwares")
public class CertificateController {

    private final CertificateService certificateService;

    public CertificateController(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @PostMapping
    public String registerFirmware(@Valid @RequestBody RegisterFirmwareRequest request) {
        return certificateService.registerFirmware(request);
    }

    @PostMapping("/cert-chains")
    public CertChainView registerChain(@Valid @RequestBody RegisterCertChainRequest request) {
        return certificateService.registerChain(request);
    }

    @PostMapping("/cert-chains/rollover")
    public CertChainView rollover(@Valid @RequestBody RolloverCertChainRequest request) {
        return certificateService.rollover(request);
    }

    @PostMapping("/cert-chains/revoke")
    public CertChainView revoke(@Valid @RequestBody RevokeCertChainRequest request) {
        return certificateService.revoke(request);
    }

    @GetMapping("/{model}/versions/{version}/cert-chains")
    public CertChainListResponse chains(@PathVariable String model, @PathVariable("version") String version) {
        return certificateService.listChains(model, version);
    }

    @PostMapping("/compat")
    public CompatView upsertCompat(@Valid @RequestBody UpsertCompatRequest request) {
        return certificateService.upsertCompat(request);
    }

    @GetMapping("/compat")
    public List<CompatView> listCompat(@RequestParam String model) {
        return certificateService.listCompat(model);
    }
}
