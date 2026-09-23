package com.example.starter.batch;

import com.example.starter.batch.dto.ClosureEntryResponse;
import com.example.starter.batch.dto.DispositionCancelRequest;
import com.example.starter.batch.dto.DispositionConfirmRequest;
import com.example.starter.batch.dto.DispositionRejectRequest;
import com.example.starter.batch.dto.DispositionResponse;
import com.example.starter.batch.dto.DispositionSubmitRequest;
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
 * 召回血缘闭包分区处置接口：提交（质量负责人）、确认/拒绝（不同的生产负责人）、
 * 确认前取消（提交人），以及处置单、闭包只读查询。
 */
@RestController
@RequestMapping("/api/dispositions")
public class DispositionController {

    private final DispositionService service;

    public DispositionController(DispositionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> submit(@RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @Valid @RequestBody DispositionSubmitRequest request) {
        return stored(service.submit(actorId, request));
    }

    @PostMapping("/{dispositionKey}/confirm")
    public ResponseEntity<String> confirm(@PathVariable String dispositionKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                          @Valid @RequestBody DispositionConfirmRequest request) {
        return stored(service.confirm(dispositionKey, actorId, request));
    }

    @PostMapping("/{dispositionKey}/reject")
    public ResponseEntity<String> reject(@PathVariable String dispositionKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @Valid @RequestBody DispositionRejectRequest request) {
        return stored(service.reject(dispositionKey, actorId, request));
    }

    @PostMapping("/{dispositionKey}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String dispositionKey,
                                         @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
                                         @Valid @RequestBody DispositionCancelRequest request) {
        return stored(service.cancel(dispositionKey, actorId, request));
    }

    @GetMapping("/{dispositionKey}")
    public DispositionResponse get(@PathVariable String dispositionKey) {
        return service.getDisposition(dispositionKey);
    }

    @GetMapping("/ancestors/{ancestorKey}/closure")
    public List<ClosureEntryResponse> closure(@PathVariable String ancestorKey) {
        return service.closure(ancestorKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
