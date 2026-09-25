package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CompatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 硬件型号目录与固件硬件兼容矩阵配置、查询。
 */
@RestController
@RequestMapping("/api")
public class CompatController {

    private final CompatService compatService;

    public CompatController(CompatService compatService) {
        this.compatService = compatService;
    }

    @PostMapping("/hardware-models")
    public HardwareModelView registerHardwareModel(@Valid @RequestBody RegisterHardwareModelRequest request) {
        return compatService.registerHardwareModel(request);
    }

    @GetMapping("/hardware-models")
    public HardwareModelListResponse listHardwareModels() {
        return new HardwareModelListResponse(compatService.listHardwareModels());
    }

    @GetMapping("/firmware/{firmwareVersion}/compat-matrix")
    public MatrixView getMatrix(@PathVariable String firmwareVersion) {
        return compatService.getMatrix(firmwareVersion);
    }

    @PostMapping("/firmware/compat-matrix")
    public MatrixView updateMatrix(@Valid @RequestBody UpdateMatrixRequest request) {
        return compatService.updateMatrix(request);
    }
}
