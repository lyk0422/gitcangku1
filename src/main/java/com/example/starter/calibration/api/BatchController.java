package com.example.starter.calibration.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.ReReleaseRequest;
import com.example.starter.calibration.api.dto.ReReleaseResponse;
import com.example.starter.calibration.service.ReReleaseService;

/**
 * 放行批次接口：复核驳回后的重新放行。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final ReReleaseService reReleases;

    public BatchController(ReReleaseService reReleases) {
        this.reReleases = reReleases;
    }

    /**
     * 重新放行：200；参数非法 400；批次不存在 404；
     * 批次非复核驳回状态/映射缺漏或多余/证书失效/值越界/版本变化/requestId 异参 409。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/{batchId}/re-release")
    public ReReleaseResponse reRelease(@PathVariable String batchId,
                                       @RequestBody ReReleaseRequest request,
                                       @RequestHeader("X-Actor-Id") String actor) {
        return reReleases.reRelease(batchId, request, actor);
    }
}
