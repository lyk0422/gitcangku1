package com.example.starter.observation.web;

import com.example.starter.observation.dto.CreateRequest;
import com.example.starter.observation.dto.DeleteRequest;
import com.example.starter.observation.dto.ObservationResponse;
import com.example.starter.observation.dto.OfflineSubmitRequest;
import com.example.starter.observation.service.ObservationService;
import com.example.starter.observation.service.WriteResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 现场观测离线合并接口：创建、离线提交、删除、当前/历史版本查询。
 */
@RestController
@RequestMapping("/api/observations")
public class ObservationController {

    private final ObservationService observationService;

    public ObservationController(ObservationService observationService) {
        this.observationService = observationService;
    }

    @PostMapping
    public ResponseEntity<ObservationResponse> create(
            @Valid @RequestBody CreateRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        WriteResult result = observationService.create(request, requestId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{observationId}/submit")
    public ResponseEntity<ObservationResponse> offlineSubmit(
            @PathVariable String observationId,
            @Valid @RequestBody OfflineSubmitRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        WriteResult result = observationService.offlineSubmit(observationId, request, requestId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/{observationId}/delete")
    public ResponseEntity<ObservationResponse> delete(
            @PathVariable String observationId,
            @Valid @RequestBody DeleteRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        WriteResult result = observationService.delete(observationId, request, requestId);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @GetMapping("/{observationId}")
    public ObservationResponse getCurrent(@PathVariable String observationId) {
        return observationService.getCurrent(observationId);
    }

    @GetMapping("/{observationId}/versions/{version}")
    public ObservationResponse getVersion(@PathVariable String observationId,
                                          @PathVariable int version) {
        return observationService.getVersion(observationId, version);
    }
}
