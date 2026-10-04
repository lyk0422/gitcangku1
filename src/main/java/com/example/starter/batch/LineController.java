package com.example.starter.batch;

import com.example.starter.batch.dto.CleaningResponse;
import com.example.starter.batch.dto.ConfirmCleaningRequest;
import com.example.starter.batch.dto.LineSequenceResponse;
import com.example.starter.batch.dto.RegisterSequenceRequest;
import com.example.starter.batch.dto.SubmitCleaningRequest;
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
 * 生产线加工顺序与相邻批次清洗验证接口。
 */
@RestController
@RequestMapping("/api")
public class LineController {

    private final LineService service;

    public LineController(LineService service) {
        this.service = service;
    }

    /**
     * 登记批次上机：X-Actor-Id 为登记操作人；expectedVersion 为期望顺序号（从 1 连续递增）。
     */
    @PostMapping("/lines/{lineId}/sequences")
    public ResponseEntity<String> register(@PathVariable String lineId,
                                           @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                           @Valid @RequestBody RegisterSequenceRequest request) {
        return stored(service.register(lineId, actorId, request));
    }

    /**
     * 生产线加工顺序查询：按顺序号升序。
     */
    @GetMapping("/lines/{lineId}/sequences")
    public List<LineSequenceResponse> sequences(@PathVariable String lineId) {
        return service.listSequences(lineId);
    }

    /**
     * 提交清洗记录：X-Actor-Id 为提交人；residualAmount 为十进制字符串（0～100 毫克、最多 6 位小数）。
     */
    @PostMapping("/lines/{lineId}/cleanings")
    public ResponseEntity<String> submitCleaning(@PathVariable String lineId,
                                                 @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                                 @Valid @RequestBody SubmitCleaningRequest request) {
        return stored(service.submitCleaning(lineId, actorId, request));
    }

    /**
     * 确认清洗：X-Actor-Id 为确认人（须不同于提交人），X-Approval-Role 须为 QUALITY。
     */
    @PostMapping("/cleanings/{cleaningId}/confirmations")
    public ResponseEntity<String> confirm(@PathVariable String cleaningId,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                          @Valid @RequestBody ConfirmCleaningRequest request) {
        return stored(service.confirm(cleaningId, actorId, role, request));
    }

    /**
     * 清洗记录查询。
     */
    @GetMapping("/cleanings/{cleaningId}")
    public CleaningResponse cleaning(@PathVariable String cleaningId) {
        return service.getCleaning(cleaningId);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}