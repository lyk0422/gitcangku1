package com.example.starter.firmware.api;

import com.example.starter.firmware.service.MatrixService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 兼容矩阵：固件模块需求与模块依赖边登记（成功一次矩阵版本加一），及只读明细诊断。
 */
@RestController
@RequestMapping("/api/matrix")
public class MatrixController {

    private final MatrixService matrixService;

    public MatrixController(MatrixService matrixService) {
        this.matrixService = matrixService;
    }

    @GetMapping
    public MatrixView current() {
        return matrixService.currentView();
    }

    @PostMapping("/firmware-requirements")
    public MatrixView registerFirmwareRequirement(
            @Valid @RequestBody RegisterFirmwareRequirementRequest request) {
        return matrixService.registerFirmwareRequirement(request);
    }

    @PostMapping("/module-dependencies")
    public MatrixView registerModuleDependency(@Valid @RequestBody RegisterModuleDependencyRequest request) {
        return matrixService.registerModuleDependency(request);
    }

    @GetMapping("/firmware-requirements")
    public List<FirmwareRequirementView> firmwareRequirements() {
        return matrixService.listFirmwareRequirements();
    }

    @GetMapping("/module-dependencies")
    public List<ModuleDependencyView> moduleDependencies() {
        return matrixService.listModuleDependencies();
    }
}
