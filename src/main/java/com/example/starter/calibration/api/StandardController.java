package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateStandardRequest;
import com.example.starter.calibration.api.dto.StandardResponse;
import com.example.starter.calibration.service.StandardService;

/**
 * 标准器版本接口：创建（血缘无环、窗口含于父级）与查询。
 */
@RestController
@RequestMapping("/api/standards")
public class StandardController {

    private final StandardService standards;

    public StandardController(StandardService standards) {
        this.standards = standards;
    }

    /**
     * 创建标准器版本：201；参数非法 400；父级不存在 422；窗口超出父级/成环/业务键重复 409。
     */
    @PostMapping
    public ResponseEntity<StandardResponse> create(@RequestBody CreateStandardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(standards.create(request));
    }

    /**
     * 查询标准器版本：200；不存在 404。
     */
    @GetMapping("/{standardId}")
    public StandardResponse get(@PathVariable String standardId) {
        return standards.get(standardId);
    }
}
