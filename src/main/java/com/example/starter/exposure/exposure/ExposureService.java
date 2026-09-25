package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ExposureDecisionResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;

import java.time.LocalDate;

/**
 * 公告曝光频控业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /**
     * 曝光申请联合裁决：先按最新访客抑制名单判定，命中返回 SUPPRESSED
     * （不创建预占、不扣频次或预算）；否则执行既有预占、两级频控与预算裁决。
     */
    ExposureDecisionResponse decide(ApplyExposureRequest request);

    /**
     * 兼容入口：等价于 {@link #decide}，仅在裁决为 RESERVED 时返回预占单。
     */
    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);
}
