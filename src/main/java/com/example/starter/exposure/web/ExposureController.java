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
 * 公告曝光频控与活动匀速投放 API。
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

    /** 申请曝光：创建 60 秒有效预占并占用额度；归入活动的公告另须预占当前小时活动名额。 */
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

    /** 取消预占：仅 RESERVED 可取消并释放额度。 */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable String reservationId,
                                      @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.cancel(reservationId, request);
    }

    /**
     * 按公告+访客/UTC 日查询额度；visitorId 缺省仅返回公告当日总额度。
     * utcDate 缺省使用服务端当前 UTC 日。
     */
    @GetMapping("/campaigns/{campaignId}/quota")
    public QuotaResponse queryQuota(@PathVariable String campaignId,
                                    @RequestParam(required = false) String visitorId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryQuota(campaignId, visitorId, utcDate);
    }

    /** 公告预占明细查询：按 UTC 日，可按状态过滤，只读。 */
    @GetMapping("/campaigns/{campaignId}/reservations")
    public List<ReservationResponse> listReservations(@PathVariable String campaignId,
                                                      @RequestParam
                                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                      LocalDate utcDate,
                                                      @RequestParam(required = false) String status) {
        return exposureService.listReservations(campaignId, utcDate, status);
    }

    /** 创建活动。 */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createActivity(request));
    }

    /** 配置活动某 UTC 日预算：24 个小时预算合计等于日总额，携带 expectedVersion。 */
    @PutMapping("/activities/{activityId}/days/{utcDate}/budget")
    public ActivityDayConfigResponse configureDayBudget(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate,
            @Valid @RequestBody ConfigureDayBudgetRequest request) {
        return exposureService.configureDayBudget(activityId, utcDate, request);
    }

    /** 公告活动迁移：只影响迁移后的新预占。 */
    @PostMapping("/campaigns/{campaignId}/activity")
    public CampaignActivityResponse migrateCampaign(@PathVariable String campaignId,
                                                    @Valid @RequestBody MigrateCampaignRequest request) {
        return exposureService.migrateCampaign(campaignId, request);
    }

    /** 活动日结查询：固化每小时确认数与取消数快照，只读。 */
    @GetMapping("/activities/{activityId}/days/{utcDate}/close")
    public ActivityDayCloseResponse queryDayClose(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryDayClose(activityId, utcDate);
    }

    /** 活动小时额度诊断查询：当前占用、剩余与预算对照，只读。 */
    @GetMapping("/activities/{activityId}/days/{utcDate}/diagnostics")
    public ActivityDiagnosticsResponse queryDiagnostics(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryDiagnostics(activityId, utcDate);
    }

    /** 活动配置历史查询：每次成功修改一条快照，只读。 */
    @GetMapping("/activities/{activityId}/days/{utcDate}/config-history")
    public ConfigHistoryResponse queryConfigHistory(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryConfigHistory(activityId, utcDate);
    }
}