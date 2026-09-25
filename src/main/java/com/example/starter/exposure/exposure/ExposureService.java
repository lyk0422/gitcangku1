package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchApplyRequest;
import com.example.starter.exposure.web.BatchApplyResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ConsentResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DecisionPreviewResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SubmitConsentRequest;
import com.example.starter.exposure.web.UpdateCategoryRequest;
import com.example.starter.exposure.web.WithdrawConsentRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控与同意联合裁决业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    CampaignResponse updateCategory(String campaignId, UpdateCategoryRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    BatchApplyResponse batchApply(BatchApplyRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 只读裁决预览：按真实申请顺序给出当前时刻的可通过结论或首个拒绝原因，不创建预占、不扣账目。 */
    DecisionPreviewResponse previewDecision(String campaignId, String visitorId);

    ConsentResponse submitConsent(SubmitConsentRequest request);

    ConsentResponse withdrawConsent(String consentId, WithdrawConsentRequest request);

    List<ConsentResponse> queryConsents(String visitorId, String category);
}
