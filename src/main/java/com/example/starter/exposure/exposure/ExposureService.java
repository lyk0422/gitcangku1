package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivityBudgetResponse;
import com.example.starter.exposure.web.ActivityDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateActivityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettlementResponse;
import com.example.starter.exposure.web.UpdateActivityBudgetRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控与活动匀速投放业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    CampaignResponse getCampaign(String campaignId);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    ActivityResponse createActivity(CreateActivityRequest request);

    ActivityResponse getActivity(String activityId);

    ActivityBudgetResponse updateBudget(String activityId, UpdateActivityBudgetRequest request);

    ActivityBudgetResponse getBudget(String activityId, LocalDate utcDate);

    CampaignResponse migrateCampaignActivity(String campaignId, MigrateActivityRequest request);

    ActivityDiagnosticsResponse getHourDiagnostics(String activityId, LocalDate utcDate);

    SettlementResponse getSettlement(String activityId, LocalDate utcDate);

    List<ReservationResponse> listActivityReservations(String activityId, LocalDate utcDate,
                                                       Integer hourOfDay);
}
