package com.example.starter.batch;

import com.example.starter.batch.dto.DispositionRequest;
import com.example.starter.batch.dto.ReadingRequest;
import com.example.starter.batch.dto.RegisterSegmentRequest;
import com.example.starter.batch.dto.ReleaseHoldRequest;
import com.example.starter.batch.dto.SegmentResponse;
import com.example.starter.batch.dto.TemperatureStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次运输温控接口：运输段登记、温度读数、异常闭包（逐段处置）、冻结解除与状态查询。
 * 运输录入人通过 X-Actor-Id 提供；处置与解除另需 X-Approval-Role=QUALITY。
 */
@RestController
@RequestMapping("/api/batches/{batchKey}")
public class TransportController {

    private final TransportService service;

    public TransportController(TransportService service) {
        this.service = service;
    }

    /**
     * 登记运输段：提交起止 UTC 时刻与允许温度上下限；同批次段间左闭右开不得重叠。
     */
    @PostMapping("/transport-segments")
    public ResponseEntity<String> registerSegment(@PathVariable String batchKey,
                                                  @RequestHeader(name = "X-Actor-Id", required = false)
                                                  String actorId,
                                                  @Valid @RequestBody RegisterSegmentRequest request) {
        return stored(service.registerSegment(batchKey, actorId, request));
    }

    /**
     * 上传温度读数：时刻须落在段内且严格递增；越界或间隔超 30 分钟立即使段异常并冻结批次。
     */
    @PostMapping("/transport-segments/{segmentKey}/readings")
    public ResponseEntity<String> uploadReading(@PathVariable String batchKey,
                                                @PathVariable String segmentKey,
                                                @Valid @RequestBody ReadingRequest request) {
        return stored(service.uploadReading(batchKey, segmentKey, request));
    }

    /**
     * 异常段逐段处置闭包：仅 QUALITY 角色、非该段录入人可提交，每段至多一次不可改写。
     */
    @PostMapping("/transport-segments/{segmentKey}/disposition")
    public ResponseEntity<String> dispose(@PathVariable String batchKey,
                                          @PathVariable String segmentKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody DispositionRequest request) {
        return stored(service.disposeExcursion(batchKey, segmentKey, actorId, role, request));
    }

    /**
     * 解除温控冻结：QUALITY 角色且不同于任一运输段录入人，所有 EXCURSION 段均已逐段处置方可解除。
     */
    @PostMapping("/temperature-release")
    public ResponseEntity<String> release(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody ReleaseHoldRequest request) {
        return stored(service.releaseHold(batchKey, actorId, role, request));
    }

    /**
     * 温控冻结状态查询：门禁标记、全部运输段与读数、异常闭包及解除记录。
     */
    @GetMapping("/temperature")
    public TemperatureStatusResponse temperature(@PathVariable String batchKey) {
        return service.temperatureStatus(batchKey);
    }

    /**
     * 单个运输段查询：含读数历史与（异常段的）处置闭包。
     */
    @GetMapping("/transport-segments/{segmentKey}")
    public SegmentResponse segment(@PathVariable String batchKey,
                                   @PathVariable String segmentKey) {
        return service.temperatureStatus(batchKey).segments().stream()
                .filter(s -> s.segmentKey().equals(segmentKey))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("运输段不存在: " + segmentKey));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
