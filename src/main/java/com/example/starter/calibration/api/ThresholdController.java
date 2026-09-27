package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CreateThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.service.ThresholdService;

/**
 * 仪器不确定度阈值版本接口：创建（版本只增不改）、查询。
 */
@RestController
@RequestMapping("/api/thresholds")
public class ThresholdController {

    private final ThresholdService thresholds;

    public ThresholdController(ThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * 创建阈值版本：201；参数非法 400；阈值为负 422；区间重叠或开放区间已存在 409。
     */
    @PostMapping
    public ResponseEntity<ThresholdResponse> create(@RequestBody CreateThresholdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(thresholds.create(request));
    }

    /**
     * 查询阈值版本：200；不存在 404。只读。
     */
    @GetMapping("/{id}")
    public ThresholdResponse get(@PathVariable long id) {
        return thresholds.get(id);
    }
}
