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
import java.util.List;

/**
 * 公告曝光频控 API：公告两级额度 + 活动 UTC 日匀速投放。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（额度创建时固定，可归入活动）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 申请曝光：创建 60 秒有效预占并占用公告/访客两级额度；归入活动时同时预占当前小时活动名额。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 预占明细。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return exposureService.getReservation(reservationId);
    }

    /** 确认预占：必须在到期时刻之前，同时结算公告、访客和活动三侧名额。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable String reservationId,
                                       @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消并释放三侧额度，活动侧只释放同一小时预占。 */
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

    /** 公告某 UTC 日的预占历史（只读），按创建时刻、编号升序。 */
    @GetMapping("/campaigns/{campaignId}/reservations")
    public List<ReservationResponse> listReservations(@PathVariable String campaignId,
                                                      @RequestParam(required = false)
                                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                      LocalDate utcDate) {
        return exposureService.listReservations(campaignId, utcDate);
    }

    /** 公告活动迁移：只影响迁移后的新预占，既有预占固化原归属。 */
    @PostMapping("/campaigns/{campaignId}/activity")
    public CampaignResponse migrateCampaignActivity(@PathVariable String campaignId,
                                                    @Valid @RequestBody MigrateActivityRequest request) {
        return exposureService.migrateCampaignActivity(campaignId, request);
    }

    /** 创建活动。 */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createActivity(request));
    }

    /** 活动明细（只读）。 */
    @GetMapping("/activities/{activityId}")
    public ActivityResponse getActivity(@PathVariable String activityId) {
        return exposureService.getActivity(activityId);
    }

    /**
     * 配置/修改活动某 UTC 日匀速投放：24 个小时预算均为非负整数且合计等于日总确认额度；
     * 携带 expectedVersion 乐观锁，已开始小时不可下调。
     */
    @PutMapping("/activities/{activityId}/days/{utcDate}/budget")
    public ActivityDayConfigResponse upsertDayConfig(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate,
            @Valid @RequestBody UpsertActivityDayConfigRequest request) {
        return exposureService.upsertDayConfig(activityId, utcDate, request);
    }

    /** 活动某 UTC 日配置明细（只读）。 */
    @GetMapping("/activities/{activityId}/days/{utcDate}/budget")
    public ActivityDayConfigResponse getDayConfig(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.getDayConfig(activityId, utcDate);
    }

    /** 活动某 UTC 日逐该小时预算/占用/剩余诊断（只读）。 */
    @GetMapping("/activities/{activityId}/days/{utcDate}/hours")
    public ActivityDayQuotaResponse queryActivityDayQuota(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryActivityDayQuota(activityId, utcDate);
    }

    /** 活动某 UTC 日结查询：已结束日固化每小时确认/取消快照，后续配置变更不改写（只读）。 */
    @GetMapping("/activities/{activityId}/days/{utcDate}/settlement")
    public ActivityDaySettlementResponse queryDaySettlement(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryDaySettlement(activityId, utcDate);
    }
}
