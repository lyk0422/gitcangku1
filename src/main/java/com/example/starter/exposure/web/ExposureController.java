package com.example.starter.exposure.web;

import com.example.starter.exposure.exposure.ExposureService;

import java.util.List;
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

    /** 创建公告（额度创建时固定）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 申请曝光：创建 60 秒有效预占并占用两级额度。 */
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

    /** 创建活动：按 UTC 自然日配置总确认额度与 24 个小时预算。 */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> createActivity(
            @Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createActivity(request));
    }

    /** 活动明细（含 24 小时预算与配置版本）。 */
    @GetMapping("/activities/{activityId}")
    public ActivityResponse getActivity(@PathVariable String activityId) {
        return exposureService.getActivity(activityId);
    }

    /** 修改活动配置：携带 expectedVersion，已开始小时不可下调。 */
    @PutMapping("/activities/{activityId}")
    public ActivityResponse updateActivity(@PathVariable String activityId,
                                           @Valid @RequestBody UpdateActivityRequest request) {
        return exposureService.updateActivity(activityId, request);
    }

    /** 公告活动迁移：只影响迁移后的新预占；activityId 为 null 表示移出活动。 */
    @PostMapping("/campaigns/{campaignId}/activity")
    public CampaignDetailResponse assignCampaignActivity(
            @PathVariable String campaignId,
            @Valid @RequestBody AssignActivityRequest request) {
        return exposureService.assignCampaignActivity(campaignId, request);
    }

    /** 公告明细（含活动归属）。 */
    @GetMapping("/campaigns/{campaignId}")
    public CampaignDetailResponse getCampaign(@PathVariable String campaignId) {
        return exposureService.getCampaign(campaignId);
    }

    /** 公告预占历史（只读）：按创建时刻与编号升序；utcDate 缺省查询全部。 */
    @GetMapping("/campaigns/{campaignId}/reservations")
    public List<ReservationResponse> listReservations(
            @PathVariable String campaignId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.listReservations(campaignId, utcDate);
    }

    /** 活动小时账目诊断（只读）：24 小时预算与占用明细；utcDate 缺省为当前 UTC 日。 */
    @GetMapping("/activities/{activityId}/hours")
    public ActivityHoursResponse queryActivityHours(
            @PathVariable String activityId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryActivityHours(activityId, utcDate);
    }

    /** 活动日结快照（只读）：固化每小时确认数与取消数；utcDate 缺省为当前 UTC 日。 */
    @GetMapping("/activities/{activityId}/daily-settlement")
    public DailySettlementResponse queryDailySettlement(
            @PathVariable String activityId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryDailySettlement(activityId, utcDate);
    }
}
