package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.CertService;
import com.example.starter.firmware.service.FirmwareService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 固件签名证书链：登记、轮换、撤销与查询。查询当前链时 atUtc 缺省取服务端当前 UTC 时刻。
 */
@RestController
@RequestMapping("/api/firmware/cert-chains")
public class CertController {

    private final CertService certService;
    private final FirmwareService firmwareService;
    private final Clock clock;

    public CertController(CertService certService, FirmwareService firmwareService, Clock clock) {
        this.certService = certService;
        this.firmwareService = firmwareService;
        this.clock = clock;
    }

    @PostMapping
    public CertChainView register(@Valid @RequestBody RegisterCertChainRequest request) {
        return certService.registerChain(request);
    }

    @PostMapping("/rollover")
    public CertChainView rollover(@Valid @RequestBody RolloverCertRequest request) {
        return certService.rollover(request);
    }

    @PostMapping("/revoke")
    public CertChainView revoke(@Valid @RequestBody RevokeCertRequest request) {
        return certService.revoke(request);
    }

    @GetMapping
    public List<CertChainView> list(@RequestParam String model, @RequestParam String firmwareVersion) {
        return certService.listChains(model, firmwareVersion);
    }

    /**
     * 查询请求时刻唯一有效链；固件未启用证书或时刻无有效链时 current 为 null。
     */
    @GetMapping("/current")
    public CertChainView current(@RequestParam String model, @RequestParam String firmwareVersion,
                                 @RequestParam(required = false) String atUtc) {
        FirmwareVersion firmware = firmwareService.requireFirmware(model, firmwareVersion);
        Instant at;
        if (atUtc == null || atUtc.isBlank()) {
            at = Instant.now(clock);
        } else {
            try {
                at = Instant.parse(atUtc);
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("INVALID_INSTANT",
                        "atUtc 必须为 UTC ISO-8601 时刻（如 2026-09-27T00:00:00Z）");
            }
        }
        return certService.currentForFirmware(firmware.id(), at)
                .map(chain -> CertChainView.of(chain, model, firmwareVersion,
                        certService.deviceIdsOf(chain.id())))
                .orElse(null);
    }
}
