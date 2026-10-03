package com.example.starter.deacid;

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
 * 档案脱酸业务链接口：登记、批次、入组、读数、封存、归还与查询。
 */
@RestController
@RequestMapping("/api")
public class DeacidController {

    private final DeacidService service;

    public DeacidController(DeacidService service) {
        this.service = service;
    }

    /** 登记档案页。 */
    @PostMapping("/pages")
    public ResponseEntity<Contracts.PageResponse> registerPage(
            @Valid @RequestBody Contracts.CreatePageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerPage(request.collectionNo()));
    }

    /** 创建脱酸批次。 */
    @PostMapping("/batches")
    public ResponseEntity<Contracts.BatchResponse> createBatch(
            @Valid @RequestBody Contracts.CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBatch(request.batchNo()));
    }

    /** 档案页入组（幂等）。 */
    @PostMapping("/batches/{batchId}/pages")
    public Contracts.EnrollResponse enroll(@PathVariable long batchId,
                                           @Valid @RequestBody Contracts.EnrollRequest request) {
        return service.enroll(batchId, request.requestId(), request.collectionNo());
    }

    /** 追加处理轮次读数。 */
    @PostMapping("/batches/{batchId}/pages/{pageId}/treatments")
    public ResponseEntity<Contracts.TreatmentResponse> appendTreatment(
            @PathVariable long batchId, @PathVariable long pageId,
            @Valid @RequestBody Contracts.TreatmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.appendTreatment(batchId, pageId, request));
    }

    /** 归还档案页并释放处理占用。 */
    @PostMapping("/batches/{batchId}/pages/{pageId}/release")
    public Contracts.ReleaseResponse release(@PathVariable long batchId, @PathVariable long pageId) {
        return service.release(batchId, pageId);
    }

    /** 封存批次（幂等），质检退回返回 422 及问题页清单。 */
    @PostMapping("/batches/{batchId}/seal")
    public ResponseEntity<?> seal(@PathVariable long batchId,
                                  @Valid @RequestBody Contracts.SealRequest request) {
        DeacidService.SealOutcome outcome = service.seal(batchId, request.requestId());
        if (outcome instanceof DeacidService.SealOutcome.Sealed sealed) {
            return ResponseEntity.ok(sealed.response());
        }
        DeacidService.SealOutcome.Rejected rejected = (DeacidService.SealOutcome.Rejected) outcome;
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new Contracts.SealRejectedBody("SEAL_REJECTED", "质检退回：批内存在问题页",
                        rejected.problems(), rejected.replayed()));
    }

    /** 查询档案页处理轮次。 */
    @GetMapping("/pages/{collectionNo}/treatments")
    public Contracts.PageTreatmentsResponse pageTreatments(@PathVariable String collectionNo) {
        return service.pageTreatments(collectionNo);
    }
}