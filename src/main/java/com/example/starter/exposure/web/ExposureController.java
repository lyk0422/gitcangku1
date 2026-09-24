package com.example.starter.exposure.web;

import com.example.starter.exposure.exposure.ExposureService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 公告曝光频控 API（含访客静默时段与抑制统计）。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（额度与类别创建时固定）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /**
     * 申请曝光：先判定静默（SUPPRESSED 时 200 返回抑制结果与静默结束 UTC 时刻，不占额度），
     * 通过则创建 60 秒有效预占（201）并占用两级额度；额度已满 429。
     */
    @PostMapping("/reservations")
    public ResponseEntity<ApplyResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        ApplyResponse response = exposureService.apply(request);
        HttpStatus status = switch (response.outcome()) {
            case RESERVED -> HttpStatus.CREATED;
            case SUPPRESSED -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(response);
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

    /** 登记（expectedVersion=0）或修改访客静默时段；版本冲突 409。 */
    @PutMapping("/visitors/{visitorId}/quiet-hours")
    public QuietHoursSettingsResponse saveQuietHours(@PathVariable String visitorId,
                                                     @Valid @RequestBody QuietHoursSettingsRequest request) {
        if (!visitorId.equals(request.visitorId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "visitorId in path and body must match");
        }
        return exposureService.saveQuietHours(request);
    }

    /** 查询访客静默设置；未登记 404（未登记视为无静默）。 */
    @GetMapping("/visitors/{visitorId}/quiet-hours")
    public QuietHoursSettingsResponse getQuietHours(@PathVariable String visitorId) {
        return exposureService.getQuietHours(visitorId);
    }

    /** 按公告、访客与 UTC 日查询累计静默抑制次数；utcDate 缺省为当前 UTC 日。 */
    @GetMapping("/campaigns/{campaignId}/suppressions")
    public SuppressionStatsResponse querySuppressionStats(
            @PathVariable String campaignId,
            @RequestParam String visitorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.querySuppressionStats(campaignId, visitorId, utcDate);
    }
}
