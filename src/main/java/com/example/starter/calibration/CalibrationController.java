package com.example.starter.calibration;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 校准记录与复核作废接口。
 */
@RestController
@RequestMapping("/api/calibrations")
public class CalibrationController {

    private final CalibrationService calibrationService;

    public CalibrationController(CalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    /** 上报校准记录。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalibrationView create(@Valid @RequestBody CreateCalibrationRequest request) {
        return calibrationService.createCalibration(request);
    }

    /** 查询单条校准（含有效期与绑定影响）。 */
    @GetMapping("/{id}")
    public CalibrationView get(@PathVariable long id) {
        return calibrationService.getCalibration(id);
    }

    /** 查询天线的校准链。 */
    @GetMapping
    public List<CalibrationView> list(@RequestParam String antennaCode) {
        return calibrationService.listCalibrations(antennaCode);
    }

    /** 复核作废，返回作废影响。 */
    @PostMapping("/{id}/invalidate")
    public InvalidateResult invalidate(@PathVariable long id, @Valid @RequestBody InvalidateRequest request) {
        return calibrationService.invalidate(id, request);
    }
}