package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.GroupQuotaResponse;
import com.example.starter.exposure.web.IdentityGroupResponse;
import com.example.starter.exposure.web.MergeIdentityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;

import java.time.LocalDate;

/**
 * 公告曝光频控业务服务（骨架）。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 显式身份归并：将两个编号（及其所在组）并为一组，共享每访客每日上限。 */
    IdentityGroupResponse mergeIdentities(MergeIdentityRequest request);

    /** 查询访客所属归并组；未入组返回 404。 */
    IdentityGroupResponse getIdentityGroup(String visitorId);

    /** 组维度额度查询：组成员、合计及原编号分账。 */
    GroupQuotaResponse queryGroupQuota(String campaignId, String visitorId, LocalDate utcDate);
}
