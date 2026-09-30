package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CoefficientVersionResponse;
import com.example.starter.calibration.api.dto.CreateCoefficientVersionRequest;
import com.example.starter.calibration.service.CoefficientVersionService;

/**
 * 环境补偿系数版本接口：写入（更新=闭合开放版本并追加新版本）、按仪器查询。
 */
@RestController
@RequestMapping("/api/coefficient-versions")
public class CoefficientVersionController {

    private final CoefficientVersionService versions;

    public CoefficientVersionController(CoefficientVersionService versions) {
        this.versions = versions;
    }

    /**
     * 写入系数版本：201；参数非法 400；新生效起点不晚于开放版本起点 400。
     */
    @PostMapping
    public ResponseEntity<CoefficientVersionResponse> create(
            @RequestBody CreateCoefficientVersionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(versions.createOrUpdate(request));
    }

    /**
     * 按仪器列出全部系数版本（生效起点升序）。
     */
    @GetMapping
    public List<CoefficientVersionResponse> list(@RequestParam String instrumentId) {
        return versions.list(instrumentId);
    }
}
