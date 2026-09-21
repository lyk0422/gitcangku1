package com.example.starter.calibration.web;

import com.example.starter.calibration.service.InputValidation;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.web.dto.MeasurementHistoryResponse;
import com.example.starter.calibration.web.dto.MeasurementResponse;
import com.example.starter.calibration.web.dto.SubmitMeasurementRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测量接口：提交、历史明细查询。
 */
@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementService measurementService;

    public MeasurementController(MeasurementService measurementService) {
        this.measurementService = measurementService;
    }

    /**
     * 提交测量。无有效证书返回 422，measurementKey 重复返回 409。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeasurementResponse submit(@Valid @RequestBody SubmitMeasurementRequest request) {
        var measurement = measurementService.submit(
                request.measurementKey(),
                request.instrumentId(),
                InputValidation.parseInstant("measuredAt", request.measuredAt()),
                InputValidation.parseDecimal("rawReading", request.rawReading()),
                InputValidation.parseDecimal("lowerLimit", request.lowerLimit()),
                InputValidation.parseDecimal("upperLimit", request.upperLimit()),
                request.submittedBy());
        return MeasurementResponse.from(measurement);
    }

    /**
     * 查询测量历史明细（含放行历史，证书撤销后仍保留）。不存在返回 404。
     */
    @GetMapping("/{id}")
    public MeasurementHistoryResponse getHistory(@PathVariable long id) {
        return MeasurementHistoryResponse.from(measurementService.getHistory(id));
    }
}
