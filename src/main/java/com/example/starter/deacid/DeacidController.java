package com.example.starter.deacid;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 脱酸业务链接口：档案登记、批次入组、处理读数、质检封存、归还与查询。
 */
@RestController
@RequestMapping("/api")
@Validated
public class DeacidController {

    private final DeacidService service;

    public DeacidController(DeacidService service) {
        this.service = service;
    }

    public record RegisterPageRequest(@NotBlank String collectionNo) {
    }

    public record CreateBatchRequest(@NotBlank String batchNo) {
    }

    public record EnrollRequest(@NotBlank String requestId, @NotBlank String collectionNo) {
    }

    public record TreatmentRequest(@NotNull BigDecimal phBefore, @NotNull BigDecimal phAfter,
                                   @NotBlank String reagentBatchNo) {
    }

    public record SealRequest(@NotBlank String requestId) {
    }

    /** 登记档案页，馆藏号唯一。 */
    @PostMapping("/pages")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerPage(@RequestBody @Validated RegisterPageRequest request) {
        long id = service.registerPage(request.collectionNo());
        return Map.of("pageId", id, "collectionNo", request.collectionNo());
    }

    /** 创建脱酸批次，初始状态 IN_PROGRESS。 */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBatch(@RequestBody @Validated CreateBatchRequest request) {
        long id = service.createBatch(request.batchNo());
        return Map.of("batchId", id, "batchNo", request.batchNo());
    }

    /** 档案页入组，requestId 幂等：同参重放原状态，异参 409。 */
    @PostMapping("/batches/{batchId}/pages")
    public EnrollResult enroll(@PathVariable long batchId, @RequestBody @Validated EnrollRequest request) {
        return service.enroll(batchId, request.requestId(), request.collectionNo());
    }

    /** 追加一轮处理读数，旧轮次只读。 */
    @PostMapping("/batches/{batchId}/pages/{pageId}/treatments")
    @ResponseStatus(HttpStatus.CREATED)
    public TreatmentRoundView addTreatment(@PathVariable long batchId, @PathVariable long pageId,
                                           @RequestBody @Validated TreatmentRequest request) {
        return service.addTreatment(batchId, pageId, request.phBefore(), request.phAfter(),
                request.reagentBatchNo());
    }

    /** 质检封存：整批校验，任一页不合格则整批退回（422）并列出问题页。 */
    @PostMapping("/batches/{batchId}/seal")
    public ResponseEntity<SealResult> seal(@PathVariable long batchId,
                                           @RequestBody @Validated SealRequest request) {
        SealResult result = service.seal(batchId, request.requestId());
        if ("REJECTED".equals(result.outcome())) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result);
        }
        return ResponseEntity.ok(result);
    }

    /** 归还档案页：从进行中批次移除并释放处理占用；已封存批次只读。 */
    @DeleteMapping("/batches/{batchId}/pages/{pageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releasePage(@PathVariable long batchId, @PathVariable long pageId) {
        service.releasePage(batchId, pageId);
    }

    /** 查询档案页全部处理轮次：批次状态、pH 前后值、药剂来源。 */
    @GetMapping("/pages/{collectionNo}/treatments")
    public List<TreatmentRoundView> pageTreatments(@PathVariable String collectionNo) {
        return service.pageTreatments(collectionNo);
    }

    /** 查询批次状态与成员。 */
    @GetMapping("/batches/{batchId}")
    public BatchView batchView(@PathVariable long batchId) {
        return service.batchView(batchId);
    }
}