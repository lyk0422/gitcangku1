package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResultResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.QuietSettingsRequest;
import com.example.starter.exposure.web.QuietSettingsResponse;
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
     * 申请曝光：静默判定先于额度校验。被静默抑制时返回 SUPPRESSED 并携带静默结束 UTC 时刻，
     * 不创建预占、不占两级额度、不返回 429；否则创建 60 秒有效预占并占用两级额度。
     */
    ApplyResultResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 登记或修改访客静默设置；expectedVersion 与当前版本冲突返回 409，只影响后续申请。 */
    QuietSettingsResponse putQuietSettings(QuietSettingsRequest request);

    /** 查询访客静默设置；访客未登记返回 null（视为无静默）。 */
    QuietSettingsResponse getQuietSettings(String visitorId);

    /** 查询某公告对某访客某 UTC 日（缺省为当前 UTC 日）的抑制累计次数。 */
    SuppressionStatsResponse querySuppressionStats(String campaignId, String visitorId, LocalDate utcDate);
}
