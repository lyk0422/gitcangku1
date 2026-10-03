package com.example.starter.kiln;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 窑炉批次 REST 接口。
 */
@RestController
@RequestMapping("/api")
public class KilnController {

    private final KilnService service;

    public KilnController(KilnService service) {
        this.service = service;
    }

    /** 创建升温程序版本（含连续程序段）。 */
    @PostMapping("/programs")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createProgram(@Valid @RequestBody KilnDtos.CreateProgramRequest request) {
        return Map.of("programId", service.createProgram(request));
    }

    /** 入炉建批，锁定程序版本。 */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> createBatch(@Valid @RequestBody KilnDtos.CreateBatchRequest request) {
        return Map.of("batchId", service.createBatch(request));
    }

    /** 批次详情：程序版本、各段最坏偏差与放行依据。 */
    @GetMapping("/batches/{batchId}")
    public KilnDtos.BatchDetail detail(@PathVariable long batchId) {
        return service.detail(batchId);
    }

    /** 测温点追加上传（requestId 幂等）。 */
    @PostMapping("/batches/{batchId}/points")
    public KilnDtos.UploadResult uploadPoints(@PathVariable long batchId,
                                              @Valid @RequestBody KilnDtos.UploadPointsRequest request) {
        return service.uploadPoints(batchId, request);
    }

    /** 标记异常。 */
    @PostMapping("/batches/{batchId}/anomalies")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> markAnomaly(@PathVariable long batchId,
                                         @Valid @RequestBody KilnDtos.MarkAnomalyRequest request) {
        return Map.of("anomalyId", service.markAnomaly(batchId, request));
    }

    /** 质检放行或驳回。 */
    @PostMapping("/batches/{batchId}/release")
    public KilnDtos.ReleaseResult release(@PathVariable long batchId,
                                          @Valid @RequestBody KilnDtos.ReleaseRequest request) {
        return service.release(batchId, request);
    }

    /** 对已标记异常的批次发起独立复烧批次。 */
    @PostMapping("/batches/{batchId}/refire")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> refire(@PathVariable long batchId) {
        return Map.of("batchId", service.refire(batchId));
    }
}