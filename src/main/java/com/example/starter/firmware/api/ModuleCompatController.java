package com.example.starter.firmware.api;

import com.example.starter.firmware.service.MatrixService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块兼容矩阵：登记（版本校验、依赖环校验）、当前矩阵与登记历史查询。
 */
@RestController
@RequestMapping("/api/compat/matrix")
public class ModuleCompatController {

    private final MatrixService matrixService;

    public ModuleCompatController(MatrixService matrixService) {
        this.matrixService = matrixService;
    }

    @PostMapping
    public MatrixRegisterResponse register(@Valid @RequestBody MatrixRegisterRequest request) {
        return matrixService.register(request);
    }

    @GetMapping
    public MatrixView current() {
        return matrixService.current();
    }

    @GetMapping("/revisions")
    public MatrixRevisionListResponse revisions() {
        return matrixService.revisions();
    }
}
