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

import com.example.starter.calibration.api.dto.CreateInterimCheckRequest;
import com.example.starter.calibration.api.dto.InterimCheckResponse;
import com.example.starter.calibration.api.dto.IsolationIntervalResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.service.InterimCheckService;

/**
 * 仪器期间核查接口：提交核查（PASS/FAIL 判定、追溯隔离与解除）、
 * 核查历史、隔离区间与受影响结果查询。
 */
@RestController
@RequestMapping("/api/interim-checks")
public class InterimCheckController {

    private final InterimCheckService checks;

    public InterimCheckController(InterimCheckService checks) {
        this.checks = checks;
    }

    /**
     * 提交核查：201；参数非法 400；checkKey/核查时刻/requestId 冲突 409。
     * FAIL 时区间内待放行结果的放行将返回 409 并指明触发的 checkKey。
     */
    @PostMapping
    public ResponseEntity<InterimCheckResponse> submit(@RequestBody CreateInterimCheckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(checks.submit(request));
    }

    /**
     * 核查历史：可按仪器过滤，按核查时刻升序。
     */
    @GetMapping
    public List<InterimCheckResponse> history(@RequestParam(required = false) String instrumentId) {
        return checks.history(instrumentId);
    }

    /**
     * 隔离区间历史：可按仪器、是否已解除过滤。
     */
    @GetMapping("/intervals")
    public List<IsolationIntervalResponse> intervals(@RequestParam(required = false) String instrumentId,
                                                     @RequestParam(required = false) Boolean resolved) {
        return checks.intervals(instrumentId, resolved);
    }

    /**
     * 某 FAIL 核查追溯区间内受影响结果。
     */
    @GetMapping("/{checkKey}/affected-results")
    public List<MeasurementResponse> affectedResults(@PathVariable String checkKey) {
        return checks.affectedResults(checkKey);
    }

    /**
     * 按核查键查询核查记录，不存在 404。
     */
    @GetMapping("/{checkKey}")
    public InterimCheckResponse get(@PathVariable String checkKey) {
        return checks.get(checkKey);
    }
}
