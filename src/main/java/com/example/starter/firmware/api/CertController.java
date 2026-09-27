package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CertService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 证书链：登记/轮换、撤销、按固件版本查询与当前链查询。
 */
@RestController
@RequestMapping("/api/certificates")
public class CertController {

    private final CertService certService;

    public CertController(CertService certService) {
        this.certService = certService;
    }

    @PostMapping
    public CertChainView register(@Valid @RequestBody RegisterCertRequest request) {
        return certService.register(request);
    }

    @PostMapping("/{chainId}/revoke")
    public CertChainView revoke(@PathVariable long chainId, @Valid @RequestBody RequestIdBody request) {
        return certService.revoke(chainId, request.requestId());
    }

    @GetMapping
    public CertChainListResponse list(@RequestParam String firmwareVersion) {
        return certService.list(firmwareVersion);
    }

    @GetMapping("/current")
    public CertChainView current(@RequestParam String firmwareVersion) {
        return certService.current(firmwareVersion);
    }
}
