package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CompatibilityService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 固件模块需求兼容矩阵登记与查询。
 */
@RestController
@RequestMapping("/api/firmware-matrices")
public class MatrixController {

    private final CompatibilityService compatibilityService;

    public MatrixController(CompatibilityService compatibilityService) {
        this.compatibilityService = compatibilityService;
    }

    @PostMapping
    public MatrixView register(@Valid @RequestBody RegisterMatrixRequest request) {
        return compatibilityService.registerMatrix(request);
    }

    @GetMapping("/{firmwareVersion:.+}")
    public MatrixView get(@PathVariable String firmwareVersion) {
        return compatibilityService.getMatrix(firmwareVersion);
    }
}
