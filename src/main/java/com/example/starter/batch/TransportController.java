package com.example.starter.batch;

import com.example.starter.batch.dto.ExcursionClosureView;
import com.example.starter.batch.dto.RegisterTransportSegmentRequest;
import com.example.starter.batch.dto.ReleaseTemperatureHoldRequest;
import com.example.starter.batch.dto.TemperatureHoldStatusView;
import com.example.starter.batch.dto.TransportSegmentView;
import com.example.starter.batch.dto.UploadReadingRequest;
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

import java.util.List;

/**
 * 批次运输温控接口：运输段登记、温度读数上传、温控冻结解除与相关查询。
 */
@RestController
@RequestMapping("/api/batches")
public class TransportController {

    private final TransportService service;

    public TransportController(TransportService service) {
        this.service = service;
    }

    /**
     * 登记运输段；X-Actor-Id 为运输录入人。段左闭右开且同批次不得重叠。
     */
    @PostMapping("/{batchKey}/transport-segments")
    public ResponseEntity<String> registerSegment(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody RegisterTransportSegmentRequest request) {
        return stored(service.registerSegment(batchKey, actorId, request));
    }

    /**
     * 上传温度读数：必须落在段内且采集时刻严格递增；越界或间隔超 30 分钟触发 EXCURSION。
     */
    @PostMapping("/{batchKey}/transport-segments/{segmentKey}/readings")
    public ResponseEntity<String> uploadReading(@PathVariable String batchKey,
                                                @PathVariable String segmentKey,
                                                @Valid @RequestBody UploadReadingRequest request) {
        return stored(service.uploadReading(batchKey, segmentKey, request));
    }

    /**
     * 解除温控冻结：质量角色（X-Approval-Role: QUALITY）且不同于任一运输录入人，
     * 同一事务内逐段处置全部 EXCURSION 段。
     */
    @PostMapping("/{batchKey}/temperature-hold/release")
    public ResponseEntity<String> releaseHold(
            @PathVariable String batchKey,
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @RequestHeader(name = "X-Approval-Role", required = false) String role,
            @Valid @RequestBody ReleaseTemperatureHoldRequest request) {
        return stored(service.releaseHold(batchKey, actorId, role, request));
    }

    /**
     * 运输段查询：段定义、状态与全部读数。
     */
    @GetMapping("/{batchKey}/transport-segments")
    public List<TransportSegmentView> listSegments(@PathVariable String batchKey) {
        return service.listSegments(batchKey);
    }

    /**
     * 异常闭包查询：EXCURSION 段异常原因、明细与逐段处置结果。
     */
    @GetMapping("/{batchKey}/temperature-excursions")
    public List<ExcursionClosureView> listExcursions(@PathVariable String batchKey) {
        return service.listExcursions(batchKey);
    }

    /**
     * 温控冻结状态查询。
     */
    @GetMapping("/{batchKey}/temperature-hold")
    public TemperatureHoldStatusView holdStatus(@PathVariable String batchKey) {
        return service.holdStatus(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
