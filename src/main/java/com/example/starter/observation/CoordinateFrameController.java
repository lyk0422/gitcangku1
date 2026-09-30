package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 带版本坐标基准转换 API：规则发布、坐标观测写入（转换 + 站内外复核判定）与查询。
 */
@RestController
@RequestMapping("/api/coordinate")
@Validated
public class CoordinateFrameController {

    private final CoordinateFrameService coordinateFrameService;

    public CoordinateFrameController(CoordinateFrameService coordinateFrameService) {
        this.coordinateFrameService = coordinateFrameService;
    }

    /**
     * 发布新版本坐标转换规则；版本号唯一，发布后不可变。
     */
    @PostMapping("/rules")
    public ResponseEntity<RuleResponse> publishRule(@Valid @RequestBody PublishRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(coordinateFrameService.publishRule(request));
    }

    /**
     * 按版本号查询转换规则。
     */
    @GetMapping("/rules/{ruleVersion}")
    public RuleResponse getRule(@PathVariable @Min(1) int ruleVersion) {
        return coordinateFrameService.getRule(ruleVersion);
    }

    /**
     * 写入坐标观测：按观测时刻唯一生效规则转换，站内容许自动合并、站外保留待复核。
     * requestId 同参重放首次结果；缺规则/命中多规则返回 422 且不入库；改参返回 409。
     */
    @PostMapping("/observations")
    public ResponseEntity<CoordinateObservationResponse> ingest(
            @Valid @RequestBody CoordinateObservationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(coordinateFrameService.ingest(request));
    }

    /**
     * 查询坐标观测：原坐标、目标坐标、目标基准、规则版本与复核状态一并返回。
     */
    @GetMapping("/observations/{observationId}")
    public CoordinateObservationResponse getObservation(@PathVariable String observationId) {
        return coordinateFrameService.getObservation(observationId);
    }
}
