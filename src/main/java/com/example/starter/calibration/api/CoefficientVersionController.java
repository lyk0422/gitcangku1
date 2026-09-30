package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CoefficientVersionResponse;
import com.example.starter.calibration.api.dto.CreateCoefficientVersionRequest;
import com.example.starter.calibration.service.CoefficientVersionService;

/**
 * 环境补偿系数版本接口：创建（只增不改）、查询。
 */
@RestController
@RequestMapping("/api/coefficient-versions")
public class CoefficientVersionController {

    private final CoefficientVersionService versions;

    public CoefficientVersionController(CoefficientVersionService versions) {
        this.versions = versions;
    }

    /**
     * 创建系数版本：201；参数非法 400；标签重复 409。
     */
    @PostMapping
    public ResponseEntity<CoefficientVersionResponse> create(
            @RequestBody CreateCoefficientVersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(versions.create(request));
    }

    /**
     * 查询系数版本：200；不存在 404。
     */
    @GetMapping("/{id}")
    public CoefficientVersionResponse get(@PathVariable long id) {
        return versions.get(id);
    }
}
