package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchApplyRequest;
import com.example.starter.exposure.web.BatchApplyResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ConsentResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.EvaluationResponse;
import com.example.starter.exposure.web.GrantConsentRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCategoryRequest;
import com.example.starter.exposure.web.WithdrawConsentRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控与同意版本联合裁决业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /** 修改活动类别：版本 +1，旧同意不迁移。 */
    CampaignResponse updateCategory(String campaignId, UpdateCategoryRequest request);

    /** 提交访客 ALLOW/DENY 同意版本区间（左闭右开）。 */
    ConsentResponse grantConsent(GrantConsentRequest request);

    /** 撤回同意：只影响撤回之后的预占。 */
    ConsentResponse withdrawConsent(String consentId, WithdrawConsentRequest request);

    /** 查询某 (访客, 类别) 的全部同意区间（含撤回/被覆盖记录）。 */
    List<ConsentResponse> queryConsents(String visitorId, String category);

    ReservationResponse apply(ApplyExposureRequest request);

    /** 批量预占：按所有访客最终账目预校验，任一失败整批回滚。 */
    BatchApplyResponse batchApply(BatchApplyRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 只读预校验请求时刻的裁决结果与拒绝原因，不创建预占、不扣额度。 */
    EvaluationResponse evaluate(String campaignId, String visitorId);
}
