package com.example.starter.exposure.web;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.exposure.SuppressionService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 公告曝光频控与访客抑制名单 API。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;
    private final SuppressionService suppressionService;

    public ExposureController(ExposureService exposureService,
                              SuppressionService suppressionService) {
        this.exposureService = exposureService;
        this.suppressionService = suppressionService;
    }

    /** 创建公告（额度创建时固定）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /**
     * 曝光申请联合裁决：先查访客抑制名单，命中返回 200 + outcome=SUPPRESSED
     * （不创建预占、不扣频次或预算）；否则 201 创建预占并占用两级额度。
     */
    @PostMapping("/decisions")
    public ResponseEntity<ExposureDecisionResponse> decide(@Valid @RequestBody ApplyExposureRequest request) {
        ExposureDecisionResponse decision = exposureService.decide(request);
        if (ExposureDecisionResponse.OUTCOME_SUPPRESSED.equals(decision.outcome())) {
            return ResponseEntity.status(HttpStatus.OK).body(decision);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(decision);
    }

    /** 申请曝光（兼容入口）：创建 60 秒有效预占并占用两级额度。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 预占明细。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return exposureService.getReservation(reservationId);
    }

    /** 确认预占：必须在到期时刻之前。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable String reservationId,
                                       @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消并释放两级额度。 */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable String reservationId,
                                      @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.cancel(reservationId, request);
    }

    /**
     * 按公告/访客/UTC 日查询额度；visitorId 缺省仅返回公告当日总额度。
     * utcDate 缺省使用服务端当前 UTC 日。
     */
    @GetMapping("/campaigns/{campaignId}/quota")
    public QuotaResponse queryQuota(@PathVariable String campaignId,
                                    @RequestParam(required = false) String visitorId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryQuota(campaignId, visitorId, utcDate);
    }

    /** 创建单条访客抑制区间；同一访客重叠区间 409，起止非法 422。 */
    @PostMapping("/campaigns/{campaignId}/suppressions")
    public ResponseEntity<SuppressionIntervalResponse> createSuppression(
            @PathVariable String campaignId,
            @Valid @RequestBody CreateSuppressionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(suppressionService.createInterval(campaignId, request));
    }

    /**
     * 携带 expectedVersion 的名单批量原子更新：任一区间重叠或起止非法整批 422，
     * 版本不匹配 409。
     */
    @PostMapping("/campaigns/{campaignId}/suppressions/batch")
    public SuppressionBatchUpdateResponse batchUpdateSuppression(
            @PathVariable String campaignId,
            @Valid @RequestBody SuppressionBatchUpdateRequest request) {
        return suppressionService.batchUpdate(campaignId, request);
    }

    /** 查询访客抑制状态与被抑制原因；atUtc 缺省取服务端当前时刻。 */
    @GetMapping("/campaigns/{campaignId}/suppressions/status")
    public SuppressionStatusResponse querySuppressionStatus(
            @PathVariable String campaignId,
            @RequestParam String visitorId,
            @RequestParam(required = false) Long atUtc) {
        return suppressionService.queryStatus(campaignId, visitorId, atUtc);
    }

    /** 查询访客抑制区间历史（含 DELETED 快照）与不可变删除记录。 */
    @GetMapping("/campaigns/{campaignId}/suppressions/history")
    public SuppressionHistoryResponse querySuppressionHistory(
            @PathVariable String campaignId,
            @RequestParam String visitorId) {
        return suppressionService.queryHistory(campaignId, visitorId);
    }
}
