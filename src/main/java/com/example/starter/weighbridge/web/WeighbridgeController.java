package com.example.starter.weighbridge.web;

import com.example.starter.weighbridge.domain.Phase;
import com.example.starter.weighbridge.model.Views.AdjudicationView;
import com.example.starter.weighbridge.model.Views.DisputeView;
import com.example.starter.weighbridge.model.Views.ErrataView;
import com.example.starter.weighbridge.model.Views.FreezeView;
import com.example.starter.weighbridge.model.Views.OrderOverview;
import com.example.starter.weighbridge.model.Views.OrderView;
import com.example.starter.weighbridge.model.Views.WeighingView;
import com.example.starter.weighbridge.service.WeighbridgeService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地磅争议结算 REST 入口：称量、复称、裁决、冻结与查询。
 */
@RestController
@RequestMapping("/api")
public class WeighbridgeController {

    private final WeighbridgeService service;

    public WeighbridgeController(WeighbridgeService service) {
        this.service = service;
    }

    /** 创建运输单请求。 */
    public record CreateOrderRequest(@NotBlank String orderNo, @NotBlank String vehicleNo) {
    }

    /** 称量请求：phase 为 ENTRY_TARE/EXIT_GROSS，weightKg 单位公斤。 */
    public record WeighingRequest(@NotNull Phase phase, @Positive int weightKg,
                                  @NotNull LocalDateTime weighedAt, @NotBlank String requestId) {
    }

    /** 复称申请请求。 */
    public record ReweighApplyRequest(@NotBlank String reason, @NotBlank String requestId) {
    }

    /** 复称称量请求。 */
    public record ReweighWeighingRequest(@Positive int weightKg, @NotNull LocalDateTime weighedAt,
                                         @NotBlank String requestId) {
    }

    /** 裁决请求：adoptedWeighingId 必须为原毛重或本争议复称之一。 */
    public record AdjudicateRequest(@NotNull Long adoptedWeighingId, @NotBlank String reason,
                                    @NotBlank String decidedBy, @NotBlank String requestId) {
    }

    /** 冻结请求。 */
    public record FreezeRequest(@NotBlank String requestId) {
    }

    @PostMapping("/transport-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView createOrder(@Validated @RequestBody CreateOrderRequest req) {
        return service.createOrder(req.orderNo(), req.vehicleNo());
    }

    @PostMapping("/transport-orders/{orderId}/weighings")
    @ResponseStatus(HttpStatus.CREATED)
    public WeighingView recordWeighing(@PathVariable long orderId,
                                       @Validated @RequestBody WeighingRequest req) {
        return service.recordWeighing(orderId, req.phase(), req.weightKg(), req.weighedAt(),
                req.requestId());
    }

    @PostMapping("/transport-orders/{orderId}/reweigh-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public DisputeView applyReweigh(@PathVariable long orderId,
                                    @Validated @RequestBody ReweighApplyRequest req) {
        return service.applyReweigh(orderId, req.reason(), req.requestId());
    }

    /**
     * 追加复称毛重；净重已冻结时转为勘误申请并返回 202。
     */
    @PostMapping("/disputes/{disputeId}/weighings")
    public ResponseEntity<Object> submitReweighWeighing(
            @PathVariable long disputeId,
            @Validated @RequestBody ReweighWeighingRequest req) {
        Object result = service.submitReweighWeighing(disputeId, req.weightKg(), req.weighedAt(),
                req.requestId());
        HttpStatus status = result instanceof ErrataView ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping("/disputes/{disputeId}/adjudications")
    @ResponseStatus(HttpStatus.CREATED)
    public AdjudicationView adjudicate(@PathVariable long disputeId,
                                       @Validated @RequestBody AdjudicateRequest req) {
        return service.adjudicate(disputeId, req.adoptedWeighingId(), req.reason(),
                req.decidedBy(), req.requestId());
    }

    @PostMapping("/transport-orders/{orderId}/net-weight-freeze")
    @ResponseStatus(HttpStatus.CREATED)
    public FreezeView freeze(@PathVariable long orderId,
                             @Validated @RequestBody FreezeRequest req) {
        return service.freeze(orderId, req.requestId());
    }

    @GetMapping("/transport-orders/{orderId}/overview")
    public OrderOverview overview(@PathVariable long orderId) {
        return service.overview(orderId);
    }

    @GetMapping("/transport-orders/{orderId}/weighings")
    public List<WeighingView> listWeighings(@PathVariable long orderId) {
        return service.listWeighings(orderId);
    }

    @GetMapping("/transport-orders/{orderId}/disputes")
    public List<DisputeView> listDisputes(@PathVariable long orderId) {
        return service.listDisputes(orderId);
    }
}