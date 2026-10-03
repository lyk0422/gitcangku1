package com.example.starter.web;

import com.example.starter.batch.BatchInfo;
import com.example.starter.batch.BatchService;
import com.example.starter.reading.ReadingService;
import com.example.starter.release.ReleaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次接口：建批、测温上传、异常标记、放行、驳回、复烧与详情查询。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService batchService;
    private final ReadingService readingService;
    private final ReleaseService releaseService;

    public BatchController(BatchService batchService, ReadingService readingService,
                           ReleaseService releaseService) {
        this.batchService = batchService;
        this.readingService = readingService;
        this.releaseService = releaseService;
    }

    /**
     * 新建批次，入炉即锁定程序版本。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BatchInfo create(@Valid @RequestBody CreateBatchRequest request) {
        return batchService.create(request.batchNo(), request.programVersionId(), null);
    }

    /**
     * 发起独立复烧批次，不修改原批次数据。
     */
    @PostMapping("/{id}/refire")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchInfo refire(@PathVariable long id, @Valid @RequestBody RefireRequest request) {
        return batchService.refire(id, request.batchNo(), request.programVersionId());
    }

    /**
     * 上传测温点，requestId 幂等：同参重放返回首次结果，异参 409。
     */
    @PostMapping("/{id}/readings")
    public ResponseEntity<String> uploadReadings(@PathVariable long id,
                                                 @Valid @RequestBody UploadReadingsRequest request) {
        List<ReadingService.ReadingInput> inputs = request.readings().stream()
                .map(r -> new ReadingService.ReadingInput(r.measuredAt(), r.temperatureC()))
                .toList();
        ReadingService.UploadResult result = readingService.upload(id, request.requestId(), inputs);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result.responseBody());
    }

    /**
     * 标记异常，标记后批次只能驳回或复烧。
     */
    @PostMapping("/{id}/anomalies")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> markAnomaly(@PathVariable long id,
                                           @Valid @RequestBody AnomalyRequest request) {
        return releaseService.markAnomaly(id, request.markedBy(), request.reason());
    }

    /**
     * 质检放行：全轨迹校核通过后一次性固化程序与测温点上限。
     */
    @PostMapping("/{id}/release")
    public Map<String, Object> release(@PathVariable long id,
                                       @Valid @RequestBody ReleaseRequest request) {
        return releaseService.release(id, request.releasedBy());
    }

    /**
     * 质检驳回：驳回后不能补录点再直接放行。
     */
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id,
                                      @Valid @RequestBody RejectRequest request) {
        return releaseService.reject(id, request.rejectedBy(), request.reason());
    }

    /**
     * 批次详情：程序版本、各段最坏偏差、异常标记与放行依据。
     */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return batchService.detail(id);
    }


    public record CreateBatchRequest(@NotBlank String batchNo, @NotNull Long programVersionId) {
    }

    public record RefireRequest(@NotBlank String batchNo, Long programVersionId) {
    }

    public record UploadReadingsRequest(@NotBlank String requestId,
                                        @NotEmpty List<@Valid ReadingDto> readings) {
    }

    public record ReadingDto(@NotNull Instant measuredAt, @NotNull BigDecimal temperatureC) {
    }

    public record AnomalyRequest(@NotBlank String markedBy, @NotBlank String reason) {
    }

    public record ReleaseRequest(@NotBlank String releasedBy) {
    }

    public record RejectRequest(@NotBlank String rejectedBy, @NotBlank String reason) {
    }
}