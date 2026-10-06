package com.example.starter.calibration.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.ResolutionConfigRequest;
import com.example.starter.calibration.api.dto.ResolutionConfigResponse;
import com.example.starter.calibration.service.ResolutionConfigService;

/**
 * 仪器分辨率格点配置接口：创建/更新（expectedVersion 乐观版本 + requestId 幂等）、查询。
 */
@RestController
@RequestMapping("/api/instruments")
public class ResolutionConfigController {

    private final ResolutionConfigService resolutions;

    public ResolutionConfigController(ResolutionConfigService resolutions) {
        this.resolutions = resolutions;
    }

    /**
     * 创建或更新分辨率配置：创建 201、更新 200；参数非法 400；
     * 版本冲突 409（VERSION_CONFLICT）；requestId 异参 409（IDEMPOTENCY_CONFLICT）；
     * 同键同参重放返回首次完整响应（含首响状态码）。操作者通过 X-Actor-Id 请求头提供。
     */
    @PutMapping("/{instrumentId}/resolution")
    public ResponseEntity<ResolutionConfigResponse> upsert(@PathVariable String instrumentId,
                                                           @RequestBody ResolutionConfigRequest request,
                                                           @RequestHeader("X-Actor-Id") String actor) {
        ResolutionConfigService.ResolutionResult result = resolutions.upsert(instrumentId, request, actor);
        return ResponseEntity.status(result.status()).body(result.response());
    }

    /**
     * 查询仪器当前分辨率配置：200；未配置 404（未配置仪器沿用原规则）。
     */
    @GetMapping("/{instrumentId}/resolution")
    public ResolutionConfigResponse get(@PathVariable String instrumentId) {
        return resolutions.get(instrumentId);
    }
}
