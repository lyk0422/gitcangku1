package com.example.starter.firmware.api;

import com.example.starter.firmware.service.FirmwareService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件版本登记与兼容矩阵维护。
 */
@RestController
@RequestMapping("/api/firmware")
public class FirmwareController {

    private final FirmwareService firmwareService;

    public FirmwareController(FirmwareService firmwareService) {
        this.firmwareService = firmwareService;
    }

    @PostMapping("/versions")
    public FirmwareVersionView register(@Valid @RequestBody RegisterFirmwareRequest request) {
        return firmwareService.register(request);
    }

    @PostMapping("/compatibility")
    public FirmwareVersionView putCompatibility(@Valid @RequestBody CompatibilityRequest request) {
        return firmwareService.putCompatibility(request);
    }

    @GetMapping("/versions/{model}/{version}")
    public FirmwareVersionView get(@PathVariable String model, @PathVariable String version) {
        return FirmwareVersionView.of(firmwareService.requireFirmware(model, version));
    }
}
