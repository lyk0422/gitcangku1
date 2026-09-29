package com.example.starter.calibration.api;

import java.util.List;

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
 * 仪器不确定度阈值版本接口：追加版本（只追加不改写）、版本历史查询。
 */
@RestController
@RequestMapping("/api/thresholds")
public class ThresholdController {

    private final ThresholdService thresholds;

    public ThresholdController(ThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * 追加阈值版本：201；阈值非法 422；生效起点不晚于上一版本 409。
     */
    @PostMapping
    public ResponseEntity<ThresholdResponse> create(@RequestBody CreateThresholdRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(thresholds.create(request));
    }

    /**
     * 阈值版本历史（版本号升序，只读）。
     */
    @GetMapping("/{instrumentId}")
    public List<ThresholdResponse> list(@PathVariable String instrumentId) {
        return thresholds.list(instrumentId);
    }
}
