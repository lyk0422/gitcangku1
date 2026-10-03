package com.example.starter.web;

import com.example.starter.domain.CalibrationRecord;
import com.example.starter.service.CalibrationService;
import com.example.starter.service.CalibrationService.CalibrationChain;
import com.example.starter.service.CalibrationService.VoidImpact;
import com.example.starter.web.dto.CreateCalibrationRequest;
import com.example.starter.web.dto.VoidCalibrationRequest;
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
 * 校准接口：登记校准、复核作废、查询校准与天线校准链。
 */
@RestController
@RequestMapping("/api")
public class CalibrationController {

    private final CalibrationService calibrationService;

    public CalibrationController(CalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    /** 登记校准记录，返回通过与有效期计算结果。 */
    @PostMapping("/calibrations")
    @ResponseStatus(HttpStatus.CREATED)
    public CalibrationRecord create(@Valid @RequestBody CreateCalibrationRequest request) {
        return calibrationService.create(request);
    }

    /** 复核作废校准，返回对既有绑定的影响清单。 */
    @PostMapping("/calibrations/{id}/void")
    public VoidImpact voidCalibration(@PathVariable long id,
            @Valid @RequestBody VoidCalibrationRequest request) {
        return calibrationService.voidCalibration(id, request.reason());
    }

    /** 查询单条校准记录。 */
    @GetMapping("/calibrations/{id}")
    public CalibrationRecord get(@PathVariable long id) {
        return calibrationService.get(id);
    }

    /** 查询天线校准链：校准记录、有效期、任务绑定版本与作废影响。 */
    @GetMapping("/antennas/{antennaCode}/calibration-chain")
    public CalibrationChain chain(@PathVariable String antennaCode) {
        return calibrationService.chain(antennaCode);
    }
}