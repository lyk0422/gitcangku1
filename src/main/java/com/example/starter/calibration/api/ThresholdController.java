package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.SetThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdView;
import com.example.starter.calibration.service.ThresholdService;

/**
 * 仪器不确定度阈值接口：登记（版本化）、当前阈值与历史查询。
 */
@RestController
@RequestMapping("/api/instruments/{instrumentId}")
public class ThresholdController {

    private final ThresholdService thresholds;

    public ThresholdController(ThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * 登记新版本阈值：200；阈值缺失或非法 422；requestId 同参重放返回首次快照，异参 409。
     * 登记人通过 X-Actor-Id 请求头提供。
     */
    @PutMapping("/threshold")
    public ThresholdView setThreshold(@PathVariable String instrumentId,
                                      @RequestBody SetThresholdRequest request,
                                      @RequestHeader("X-Actor-Id") String actor) {
        return thresholds.setThreshold(instrumentId, request, actor);
    }

    /**
     * 当前最新版本阈值；未登记返回 404。
     */
    @GetMapping("/threshold")
    public ThresholdView current(@PathVariable String instrumentId) {
        return thresholds.current(instrumentId);
    }

    /**
     * 阈值版本历史（按版本升序）。
     */
    @GetMapping("/thresholds")
    public List<ThresholdView> history(@PathVariable String instrumentId) {
        return thresholds.history(instrumentId);
    }
}
