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
 * 公告曝光频控与活动匀速投放 API。所有时间按 UTC，小时区间左闭右开。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建活动：日总额与 24 个 UTC 小时预算，预算合计必须等于日总额。 */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createActivity(request));
    }

    /** 修改活动配置：携带 expectedVersion，已开始小时不可下调。 */
    @PutMapping("/activities/{activityId}")
    public ActivityResponse updateActivity(@PathVariable String activityId,
                                           @Valid @RequestBody UpdateActivityRequest request) {
        return exposureService.updateActivity(activityId, request);
    }

    /** 活动配置明细（只读）。 */
    @GetMapping("/activities/{activityId}")
    public ActivityResponse getActivity(@PathVariable String activityId) {
        return exposureService.getActivity(activityId);
    }

    /** 创建公告（额度创建时固定；可同时归入已有活动）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 公告活动迁移：只影响迁移后的新预占，既有预占固化活动与小时归属。 */
    @PostMapping("/campaigns/{campaignId}/migration")
    public CampaignResponse migrateCampaign(@PathVariable String campaignId,
                                            @Valid @RequestBody MigrateCampaignRequest request) {
        return exposureService.migrateCampaign(campaignId, request);
    }

    /** 申请曝光：创建 60 秒有效预占，原子占用活动当前小时、公告、访客三侧名额。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 预占明细（只读）。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return exposureService.getReservation(reservationId);
    }

    /** 确认预占：必须在到期时刻之前；同时结算公告、访客、活动三侧名额。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable String reservationId,
                                       @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消，只释放尚未确认的同一小时预占及公告/访客额度。 */
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

    /**
     * 活动日结：往日首次查询固化每小时确认数与取消数快照，后续配置变更不改写已结算日；
     * 查询当日返回实时账目不固化。utcDate 缺省为当前 UTC 日。
     */
    @GetMapping("/activities/{activityId}/daily-settlement")
    public DailySettlementResponse dailySettlement(
            @PathVariable String activityId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.dailySettlement(activityId, utcDate);
    }

    /** 活动某日 24 小时投放诊断（预算、占用、确认/取消/过期、剩余差额），只读。 */
    @GetMapping("/activities/{activityId}/day")
    public ActivityDayResponse activityDay(
            @PathVariable String activityId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.activityDay(activityId, utcDate);
    }

    /** 公告预占历史，按创建时刻倒序（只读）；可按 UTC 日与状态过滤。 */
    @GetMapping("/campaigns/{campaignId}/reservations")
    public List<ReservationResponse> listReservations(
            @PathVariable String campaignId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        return exposureService.listReservations(campaignId, utcDate, status,
                limit == null ? 100 : limit);
    }
}
