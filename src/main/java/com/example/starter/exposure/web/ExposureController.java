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
 * 公告曝光频控与活动匀速投放 API。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建活动（按 UTC 自然日配置匀速投放预算的载体）。 */
    @PostMapping("/activities")
    public ResponseEntity<ActivityResponse> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createActivity(request));
    }

    /** 创建公告（公告日总额度与访客日上限创建时固定）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 新建活动某 UTC 日 24 小时预算配置（不带 expectedVersion）。 */
    @PostMapping("/activities/{activityId}/day-configs/{utcDate}")
    public ResponseEntity<ActivityDayConfigResponse> createActivityDayConfig(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate,
            @Valid @RequestBody ActivityDayConfigRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exposureService.configureActivityDay(activityId, utcDate, request));
    }

    /** 修改活动某 UTC 日配置（携带 expectedVersion；已开始小时不可下调）。 */
    @PutMapping("/activities/{activityId}/day-configs/{utcDate}")
    public ActivityDayConfigResponse updateActivityDayConfig(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate,
            @Valid @RequestBody ActivityDayConfigRequest request) {
        return exposureService.configureActivityDay(activityId, utcDate, request);
    }

    /** 查询活动某日配置（只读）。 */
    @GetMapping("/activities/{activityId}/day-configs/{utcDate}")
    public ActivityDayConfigResponse getActivityDayConfig(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.getActivityDayConfig(activityId, utcDate);
    }

    /**
     * 公告活动迁移：只影响迁移后的新预占，既有预占固化活动与小时归属；
     * 请求体 activityId 为 null 表示移出活动。
     */
    @PostMapping("/campaigns/{campaignId}/activity")
    public CampaignResponse assignCampaignActivity(@PathVariable String campaignId,
                                                   @Valid @RequestBody AssignCampaignActivityRequest request) {
        return exposureService.assignCampaignActivity(campaignId, request);
    }

    /** 申请曝光：原子预占活动当前小时名额与公告、访客两级额度，创建 60 秒有效预占。 */
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
     * 预占历史（只读）：可按活动/公告/访客与申请时刻闭开区间过滤，
     * 区间 epoch 毫秒、UTC、左闭右开；按申请时刻降序返回。
     */
    @GetMapping("/reservations")
    public ReservationHistoryResponse findReservations(
            @RequestParam(required = false) String activityId,
            @RequestParam(required = false) String campaignId,
            @RequestParam(required = false) String visitorId,
            @RequestParam(required = false) Long fromCreatedAtUtc,
            @RequestParam(required = false) Long toCreatedAtUtc,
            @RequestParam(required = false) Integer limit) {
        return exposureService.findReservations(activityId, campaignId, visitorId,
                fromCreatedAtUtc, toCreatedAtUtc, limit);
    }

    /** 确认预占：必须在到期时刻之前；同时结算公告、访客、活动三侧名额。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable String reservationId,
                                       @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消，只释放该单固化的同一小时三侧名额。 */
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

    /** 活动某日 24 小时名额诊断（只读）；utcDate 缺省为当前 UTC 日。 */
    @GetMapping("/activities/{activityId}/diagnostics/{utcDate}")
    public ActivityDayDiagnosticsResponse activityDayDiagnostics(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.activityDayDiagnostics(activityId, utcDate);
    }

    /**
     * 活动日日结（只读语义、首次固化快照）：固化每小时确认数与取消数，
     * 已结算小时不随后续配置变更改写。
     */
    @PostMapping("/activities/{activityId}/settlements/{utcDate}")
    public ActivityDaySettlementResponse settleActivityDay(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.settleActivityDay(activityId, utcDate);
    }

    /** 读取活动日日结结果；若尚未固化则先固化再返回。 */
    @GetMapping("/activities/{activityId}/settlements/{utcDate}")
    public ActivityDaySettlementResponse getActivityDaySettlement(
            @PathVariable String activityId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.settleActivityDay(activityId, utcDate);
    }
}
