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
 * 公告曝光频控 API。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（类别与额度创建时固定）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /**
     * 申请曝光：先判定访客静默。静默时段内 SERVICE/MARKETING（及 allowCritical=false 时的
     * CRITICAL）返回 200 + SUPPRESSED 并携带静默结束 UTC 时刻，不预占、不占额度；
     * 否则创建 60 秒有效预占并占用两级额度（201）。
     */
    @PostMapping("/reservations")
    public ResponseEntity<ApplyResultResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        ApplyResultResponse result = exposureService.apply(request);
        HttpStatus status = switch (result.outcome()) {
            case RESERVED -> HttpStatus.CREATED;
            case SUPPRESSED -> HttpStatus.OK;
        };
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

    /** 登记或修改访客静默设置；版本冲突返回 409，只影响后续申请。 */
    @PutMapping("/visitors/{visitorId}/quiet-settings")
    public QuietSettingsResponse putQuietSettings(@PathVariable String visitorId,
                                                  @Valid @RequestBody QuietSettingsRequest request) {
        if (!visitorId.equals(request.visitorId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "visitorId in path does not match request body");
        }
        return exposureService.putQuietSettings(request);
    }

    /** 查询访客静默设置；访客未登记返回 404（视为无静默）。 */
    @GetMapping("/visitors/{visitorId}/quiet-settings")
    public QuietSettingsResponse getQuietSettings(@PathVariable String visitorId) {
        return exposureService.getQuietSettings(visitorId);
    }

    /**
     * 查询某公告对某访客某 UTC 日（缺省当前 UTC 日）的静默抑制累计次数。
     */
    @GetMapping("/campaigns/{campaignId}/suppressions")
    public SuppressionStatsResponse querySuppressionStats(
            @PathVariable String campaignId,
            @RequestParam String visitorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.querySuppressionStats(campaignId, visitorId, utcDate);
    }
}
