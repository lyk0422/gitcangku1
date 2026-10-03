package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivityDayCloseResponse;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignActivityResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ConfigHistoryResponse;
import com.example.starter.exposure.web.ConfigureDayBudgetRequest;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;

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

    ActivityDayConfigResponse configureDayBudget(String activityId, LocalDate utcDate,
                                                 ConfigureDayBudgetRequest request);

    CampaignActivityResponse migrateCampaign(String campaignId, MigrateCampaignRequest request);

    ActivityDayCloseResponse queryDayClose(String activityId, LocalDate utcDate);

    ActivityDiagnosticsResponse queryDiagnostics(String activityId, LocalDate utcDate);

    ConfigHistoryResponse queryConfigHistory(String activityId, LocalDate utcDate);

    List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate, String status);
}