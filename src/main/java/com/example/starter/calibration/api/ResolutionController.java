package com.example.starter.calibration.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.ResolutionResponse;
import com.example.starter.calibration.api.dto.UpsertResolutionRequest;
import com.example.starter.calibration.service.ResolutionService;

/**
 * 仪器分辨率格点配置接口：新建/更新（幂等）、查询。
 */
@RestController
@RequestMapping("/api/instruments")
public class ResolutionController {

    private final ResolutionService resolutions;

    public ResolutionController(ResolutionService resolutions) {
        this.resolutions = resolutions;
    }

    /**
     * 新建或更新分辨率配置：新建 201（版本从 1 起）、更新 200（携带 expectedVersion）；
     * 参数非法 400；版本冲突/已存在/幂等键异参 409；更新未配置仪器 404。
     * 同 requestId 同参重放首次完整响应。操作者通过 X-Actor-Id 请求头提供。
     */
    @PutMapping("/{instrumentId}/resolution")
    public ResponseEntity<ResolutionResponse> upsert(@PathVariable String instrumentId,
                                                     @RequestBody UpsertResolutionRequest request,
                                                     @RequestHeader("X-Actor-Id") String actor) {
        ResolutionService.StoredResponse result = resolutions.upsert(instrumentId, request, actor);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    /**
     * 查询仪器当前分辨率配置：200；未配置 404。
     */
    @GetMapping("/{instrumentId}/resolution")
    public ResolutionResponse get(@PathVariable String instrumentId) {
        return resolutions.get(instrumentId);
    }
}
