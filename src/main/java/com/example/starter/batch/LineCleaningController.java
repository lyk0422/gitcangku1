package com.example.starter.batch;

import com.example.starter.batch.dto.CleaningConfirmRequest;
import com.example.starter.batch.dto.CleaningResponse;
import com.example.starter.batch.dto.CleaningSubmitRequest;
import com.example.starter.batch.dto.LineRunRequest;
import com.example.starter.batch.dto.LineRunResponse;
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
 * 生产线加工顺序与批间清洗验证接口。
 */
@RestController
@RequestMapping("/api")
public class LineCleaningController {

    private final LineCleaningService service;

    public LineCleaningController(LineCleaningService service) {
        this.service = service;
    }

    /**
     * 登记批次上机：顺序从 1 连续递增；X-Actor-Id 为登记操作人。
     */
    @PostMapping("/lines/{lineCode}/runs")
    public ResponseEntity<String> registerRun(@PathVariable String lineCode,
                                              @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                              @Valid @RequestBody LineRunRequest request) {
        return stored(service.registerRun(lineCode, actorId, request));
    }

    /**
     * 查询生产线加工顺序。
     */
    @GetMapping("/lines/{lineCode}/runs")
    public List<LineRunResponse> listRuns(@PathVariable String lineCode) {
        return service.listRuns(lineCode);
    }

    /**
     * 提交两批之间的清洗记录；X-Actor-Id 为提交人。
     */
    @PostMapping("/cleanings")
    public ResponseEntity<String> submitCleaning(@RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                                 @Valid @RequestBody CleaningSubmitRequest request) {
        return stored(service.submitCleaning(actorId, request));
    }

    /**
     * 查询清洗记录。
     */
    @GetMapping("/cleanings/{cleaningId}")
    public CleaningResponse getCleaning(@PathVariable long cleaningId) {
        return service.getCleaning(cleaningId);
    }

    /**
     * 确认清洗：X-Actor-Id 为确认人（不得同提交人），X-Approval-Role 须为 QUALITY。
     */
    @PostMapping("/cleanings/{cleaningId}/confirm")
    public ResponseEntity<String> confirmCleaning(@PathVariable long cleaningId,
                                                  @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                                  @RequestHeader(name = "X-Approval-Role", required = false) String role,
                                                  @Valid @RequestBody CleaningConfirmRequest request) {
        return stored(service.confirmCleaning(cleaningId, actorId, role, request));
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}