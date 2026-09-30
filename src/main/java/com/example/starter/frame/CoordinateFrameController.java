package com.example.starter.frame;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 带版本坐标基准转换 API：规则版本发布、坐标观测接收与结果/规则查询。
 */
@RestController
@RequestMapping("/api/coordinate")
public class CoordinateFrameController {

    private final CoordinateFrameService coordinateFrameService;

    public CoordinateFrameController(CoordinateFrameService coordinateFrameService) {
        this.coordinateFrameService = coordinateFrameService;
    }

    /**
     * 发布坐标基准转换规则的新版本。
     */
    @PostMapping("/rules")
    public ResponseEntity<FrameRuleResponse> publishRule(@Valid @RequestBody PublishFrameRuleRequest request) {
        CoordinateFrameService.PublishOutcome outcome = coordinateFrameService.publish(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询规则链全部版本（版本号升序）。
     */
    @GetMapping("/rules/{ruleId}")
    public List<FrameRuleResponse> listRules(@PathVariable String ruleId) {
        return coordinateFrameService.listRules(ruleId);
    }

    /**
     * 接收一条携带原基准、坐标与观测时刻的现场观测。
     */
    @PostMapping("/observations")
    public ResponseEntity<CoordinateObservationResponse> observe(
            @Valid @RequestBody CoordinateObservationRequest request) {
        CoordinateFrameService.ObserveOutcome outcome = coordinateFrameService.observe(request);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    /**
     * 查询坐标观测：原坐标、目标坐标、规则版本与复核状态。
     */
    @GetMapping("/observations/{coordinateObservationId}")
    public CoordinateObservationResponse getObservation(@PathVariable String coordinateObservationId) {
        return coordinateFrameService.get(coordinateObservationId);
    }
}
