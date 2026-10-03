package com.example.starter.web;

import com.example.starter.domain.DeadlineBasis;
import com.example.starter.domain.DispenseRecord;
import com.example.starter.domain.ReagentBottle;
import com.example.starter.service.ReagentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试剂瓶接口：接收、开封、幂等领用、批号召回与按瓶查询。
 */
@RestController
@RequestMapping("/api")
public class ReagentController {

    private final ReagentService service;

    public ReagentController(ReagentService service) {
        this.service = service;
    }

    /** 接收登记请求：批号、容量 mL（0.1 精度）、供应商到期 UTC 时刻。 */
    public record ReceiveRequest(
            @NotBlank @Size(max = 64) String batchNo,
            @NotNull BigDecimal capacityMl,
            @NotNull Instant supplierExpiryAt) {
    }

    /** 领用请求：幂等键与领用量 mL（0.1 精度）。 */
    public record DispenseRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotNull BigDecimal amountMl) {
    }

    /** 召回请求：批号与原因。 */
    public record RecallRequest(
            @NotBlank @Size(max = 64) String batchNo,
            @NotBlank @Size(max = 255) String reason) {
    }

    /** 瓶视图：状态、剩余量、实际截止时刻与到期来源、隔离信息。 */
    public record BottleView(
            long id, String batchNo, BigDecimal capacityMl, BigDecimal remainingMl,
            Instant supplierExpiryAt, Instant openedAt, String status,
            Instant effectiveDeadline, DeadlineBasis expirySource,
            String quarantineReason, Instant quarantinedAt, Instant createdAt) {

        static BottleView of(ReagentBottle b) {
            return new BottleView(b.id(), b.batchNo(), b.capacityMl(), b.remainingMl(),
                    b.supplierExpiryAt(), b.openedAt(), b.status().name(),
                    b.effectiveDeadline(), b.deadlineBasis(),
                    b.quarantineReason(), b.quarantinedAt(), b.createdAt());
        }
    }

    /** 领用记录视图：固化当时剩余量与截止依据。 */
    public record DispenseView(
            long id, long bottleId, String requestId, BigDecimal amountMl,
            BigDecimal remainingAfterMl, DeadlineBasis deadlineBasis, Instant dispensedAt,
            boolean replayed) {

        static DispenseView of(ReagentService.DispenseOutcome outcome) {
            DispenseRecord r = outcome.record();
            return new DispenseView(r.id(), r.bottleId(), r.requestId(), r.amountMl(),
                    r.remainingAfterMl(), r.deadlineBasis(), r.dispensedAt(), outcome.replayed());
        }
    }

    /** 瓶详情视图：瓶状态加全部成功领用流水。 */
    public record BottleDetailView(BottleView bottle, List<DispenseView> dispenses) {
    }

    /** 召回结果视图：召回ID与本次被隔离的瓶ID列表。 */
    public record RecallView(long recallId, String batchNo, String reason,
                             Instant recalledAt, List<Long> quarantinedBottleIds) {
    }

    /** 接收登记新瓶。 */
    @PostMapping("/bottles")
    public ResponseEntity<BottleView> receive(@Valid @RequestBody ReceiveRequest request) {
        ReagentBottle bottle = service.receive(request.batchNo(), request.capacityMl(),
                request.supplierExpiryAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(BottleView.of(bottle));
    }

    /** 首次开封，固定开封时刻。 */
    @PostMapping("/bottles/{id}/open")
    public BottleView open(@PathVariable long id) {
        return BottleView.of(service.open(id));
    }

    /** 幂等领用：同参重放返回首次记录（200），新请求成功返回 201，异参 409。 */
    @PostMapping("/bottles/{id}/dispenses")
    public ResponseEntity<DispenseView> dispense(@PathVariable long id,
                                                 @Valid @RequestBody DispenseRequest request) {
        ReagentService.DispenseOutcome outcome =
                service.dispense(id, request.requestId(), request.amountMl());
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(DispenseView.of(outcome));
    }

    /** 批号召回：隔离仍有余量的瓶。 */
    @PostMapping("/recalls")
    public RecallView recall(@Valid @RequestBody RecallRequest request) {
        ReagentService.RecallOutcome outcome = service.recall(request.batchNo(), request.reason());
        return new RecallView(outcome.recallId(), outcome.batchNo(), outcome.reason(),
                outcome.recalledAt(), outcome.quarantinedBottleIds());
    }

    /** 按瓶查询到期来源、剩余量、领用流水与隔离原因。 */
    @GetMapping("/bottles/{id}")
    public BottleDetailView detail(@PathVariable long id) {
        ReagentService.BottleDetail detail = service.detail(id);
        List<DispenseView> records = detail.dispenses().stream()
                .map(r -> new DispenseView(r.id(), r.bottleId(), r.requestId(), r.amountMl(),
                        r.remainingAfterMl(), r.deadlineBasis(), r.dispensedAt(), false))
                .toList();
        return new BottleDetailView(BottleView.of(detail.bottle()), records);
    }
}