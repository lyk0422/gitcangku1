package com.example.starter.exposure.web;

import com.example.starter.exposure.exposure.ExposureService;
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

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（额度创建时固定）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /**
     * 申请曝光：未命中抑制名单创建 60 秒有效预占（201）；
     * 命中抑制名单返回 SUPPRESSED（200），不创建预占、不扣频次或预算。
     */
    @PostMapping("/reservations")
    public ResponseEntity<ApplyResult> apply(@Valid @RequestBody ApplyExposureRequest request) {
        ApplyResult result = exposureService.apply(request);
        HttpStatus status = result instanceof SuppressedResponse ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
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

    /** 创建单条抑制区间：起止非法 422，同访客重叠 409。 */
    @PostMapping("/campaigns/{campaignId}/suppression-intervals")
    public ResponseEntity<SuppressionIntervalResponse> createSuppressionInterval(
            @PathVariable String campaignId,
            @Valid @RequestBody CreateSuppressionIntervalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exposureService.createSuppressionInterval(campaignId, request));
    }

    /**
     * 批量更新抑制名单：expectedVersion 不一致 409；
     * 任一重叠或起止非法整批 422，原名单不变。
     */
    @PostMapping("/campaigns/{campaignId}/suppression-list:batch-update")
    public SuppressionListResponse batchUpdateSuppressionList(
            @PathVariable String campaignId,
            @Valid @RequestBody BatchUpdateSuppressionListRequest request) {
        return exposureService.batchUpdateSuppressionList(campaignId, request);
    }

    /** 删除未开始的抑制区间：立即失效并保留不可变删除记录。 */
    @PostMapping("/campaigns/{campaignId}/suppression-intervals/{intervalId}/delete")
    public SuppressionIntervalResponse deleteSuppressionInterval(
            @PathVariable String campaignId,
            @PathVariable String intervalId,
            @Valid @RequestBody IdempotentRequest request) {
        return exposureService.deleteSuppressionInterval(campaignId, intervalId, request);
    }

    /** 提前结束已开始的抑制区间：结束时刻不得早于当前时刻。 */
    @PostMapping("/campaigns/{campaignId}/suppression-intervals/{intervalId}/end")
    public SuppressionIntervalResponse endSuppressionInterval(
            @PathVariable String campaignId,
            @PathVariable String intervalId,
            @Valid @RequestBody EndSuppressionIntervalRequest request) {
        return exposureService.endSuppressionInterval(campaignId, intervalId, request);
    }

    /** 查询访客当前抑制状态与被抑制原因。 */
    @GetMapping("/campaigns/{campaignId}/suppression-status")
    public VisitorSuppressionStatusResponse getVisitorSuppressionStatus(
            @PathVariable String campaignId,
            @RequestParam String visitorId) {
        return exposureService.getVisitorSuppressionStatus(campaignId, visitorId);
    }

    /** 查询区间历史（全部状态）；visitorId 缺省返回该公告全部区间。 */
    @GetMapping("/campaigns/{campaignId}/suppression-intervals")
    public SuppressionListResponse listSuppressionIntervals(
            @PathVariable String campaignId,
            @RequestParam(required = false) String visitorId) {
        return exposureService.listSuppressionIntervals(campaignId, visitorId);
    }
}
