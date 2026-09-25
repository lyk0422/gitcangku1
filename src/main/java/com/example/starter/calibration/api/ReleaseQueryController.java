package com.example.starter.calibration.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.ReleaseDiagnosticsResponse;
import com.example.starter.calibration.service.ReleaseService;

/**
 * 放行查询接口：放行诊断。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseQueryController {

    private final ReleaseService releases;

    public ReleaseQueryController(ReleaseService releases) {
        this.releases = releases;
    }

    /**
     * 放行诊断：批次头与逐条测量的标准器、补偿系数、不确定度版本追溯；批次不存在 404。
     */
    @GetMapping("/{batchId}/diagnostics")
    public ReleaseDiagnosticsResponse diagnostics(@PathVariable String batchId) {
        return releases.diagnostics(batchId);
    }
}
