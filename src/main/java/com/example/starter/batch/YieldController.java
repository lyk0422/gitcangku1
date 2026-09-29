package com.example.starter.batch;

import com.example.starter.batch.dto.YieldDispositionRequest;
import com.example.starter.batch.dto.YieldHistoryResponse;
import com.example.starter.batch.dto.YieldReconcileRequest;
import com.example.starter.batch.dto.YieldReconcileResponse;
import com.example.starter.batch.dto.YieldReleaseRequest;
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
 * 产率平衡核算接口骨架。
 */
@RestController
@RequestMapping("/api/batches/{batchKey}/yield")
public class YieldController {

    private final YieldService service;

    public YieldController(YieldService service) {
        this.service = service;
    }

    @PostMapping("/reconciliations")
    public ResponseEntity<String> reconcile(@PathVariable String batchKey,
                                            @Valid @RequestBody YieldReconcileRequest request) {
        return stored(service.submitReconciliation(batchKey, request));
    }

    @PostMapping("/dispositions")
    public ResponseEntity<String> disposition(@PathVariable String batchKey,
                                              @RequestHeader(name = "X-Actor-Id", required = false)
                                              String handlerId,
                                              @Valid @RequestBody YieldDispositionRequest request) {
        return stored(service.confirmDisposition(batchKey, handlerId, request));
    }

    @PostMapping("/release")
    public ResponseEntity<String> release(@PathVariable String batchKey,
                                          @RequestHeader(name = "X-Actor-Id", required = false)
                                          String actorId,
                                          @Valid @RequestBody YieldReleaseRequest request) {
        return stored(service.release(batchKey, actorId, request));
    }

    @GetMapping("/current")
    public YieldReconcileResponse current(@PathVariable String batchKey) {
        return service.current(batchKey);
    }

    @GetMapping("/history")
    public YieldHistoryResponse history(@PathVariable String batchKey) {
        return service.history(batchKey);
    }

    private ResponseEntity<String> stored(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response.body());
    }
}
