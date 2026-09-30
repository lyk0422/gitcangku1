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
 * 公告曝光频控与活动匀速投放 API。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建活动：24 个小时预算均为非负整数且合计等于日总额。 */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createActivity(request));
    }

    /** 修改活动配置：携带 expectedVersion，已开始小时不可下调。 */
    @PostMapping("/activities/{activityId}/update")
    public ActivityResponse updateActivity(@PathVariable String activityId,
                                           @Valid @RequestBody UpdateActivityRequest request) {
        return exposureService.updateActivity(activityId, request);
    }

    /** 活动明细（只读）。 */
    @GetMapping("/activities/{activityId}")
    public ActivityResponse getActivity(@PathVariable String activityId) {
        return exposureService.getActivity(activityId);
    }

    /**
     * 活动 24 小时匀速投放诊断（只读）。utcDate 缺省使用服务端当前 UTC 日。
     */
    @GetMapping("/activities/{activityId}/hours")
    public HourDiagnosticResponse hourDiagnostic(
            @PathVariable String activityId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryHourDiagnostic(activityId, utcDate);
    }

    /**
     * 活动日结（只读触发固化）：固化每小时确认数与取消数快照；
     * 已固化日不改写，未终结日返回 frozen=false 的实时值。
     */
    @GetMapping("/activities/{activityId}/daily-snapshot")
    public DailySnapshotResponse dailySnapshot(
            @PathVariable String activityId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryDailySnapshot(activityId, utcDate);
    }

    /** 创建公告（额度创建时固定；可在创建时归入活动）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 公告明细（只读，含当前归属活动）。 */
    @GetMapping("/campaigns/{campaignId}")
    public CampaignResponse getCampaign(@PathVariable String campaignId) {
        return exposureService.getCampaign(campaignId);
    }

    /**
     * 公告活动迁移：只影响迁移之后的新预占，既有预占固化活动与小时归属。
     * 请求体 activityId 为 null/空表示移出活动。
     */
    @PostMapping("/campaigns/{campaignId}/migrate")
    public CampaignResponse migrateCampaign(@PathVariable String campaignId,
                                            @Valid @RequestBody MigrateCampaignRequest request) {
        return exposureService.migrateCampaign(campaignId, request);
    }

    /** 申请曝光：创建 60 秒有效预占并原子占用公告、访客与活动当小时三侧名额。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 预占明细。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return exposureService.getReservation(reservationId);
    }

    /**
     * 预占历史（只读）。UTC 日区间左闭右开 [fromUtc, toUtc)，按创建时刻、预占单编号稳定排序。
     */
    @GetMapping("/reservations")
    public ReservationHistoryResponse history(
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String visitorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromUtc,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toUtc,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        return exposureService.queryHistory(campaignId, visitorId, fromUtc, toUtc, limit);
    }

    /** 确认预占：必须在到期时刻之前；同时结算公告、访客与活动三侧名额。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable String reservationId,
                                       @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消，释放尚未确认的同一小时预占的三侧名额。 */
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
}
