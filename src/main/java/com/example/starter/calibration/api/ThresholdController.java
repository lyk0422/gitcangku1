package com.example.starter.calibration.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.PublishThresholdRequest;
import com.example.starter.calibration.api.dto.ThresholdResponse;
import com.example.starter.calibration.service.IdempotentResult;
import com.example.starter.calibration.service.ThresholdService;

/**
 * 仪器不确定度阈值接口：版本化发布（只增不改）、查询当前版本与指定版本。
 */
@RestController
@RequestMapping("/api/thresholds")
public class ThresholdController {

    private final ThresholdService thresholds;

    public ThresholdController(ThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * 发布阈值新版本：201；负值等非法值 422；X-Request-Id 同参重放返回首次快照，异参 409。
     */
    @PostMapping
    public ResponseEntity<ThresholdResponse> publish(@RequestBody PublishThresholdRequest request,
                                                     @RequestHeader("X-Actor-Id") String actor,
                                                     @RequestHeader(value = "X-Request-Id",
                                                             required = false) String requestId) {
        IdempotentResult<ThresholdResponse> result = thresholds.publish(request, actor, requestId);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 查询仪器当前阈值版本：200；从未发布 422。
     */
    @GetMapping("/current")
    public ThresholdResponse current(@RequestParam String instrumentId) {
        return thresholds.current(instrumentId);
    }

    /**
     * 按版本行 ID 查询阈值：200；不存在 404。
     */
    @GetMapping("/{id}")
    public ThresholdResponse get(@PathVariable long id) {
        return thresholds.get(id);
    }
}
