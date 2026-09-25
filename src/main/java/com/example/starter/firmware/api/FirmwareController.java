package com.example.starter.firmware.api;

import com.example.starter.firmware.service.FirmwareCompatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件硬件兼容矩阵：配置（携带 expectedVersion 乐观校验）与查询。
 */
@RestController
@RequestMapping("/api/firmware")
@Validated
public class FirmwareController {

    private final FirmwareCompatService firmwareCompatService;

    public FirmwareController(FirmwareCompatService firmwareCompatService) {
        this.firmwareCompatService = firmwareCompatService;
    }

    @PutMapping("/{firmwareVersion}/compat")
    public FirmwareCompatView configure(@PathVariable @Size(max = 64) String firmwareVersion,
                                        @Valid @RequestBody ConfigureCompatRequest request) {
        return firmwareCompatService.configure(firmwareVersion, request);
    }

    @GetMapping("/{firmwareVersion}/compat")
    public FirmwareCompatView get(@PathVariable @Size(max = 64) String firmwareVersion) {
        return firmwareCompatService.get(firmwareVersion);
    }
}
