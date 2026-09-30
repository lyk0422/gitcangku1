package com.example.starter.firmware.api;

import com.example.starter.firmware.service.HardwareRequirementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件版本硬件兼容要求声明与查询：型号集合、最低内存、必需能力标签。
 */
@RestController
@RequestMapping("/api/hardware-requirements")
public class HardwareRequirementController {

    private final HardwareRequirementService service;

    public HardwareRequirementController(HardwareRequirementService service) {
        this.service = service;
    }

    @PostMapping
    public HardwareRequirementView declare(@Valid @RequestBody DeclareHardwareRequest request) {
        return service.declare(request);
    }

    @GetMapping("/{firmwareVersion}")
    public HardwareRequirementView get(@PathVariable String firmwareVersion) {
        return service.get(firmwareVersion);
    }
}
