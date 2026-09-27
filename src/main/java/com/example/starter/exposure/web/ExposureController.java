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
 * 公告曝光频控与归因窗口 API。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（额度创建时固定，归因窗口 1～1440 分钟）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 调整公告 UTC 归因窗口：追加不可变版本，只对之后的新预占生效。 */
    @PutMapping("/campaigns/{campaignId}/attribution-window")
    public CampaignResponse updateWindow(@PathVariable String campaignId,
                                         @Valid @RequestBody UpdateWindowRequest request) {
        return exposureService.updateWindow(campaignId, request);
    }

    /** 只读：公告归因窗口版本历史（按版本号升序）。 */
    @GetMapping("/campaigns/{campaignId}/window-versions")
    public List<WindowVersionResponse> listWindowVersions(@PathVariable String campaignId) {
        return exposureService.listWindowVersions(campaignId);
    }

    /** 只读：公告归因诊断（状态/结果实际数量与近期回执）。 */
    @GetMapping("/campaigns/{campaignId}/diagnostics")
    public CampaignDiagnosticsResponse diagnostics(@PathVariable String campaignId,
                                                   @RequestParam(defaultValue = "100") int recentLimit) {
        return exposureService.getCampaignDiagnostics(campaignId, recentLimit);
    }

    /** 申请曝光：创建 60 秒有效预占并占用两级额度，快照当前归因窗口版本。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 预占明细（基础视图）。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return exposureService.getReservation(reservationId);
    }

    /** 只读：预占归因明细（窗口区间与回执历史），不触发结算。 */
    @GetMapping("/reservations/{reservationId}/detail")
    public ReservationDetailResponse getReservationDetail(@PathVariable String reservationId) {
        return exposureService.getReservationDetail(reservationId);
    }

    /** 只读：预占回执历史（按受理时刻、流水编号升序）。 */
    @GetMapping("/reservations/{reservationId}/receipts")
    public List<ReceiptItemResult> listReceipts(@PathVariable String reservationId) {
        return exposureService.listReceipts(reservationId);
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
     * 提交批量曝光确认回执：整批一次事务裁决；窗口内计入归因并结算，
     * 窗口外记录 LATE_RECEIPT；任一归属不匹配等结构错误整批 422 不结算。
     */
    @PostMapping("/receipts")
    public ReceiptBatchResponse submitReceipts(@Valid @RequestBody ReceiptBatchRequest request) {
        return exposureService.submitReceipts(request);
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
