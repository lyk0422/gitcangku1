package com.example.starter.web;

import com.example.starter.domain.ConfirmationResult;
import com.example.starter.domain.Inflorescence;
import com.example.starter.domain.PollenBatch;
import com.example.starter.domain.PollinationConfirmation;
import com.example.starter.domain.PollinationOperation;
import com.example.starter.service.ApiException;
import com.example.starter.service.PollinationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 温室授粉窗口管理接口。所有时刻均为 UTC。
 */
@RestController
@RequestMapping("/api")
public class PollinationController {

    private final PollinationService service;

    public PollinationController(PollinationService service) {
        this.service = service;
    }

    /** 登记花序。 */
    @PostMapping("/inflorescences")
    public ResponseEntity<Inflorescence> createInflorescence(
            @Valid @RequestBody ApiRequests.CreateInflorescenceRequest request) {
        Inflorescence created = service.createInflorescence(
                request.code(), request.firstOpenAt(), request.windowHours());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 摘除花序（幂等）。 */
    @PostMapping("/inflorescences/{id}/removal")
    public Inflorescence removeInflorescence(@PathVariable long id,
                                             @Valid @RequestBody ApiRequests.RemoveInflorescenceRequest request) {
        return service.removeInflorescence(id, request.removedAt());
    }

    /** 查询花序时间线：窗口、花粉来源、操作及结果。 */
    @GetMapping("/inflorescences/{id}/timeline")
    public TimelineResponse timeline(@PathVariable long id) {
        return TimelineResponse.from(service.timeline(id));
    }

    /** 新建花粉批号。 */
    @PostMapping("/pollen-batches")
    public ResponseEntity<PollenBatch> createPollenBatch(
            @Valid @RequestBody ApiRequests.CreatePollenBatchRequest request) {
        PollenBatch created = service.createPollenBatch(request.batchNo());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 停用花粉批号。 */
    @PostMapping("/pollen-batches/{batchNo}/deactivation")
    public PollenBatch disablePollenBatch(@PathVariable String batchNo) {
        return service.disablePollenBatch(batchNo);
    }

    /** 登记授粉操作（幂等键 requestId）。 */
    @PostMapping("/inflorescences/{id}/pollinations")
    public ResponseEntity<PollinationOperation> pollinate(@PathVariable long id,
                                                          @Valid @RequestBody ApiRequests.PollinateRequest request) {
        PollinationOperation op = service.pollinate(id, request.requestId(), request.pollenBatchNo(),
                request.technician(), request.executedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(op);
    }

    /** 撤销尚未确认结果的授粉操作。 */
    @PostMapping("/pollinations/{operationId}/revoke")
    public PollinationOperation revoke(@PathVariable long operationId,
                                       @Valid @RequestBody ApiRequests.RevokeRequest request) {
        return service.revoke(operationId, request.revokedAt());
    }

    /** 登记授粉结果确认（幂等键 requestId）。 */
    @PostMapping("/pollinations/{operationId}/confirmation")
    public ResponseEntity<PollinationConfirmation> confirm(@PathVariable long operationId,
                                                           @Valid @RequestBody ApiRequests.ConfirmRequest request) {
        ConfirmationResult result = parseResult(request.result());
        PollinationConfirmation conf = service.confirm(operationId, request.requestId(), result,
                request.technician(), request.confirmedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(conf);
    }

    private static ConfirmationResult parseResult(String result) {
        try {
            return ConfirmationResult.valueOf(result);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("RESULT_INVALID", "结果取值须为 SUCCESS 或 FAILED: " + result);
        }
    }
}