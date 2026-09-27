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

    /** 创建公告（额度创建时固定；归因窗口缺省 1440 分钟，可配置 1～1440）。 */
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

    /** 调整归因窗口（1～1440 分钟）：版本号 +1，历史归因不重算。 */
    @PostMapping("/campaigns/{campaignId}/window")
    public CampaignResponse updateWindow(@PathVariable String campaignId,
                                         @Valid @RequestBody UpdateWindowRequest request) {
        return exposureService.updateWindow(campaignId, request);
    }

    /** 归因窗口版本历史（只读，按版本号升序）。 */
    @GetMapping("/campaigns/{campaignId}/window-history")
    public List<WindowVersionResponse> listWindowHistory(@PathVariable String campaignId) {
        return exposureService.listWindowHistory(campaignId);
    }

    /** 归因诊断统计（只读）；utcDate 缺省统计全部回执。 */
    @GetMapping("/campaigns/{campaignId}/attribution")
    public AttributionDiagnosticsResponse attributionDiagnostics(
            @PathVariable String campaignId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate utcDate) {
        return exposureService.attributionDiagnostics(campaignId, utcDate);
    }

    /** 提交单笔曝光确认回执：窗口内计入归因并结算，窗口外记录 LATE_RECEIPT。 */
    @PostMapping("/reservations/{reservationId}/receipts")
    public ResponseEntity<ReceiptResponse> submitReceipt(@PathVariable String reservationId,
                                                         @Valid @RequestBody ReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exposureService.submitReceipt(reservationId, request));
    }

    /** 某预占单的回执历史（只读，按提交顺序升序）。 */
    @GetMapping("/reservations/{reservationId}/receipts")
    public List<ReceiptResponse> listReceipts(@PathVariable String reservationId) {
        return exposureService.listReceipts(reservationId);
    }

    /** 回执明细（只读）。 */
    @GetMapping("/receipts/{receiptId}")
    public ReceiptResponse getReceipt(@PathVariable String receiptId) {
        return exposureService.getReceipt(receiptId);
    }

    /** 批量提交回执：整批核对后一次事务提交，任一失败整批不结算。 */
    @PostMapping("/receipts/batch")
    public ResponseEntity<BatchReceiptResponse> submitBatchReceipts(
            @Valid @RequestBody BatchReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exposureService.submitBatchReceipts(request));
    }
}
