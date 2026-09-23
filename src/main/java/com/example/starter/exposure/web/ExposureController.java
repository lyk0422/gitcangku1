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
import java.util.List;

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

    /** 创建公告（额度创建时固定，并自动创建 DEFAULT 展示位）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 新增展示位（创建后不可修改、不可删除），携带 expectedConfigVersion。 */
    @PostMapping("/campaigns/{campaignId}/placements")
    public ResponseEntity<PlacementResponse> createPlacement(@PathVariable String campaignId,
                                                             @Valid @RequestBody CreatePlacementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exposureService.createPlacement(campaignId, request));
    }

    /** 列出公告下全部展示位（含 DEFAULT）。 */
    @GetMapping("/campaigns/{campaignId}/placements")
    public List<PlacementResponse> listPlacements(@PathVariable String campaignId) {
        return exposureService.listPlacements(campaignId);
    }

    /** 旧申请入口：等价于在 DEFAULT 展示位申请，创建 60 秒有效预占并占用三层额度。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 按展示位申请曝光：同一事务占用公告/访客/展示位三层额度。 */
    @PostMapping("/placement-reservations")
    public ResponseEntity<ReservationResponse> applyPlacement(
            @Valid @RequestBody ApplyPlacementExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.applyPlacement(request));
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

    /** 取消预占：仅 RESERVED 可取消并释放三层额度。 */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable String reservationId,
                                      @Valid @RequestBody ReservationActionRequest request) {
        return exposureService.cancel(reservationId, request);
    }

    /**
     * 按公告/访客/展示位/UTC 日查询三层额度；visitorId、placementCode 缺省时对应层字段为 null。
     * utcDate 缺省使用服务端当前 UTC 日。旧调用（不传 placementCode）响应保持兼容。
     */
    @GetMapping("/campaigns/{campaignId}/quota")
    public QuotaResponse queryQuota(@PathVariable String campaignId,
                                    @RequestParam(required = false) String visitorId,
                                    @RequestParam(required = false) String placementCode,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryQuota(campaignId, visitorId, placementCode, utcDate);
    }

    /**
     * 三层额度及预占展示位明细查询；维度缺省语义与额度查询一致。
     */
    @GetMapping("/campaigns/{campaignId}/quota/detail")
    public QuotaDetailResponse queryQuotaDetail(@PathVariable String campaignId,
                                                @RequestParam(required = false) String visitorId,
                                                @RequestParam(required = false) String placementCode,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.queryQuotaDetail(campaignId, visitorId, placementCode, utcDate);
    }
}
