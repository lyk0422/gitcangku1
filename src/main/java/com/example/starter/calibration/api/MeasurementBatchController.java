package com.example.starter.calibration.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.ReplaceReferenceRequest;
import com.example.starter.calibration.api.dto.ReplaceReferenceResponse;
import com.example.starter.calibration.service.ReplaceReferenceService;

/**
 * 提交批次接口：对未放行批次替换标准器并整批重算。
 */
@RestController
@RequestMapping("/api/measurement-batches")
public class MeasurementBatchController {

    private final ReplaceReferenceService replaceReference;

    public MeasurementBatchController(ReplaceReferenceService replaceReference) {
        this.replaceReference = replaceReference;
    }

    /**
     * 替换标准器：200；批次或证书不存在 404；批次已放行或证书已撤销 409；
     * 任一测量重算失败整批 422，旧版本仍是当前有效版本。
     */
    @PostMapping("/{batchId}/replace-reference")
    public ReplaceReferenceResponse replaceReference(@PathVariable String batchId,
                                                     @RequestBody ReplaceReferenceRequest request) {
        return replaceReference.replace(batchId, request);
    }
}
