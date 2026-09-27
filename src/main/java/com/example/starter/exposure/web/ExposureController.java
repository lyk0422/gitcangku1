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
 * 公告曝光频控与活动匀速投放 API。所有时间统一 UTC，日期区间左闭右开。
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

    /** 公告明细（含当前活动归属）。 */
    @GetMapping("/campaigns/{campaignId}")
    public CampaignResponse getCampaign(@PathVariable String campaignId) {
        return exposureService.getCampaign(campaignId);
    }

    /** 公告活动迁移：只影响迁移后的新预占；activityId 为 null 表示迁出活动。 */
    @PostMapping("/campaigns/{campaignId}/activity")
    public CampaignResponse migrateActivity(@PathVariable String campaignId,
                                            @Valid @RequestBody MigrateActivityRequest request) {
        return exposureService.migrateCampaignActivity(campaignId, request);
    }

    /** 申请曝光：创建 60 秒有效预占并占用活动小时名额（如已归入活动）与两级额度。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 预占明细。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return exposureService.getReservation(reservationId);
    }

    /** 确认预占：必须在到期时刻之前；同时结算活动、公告、访客三侧名额。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable String reservationId,
                                       @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消并释放三侧额度。 */
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

    /** 创建活动。 */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createActivity(request));
    }

    /** 活动明细（含当前配置版本号）。 */
    @GetMapping("/activities/{activityId}")
    public ActivityResponse getActivity(@PathVariable String activityId) {
        return exposureService.getActivity(activityId);
    }

    /** 配置活动某 UTC 日预算：24 个小时预算合计须等于日总额，携带 expectedVersion 乐观锁。 */
    @PutMapping("/activities/{activityId}/budget")
    public ActivityBudgetResponse updateBudget(@PathVariable String activityId,
                                               @Valid @RequestBody UpdateActivityBudgetRequest request) {
        return exposureService.updateBudget(activityId, request);
    }

    /** 查询活动某 UTC 日预算配置。 */
    @GetMapping("/activities/{activityId}/budget")
    public ActivityBudgetResponse getBudget(@PathVariable String activityId,
                                            @RequestParam
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.getBudget(activityId, utcDate);
    }

    /** 活动某 UTC 日小时账目诊断（纯读取，不改变状态）。 */
    @GetMapping("/activities/{activityId}/hours")
    public ActivityDiagnosticsResponse getHourDiagnostics(@PathVariable String activityId,
                                                          @RequestParam
                                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                          LocalDate utcDate) {
        return exposureService.getHourDiagnostics(activityId, utcDate);
    }

    /** 活动日结查询：日终后首次查询固化每小时确认/取消/过期快照。 */
    @GetMapping("/activities/{activityId}/settlement")
    public SettlementResponse getSettlement(@PathVariable String activityId,
                                            @RequestParam
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.getSettlement(activityId, utcDate);
    }

    /** 活动某 UTC 日（可按小时过滤）的预占历史，按创建时刻与编号稳定升序。 */
    @GetMapping("/activities/{activityId}/reservations")
    public List<ReservationResponse> listActivityReservations(@PathVariable String activityId,
                                                              @RequestParam
                                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                              LocalDate utcDate,
                                                              @RequestParam(required = false)
                                                              Integer hourOfDay) {
        return exposureService.listActivityReservations(activityId, utcDate, hourOfDay);
    }
}
