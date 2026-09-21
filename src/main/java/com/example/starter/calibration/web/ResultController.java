package com.example.starter.calibration.web;

import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.web.dto.MeasurementResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前可用结果查询：已放行且证书未被撤销的测量结果。
 */
@RestController
@RequestMapping("/api/results")
public class ResultController {

    private final MeasurementService measurementService;

    public ResultController(MeasurementService measurementService) {
        this.measurementService = measurementService;
    }

    /**
     * 查询当前可用结果，可按仪器过滤。
     */
    @GetMapping("/current")
    public List<MeasurementResponse> currentUsable(
            @RequestParam(required = false) String instrumentId) {
        return measurementService.findCurrentUsable(instrumentId).stream()
                .map(MeasurementResponse::from)
                .toList();
    }
}
