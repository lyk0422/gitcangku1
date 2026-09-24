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

import com.example.starter.calibration.api.dto.CheckResponse;
import com.example.starter.calibration.api.dto.InterimCheckView;
import com.example.starter.calibration.api.dto.IsolationIntervalView;
import com.example.starter.calibration.api.dto.SubmitCheckRequest;
import com.example.starter.calibration.service.CheckService;

/**
 * 仪器期间核查接口：提交核查、核查历史与明细、隔离区间及受影响结果查询。
 */
@RestController
@RequestMapping("/api/checks")
public class CheckController {

    private final CheckService checks;

    public CheckController(CheckService checks) {
        this.checks = checks;
    }

    /**
     * 提交期间核查：201；参数非法 400；checkKey 或同仪器同时刻重复、requestId 异参 409。
     * FAIL 时响应体携带追溯区间、受影响结果与区间内被拦截的待放行结果。
     */
    @PostMapping
    public ResponseEntity<CheckResponse> submit(@RequestBody SubmitCheckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(checks.submit(request));
    }

    /**
     * 核查历史：可按仪器过滤，按核查时刻升序。
     */
    @GetMapping
    public List<InterimCheckView> history(@RequestParam(required = false) String instrumentId) {
        return checks.history(instrumentId);
    }

    /**
     * 核查明细：不存在 404。
     */
    @GetMapping("/{checkKey}")
    public CheckResponse detail(@PathVariable String checkKey) {
        return checks.detail(checkKey);
    }

    /**
     * 隔离区间历史（含每个 FAIL 引入 SUSPECT 的受影响测量键）：可按仪器过滤。
     */
    @GetMapping("/isolation-intervals")
    public List<IsolationIntervalView> isolationIntervals(@RequestParam(required = false) String instrumentId) {
        return checks.intervalHistory(instrumentId);
    }
}
