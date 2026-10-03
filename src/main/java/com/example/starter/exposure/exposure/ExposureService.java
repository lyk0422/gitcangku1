package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivityHoursResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AssignActivityRequest;
import com.example.starter.exposure.web.CampaignDetailResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySettlementResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控业务服务：公告/访客两级额度 + 活动匀速投放（小时预算）管控。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    ActivityResponse createActivity(CreateActivityRequest request);

    ActivityResponse updateActivity(String activityId, UpdateActivityRequest request);

    ActivityResponse getActivity(String activityId);

    CampaignDetailResponse assignCampaignActivity(String campaignId, AssignActivityRequest request);

    CampaignDetailResponse getCampaign(String campaignId);

    List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate);

    ActivityHoursResponse queryActivityHours(String activityId, LocalDate utcDate);

    DailySettlementResponse queryDailySettlement(String activityId, LocalDate utcDate);
}
