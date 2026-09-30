package com.example.starter.batch;

import com.example.starter.batch.dto.ConfirmDeviationRequest;
import com.example.starter.batch.dto.CreateSegmentRequest;
import com.example.starter.batch.dto.MergeRequest;
import com.example.starter.batch.dto.RegisterReadingRequest;
import com.example.starter.batch.dto.StorageTimelineResponse;
import com.example.starter.batch.dto.UpdateLimitsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓储温度偏差接口：仓储段限值、温度读数、偏差处置、时刻视图与合批门禁。
 */
@RestController
@RequestMapping("/api")
public class StorageController {

    private final StorageService service;

    public StorageController(StorageService service) {
        this.service = service;
    }

    /**
     * 在指定批次下创建仓储段（含温度上下限，单位℃）。
     */
    @PostMapping("/batches/{batchKey}/segments")
    public ResponseEntity<String> createSegment(@PathVariable String batchKey,
                                                @Valid @RequestBody CreateSegmentRequest request) {
        return stored(service.createSegment(batchKey, request));
    }

    /**
     * 修改仓储段温度上下限；只影响之后的新读数，历史偏差不回写。
     */
    @PutMapping("/batches/{batchKey}/segments/{segmentKey}/limits")
    public ResponseEntity<String> updateLimits(@PathVariable String batchKey,
                                               @PathVariable String segmentKey,
                                               @Valid @RequestBody UpdateLimitsRequest request) {
        return stored(service.updateLimits(batchKey, segmentKey, request));
    }

    /**
     * 登记仓储温度读数；越界或缺测自动生成偏差。仓储员身份由网关鉴权保证，本服务不另存登记人。
     */
    @PostMapping("/batches/{batchKey}/segments/{segmentKey}/readings")
    public ResponseEntity<String> registerReading(@PathVariable String batchKey,
                                                  @PathVariable String segmentKey,
                                                  @Valid @RequestBody RegisterReadingRequest request) {
        return stored(service.registerReading(batchKey, segmentKey, request));
    }

    /**
     * 仓储段时刻视图：原始读数、超限/缺测区间、偏差处置人与门禁状态。
     */
    @GetMapping("/batches/{batchKey}/segments/{segmentKey}/timeline")
    public StorageTimelineResponse timeline(@PathVariable String batchKey,
                                           @PathVariable String segmentKey) {
        return service.timeline(batchKey, segmentKey);
    }

    /**
     * 偏差处置确认；X-Actor-Id 为处置人，X-Approval-Role 为 QUALITY 或 OPERATIONS。
     */
    @PostMapping("/deviations/{deviationKey}/confirmations")
    public ResponseEntity<String> confirmDeviation(@PathVariable String deviationKey,
                                                   @RequestHeader(name = "X-Actor-Id", required = false)
                                                   String actorId,
                                                   @RequestHeader(name = "X-Approval-Role", required = false)
                                                   String role,
                                                   @Valid @RequestBody ConfirmDeviationRequest request) {
        return stored(service.confirmDeviation(deviationKey, actorId, role, request));
    }

    /**
     * 合批：多个已放行且无未处置偏差的父批合并为一个新隔离子批。
     */
    @PostMapping("/merges")
    public ResponseEntity<String> merge(@Valid @RequestBody MergeRequest request) {
        return stored(service.merge(request));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
