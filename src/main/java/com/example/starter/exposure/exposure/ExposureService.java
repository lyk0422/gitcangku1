package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuietHoursSettingsRequest;
import com.example.starter.exposure.web.QuietHoursSettingsResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionStatsResponse;

import java.time.LocalDate;

/**
 * 公告曝光频控业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /**
     * 申请曝光：先判定访客静默（SUPPRESSED 不创建预占、不占额度），再校验两级额度。
     */
    ApplyResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 首次登记或修改访客静默时段；修改须带 expectedVersion，版本冲突 409。 */
    QuietHoursSettingsResponse saveQuietHours(QuietHoursSettingsRequest request);

    /** 查询访客静默设置；未登记 404（未登记视为无静默）。 */
    QuietHoursSettingsResponse getQuietHours(String visitorId);

    /** 按公告、访客与 UTC 日查询累计抑制次数。 */
    SuppressionStatsResponse querySuppressionStats(String campaignId, String visitorId, LocalDate utcDate);
}
