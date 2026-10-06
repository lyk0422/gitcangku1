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

    /**
     * 显式身份归并：将两个合成访客编号所在的组合并为一组，组关系全局共享。
     * 已在一组时不新增成员；归并后申请按组内所有编号的 UTC 日已确认与有效预占
     * 合计执行原每访客上限。
     */
    IdentityGroupResponse mergeIdentities(MergeIdentityRequest request);

    /**
     * 归并组额度查询：给出组成员、组内合计及原编号分账。
     * 访客未归并返回 404，公告不存在返回 404。
     */
    GroupQuotaResponse queryGroupQuota(String visitorId, String campaignId, LocalDate utcDate);
}
