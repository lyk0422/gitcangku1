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
 * 公告曝光频控与同意版本联合裁决 API。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（额度创建时固定，可携带类别/静默窗口/冷却频控配置）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 修改公告活动类别：活动版本 +1，旧同意不迁移。 */
    @PostMapping("/campaigns/{campaignId}/category")
    public CampaignResponse updateCategory(@PathVariable String campaignId,
                                           @Valid @RequestBody UpdateCategoryRequest request) {
        return exposureService.updateCategory(campaignId, request);
    }

    /** 提交访客同意（ALLOW/DENY 版本区间，左闭右开）。 */
    @PostMapping("/consents")
    public ResponseEntity<ConsentResponse> grantConsent(@Valid @RequestBody GrantConsentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.grantConsent(request));
    }

    /** 查询某 (访客, 类别) 的全部同意区间（含撤回/被覆盖记录）。 */
    @GetMapping("/consents")
    public List<ConsentResponse> queryConsents(@RequestParam String visitorId,
                                               @RequestParam String category) {
        return exposureService.queryConsents(visitorId, category);
    }

    /** 撤回同意：只影响撤回之后的预占。 */
    @PostMapping("/consents/{consentId}/withdraw")
    public ConsentResponse withdrawConsent(@PathVariable String consentId,
                                           @Valid @RequestBody WithdrawConsentRequest request) {
        return exposureService.withdrawConsent(consentId, request);
    }

    /** 申请曝光：先过同意/静默/频控/预算，再创建 60 秒有效预占并占用两级额度。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 批量预占：任一访客最终账目预校验失败则整批回滚。 */
    @PostMapping("/reservations/batch")
    public ResponseEntity<BatchApplyResponse> batchApply(@Valid @RequestBody BatchApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.batchApply(request));
    }

    /** 只读预校验请求时刻的裁决结果与拒绝原因，不创建预占、不扣额度。 */
    @GetMapping("/campaigns/{campaignId}/evaluation")
    public EvaluationResponse evaluate(@PathVariable String campaignId,
                                       @RequestParam String visitorId) {
        return exposureService.evaluate(campaignId, visitorId);
    }

    /** 预占明细（含创建时固化的同意快照）。 */
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
}
