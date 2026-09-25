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
 * 公告曝光频控与同意联合裁决 API。
 */
@RestController
@RequestMapping("/api/exposure")
public class ExposureController {

    private final ExposureService exposureService;

    public ExposureController(ExposureService exposureService) {
        this.exposureService = exposureService;
    }

    /** 创建公告（额度创建时固定，可选活动类别与 UTC 静默时段）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.createCampaign(request));
    }

    /** 修改活动类别：版本 +1，旧类别同意不迁移。 */
    @PostMapping("/campaigns/{campaignId}/category")
    public CampaignResponse updateCategory(@PathVariable String campaignId,
                                           @Valid @RequestBody UpdateCategoryRequest request) {
        return exposureService.updateCategory(campaignId, request);
    }

    /** 申请曝光：按 同意→静默→频控→预算 裁决，创建 60 秒有效预占并占用两级额度。 */
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.apply(request));
    }

    /** 批量预占：所有访客最终账目预校验，任一失败整批回滚。 */
    @PostMapping("/reservations/batch")
    public ResponseEntity<BatchApplyResponse> batchApply(@Valid @RequestBody BatchApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.batchApply(request));
    }

    /** 预占明细（含创建时固化的同意决定与版本快照）。 */
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

    /** 只读裁决预览（拒绝原因查询）：不创建预占、不扣频次与预算。 */
    @GetMapping("/campaigns/{campaignId}/decision")
    public DecisionPreviewResponse previewDecision(@PathVariable String campaignId,
                                                   @RequestParam String visitorId) {
        return exposureService.previewDecision(campaignId, visitorId);
    }

    /** 提交访客活动类别同意区间（ALLOW/DENY，含版本与左闭右开 UTC 生效区间）。 */
    @PostMapping("/consents")
    public ResponseEntity<ConsentResponse> submitConsent(@Valid @RequestBody SubmitConsentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(exposureService.submitConsent(request));
    }

    /** 撤回同意：生效中区间截断到撤回时刻，只影响之后的预占。 */
    @PostMapping("/consents/{consentId}/withdraw")
    public ConsentResponse withdrawConsent(@PathVariable String consentId,
                                           @Valid @RequestBody WithdrawConsentRequest request) {
        return exposureService.withdrawConsent(consentId, request);
    }

    /** 查询某访客某活动类别的全部同意区间（按生效起点排序）。 */
    @GetMapping("/consents")
    public List<ConsentResponse> queryConsents(@RequestParam String visitorId,
                                               @RequestParam String category) {
        return exposureService.queryConsents(visitorId, category);
    }
}
