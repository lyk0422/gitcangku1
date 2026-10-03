package com.example.starter.web;

import com.example.starter.service.BatchQueryService;
import com.example.starter.service.DischargeService;
import com.example.starter.service.IdempotencyService;
import com.example.starter.service.IdempotentResponse;
import com.example.starter.web.dto.ApiRequests.FinalReadingRequest;
import com.example.starter.web.dto.ApiRequests.HandoverRequest;
import com.example.starter.web.dto.ApiRequests.IntakeRequest;
import com.example.starter.web.dto.ApiRequests.IsolateRequest;
import com.example.starter.web.dto.ApiResponses.CreateBatchResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 电芯放电业务入口：入批、终测、作废、隔离、交接与查询。 */
@RestController
@RequestMapping("/api")
public class DischargeController {

    private final DischargeService dischargeService;
    private final BatchQueryService queryService;
    private final IdempotencyService idempotencyService;

    public DischargeController(DischargeService dischargeService,
                               BatchQueryService queryService,
                               IdempotencyService idempotencyService) {
        this.dischargeService = dischargeService;
        this.queryService = queryService;
        this.idempotencyService = idempotencyService;
    }

    /** 创建批次。 */
    @PostMapping("/batches")
    public ResponseEntity<CreateBatchResponse> createBatch() {
        return ResponseEntity.ok(dischargeService.createBatch());
    }

    /** 电芯入批（幂等）。 */
    @PostMapping("/batches/{batchCode}/cells")
    public ResponseEntity<String> intake(@PathVariable String batchCode,
                                         @Valid @RequestBody IntakeRequest request) {
        IdempotentResponse response = idempotencyService.execute(
                request.requestId(), "intake",
                hash(batchCode, request.cellCode(), String.valueOf(request.initialVoltage())),
                () -> dischargeService.intake(request.requestId(), batchCode,
                        request.cellCode(), request.initialVoltage()));
        return toJson(response);
    }

    /** 记录终测读数（幂等）。 */
    @PostMapping("/batches/{batchCode}/readings")
    public ResponseEntity<String> finalReading(@PathVariable String batchCode,
                                               @Valid @RequestBody FinalReadingRequest request) {
        IdempotentResponse response = idempotencyService.execute(
                request.requestId(), "finalReading",
                hash(batchCode, request.cellCode(), String.valueOf(request.voltage()),
                        request.measuredAt().toString()),
                () -> dischargeService.finalReading(request.requestId(), batchCode,
                        request.cellCode(), request.voltage(), request.measuredAt()));
        return toJson(response);
    }

    /** 作废读数。 */
    @PostMapping("/readings/{readingCode}/invalidate")
    public ResponseEntity<Map<String, Object>> invalidate(@PathVariable String readingCode) {
        dischargeService.invalidateReading(readingCode);
        return ResponseEntity.ok(Map.of("readingCode", readingCode, "valid", false));
    }

    /** 隔离异常电芯。 */
    @PostMapping("/batches/{batchCode}/isolate")
    public ResponseEntity<Map<String, Object>> isolate(
            @PathVariable String batchCode,
            @Valid @RequestBody IsolateRequest request) {
        dischargeService.isolate(batchCode, request.cellCode(), request.reason());
        return ResponseEntity.ok(Map.of("batchCode", batchCode, "cellCode", request.cellCode(),
                "status", "ISOLATED"));
    }

    /** 交接签收（幂等）。 */
    @PostMapping("/batches/{batchCode}/handover")
    public ResponseEntity<String> handover(@PathVariable String batchCode,
                                           @Valid @RequestBody HandoverRequest request) {
        IdempotentResponse response = idempotencyService.execute(
                request.requestId(), "handover",
                hash(batchCode, request.fromParty(), request.toParty()),
                () -> dischargeService.handover(request.requestId(), batchCode,
                        request.fromParty(), request.toParty()));
        return toJson(response);
    }

    /** 查询批次：读数链、隔离原因、交接快照。 */
    @GetMapping("/batches/{batchCode}")
    public ResponseEntity<BatchQueryService.BatchView> query(@PathVariable String batchCode) {
        return ResponseEntity.ok(queryService.query(batchCode));
    }

    private static ResponseEntity<String> toJson(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(
                    String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}