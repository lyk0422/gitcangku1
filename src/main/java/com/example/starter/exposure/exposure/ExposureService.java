package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivityHourDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailyCloseResponse;
import com.example.starter.exposure.web.MigrateCampaignActivityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控与活动匀速投放业务服务。
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

    CampaignResponse migrateCampaign(String campaignId, MigrateCampaignActivityRequest request);

    ActivityResponse getActivity(String activityId);

    DailyCloseResponse getDailyClose(String activityId, LocalDate utcDate);

    ActivityHourDiagnosticsResponse getHourDiagnostics(String activityId, LocalDate utcDate);

    List<ReservationResponse> getActivityReservations(String activityId, LocalDate utcDate);
}
