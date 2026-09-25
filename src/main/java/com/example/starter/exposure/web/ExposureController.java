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
 * 公告曝光频控 API：公告/访客两级额度与渠道总量频控。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（额度创建时固定，可同时归属一个渠道）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 申请曝光：创建 60 秒有效预占并原子占用渠道、公告、访客名额。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 预占明细：可按公告、渠道、UTC 日任意组合过滤。 */
    @GetMapping("/reservations")
    public List<ReservationResponse> listReservations(@RequestParam(required = false) String campaignId,
                                                      @RequestParam(required = false) String channelKey,
                                                      @RequestParam(required = false)
                                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                      LocalDate utcDate) {
        return exposureService.listReservations(campaignId, channelKey, utcDate);
    }

    /** 预占明细（单条）。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return exposureService.getReservation(reservationId);
    }

    /** 确认预占：必须在到期时刻之前；确认同时结算公告与渠道名额。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable String reservationId,
                                       @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消并同时释放渠道、公告、访客名额。 */
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

    /** 按公告归属统计：以预占创建时固化的渠道分组，不随公告迁移变化。 */
    @GetMapping("/campaigns/{campaignId}/attribution")
    public CampaignAttributionResponse attribution(@PathVariable String campaignId) {
        return exposureService.attributionStats(campaignId);
    }

    /** 公告迁移归属渠道：只影响迁移后的新申请。 */
    @PostMapping("/campaigns/{campaignId}/channel")
    public CampaignResponse migrateChannel(@PathVariable String campaignId,
                                           @Valid @RequestBody MigrateCampaignChannelRequest request) {
        return exposureService.migrateCampaignChannel(campaignId, request);
    }

    /** 创建渠道总量配置。 */
    @PostMapping("/channels")
    public ResponseEntity<ChannelResponse> createChannel(@Valid @RequestBody CreateChannelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createChannel(request));
    }

    /** 修改渠道日额度：携带 expectedVersion，冲突 409，不得下调到低于当前已确认数。 */
    @PutMapping("/channels/{channelKey}/cap")
    public ChannelResponse updateChannelCap(@PathVariable String channelKey,
                                            @Valid @RequestBody UpdateChannelCapRequest request) {
        return exposureService.updateChannelCap(channelKey, request);
    }

    /** 查询渠道日用量；utcDate 缺省使用服务端当前 UTC 日。 */
    @GetMapping("/channels/{channelKey}/usage")
    public ChannelUsageResponse channelUsage(@PathVariable String channelKey,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryChannelUsage(channelKey, utcDate);
    }
}
