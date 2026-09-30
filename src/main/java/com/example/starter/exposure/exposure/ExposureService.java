package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDayQuotaResponse;
import com.example.starter.exposure.web.ActivityDaySettlementResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateActivityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpsertActivityDayConfigRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控业务服务：公告两级额度 + 活动 UTC 日匀速投放。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    ActivityResponse createActivity(CreateActivityRequest request);

    ActivityResponse getActivity(String activityId);

    ActivityDayConfigResponse upsertDayConfig(String activityId, LocalDate utcDate,
                                              UpsertActivityDayConfigRequest request);

    ActivityDayConfigResponse getDayConfig(String activityId, LocalDate utcDate);

    ActivityDayQuotaResponse queryActivityDayQuota(String activityId, LocalDate utcDate);

    ActivityDaySettlementResponse queryDaySettlement(String activityId, LocalDate utcDate);

    CampaignResponse migrateCampaignActivity(String campaignId, MigrateActivityRequest request);

    List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate);
}
