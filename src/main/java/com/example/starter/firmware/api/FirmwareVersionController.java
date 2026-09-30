package com.example.starter.firmware.api;

import com.example.starter.firmware.service.FirmwareVersionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件版本兼容声明：型号集合、最低内存与必需能力标签。
 */
@RestController
@RequestMapping("/api/hw/versions")
public class FirmwareVersionController {

    private final FirmwareVersionService versionService;

    public FirmwareVersionController(FirmwareVersionService versionService) {
        this.versionService = versionService;
    }

    @PostMapping
    public FirmwareVersionView create(@Valid @RequestBody CreateFirmwareVersionRequest request) {
        return versionService.create(request);
    }
}
