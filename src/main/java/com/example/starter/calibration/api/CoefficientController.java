package com.example.starter.calibration.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.CoefficientResponse;
import com.example.starter.calibration.api.dto.PublishCoefficientRequest;
import com.example.starter.calibration.service.CoefficientService;

/**
 * 仪器型号环境补偿系数版本接口：发布新版本（仅影响后续测量）、查询当前生效版本、版本历史。
 */
@RestController
@RequestMapping("/api/coefficients")
public class CoefficientController {

    private final CoefficientService coefficients;

    public CoefficientController(CoefficientService coefficients) {
        this.coefficients = coefficients;
    }

    /**
     * 发布新生效补偿系数版本：201；参数非法 400。旧版本转历史，已固化测量快照不改写。
     */
    @PostMapping
    public ResponseEntity<CoefficientResponse> publish(@RequestBody PublishCoefficientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(coefficients.publish(request));
    }

    /**
     * 查询型号当前生效系数版本：200；无版本 404。
     */
    @GetMapping("/active")
    public CoefficientResponse active(@RequestParam String instrumentModel) {
        return coefficients.active(instrumentModel);
    }

    /**
     * 查询型号全部系数版本（版本号升序）。
     */
    @GetMapping("/history/{model}")
    public List<CoefficientResponse> history(@PathVariable String model) {
        return coefficients.history(model);
    }
}
