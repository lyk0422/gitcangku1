package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CooldownStatusResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DecayRecordResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCooldownRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /**
     * 修改公告冷却分钟数；expectedVersion 与当前版本不一致返回 409，只影响后续申请。
     */
    CampaignResponse updateCooldown(String campaignId, UpdateCooldownRequest request);

    /**
     * 查询访客对公告的冷却状态（冷却配置、最近确认时刻、冷却结束时刻、是否在冷却期）。
     */
    CooldownStatusResponse queryCooldownStatus(String campaignId, String visitorId);

    /**
     * 查询访客对公告在指定 UTC 日的衰减权重明细；utcDate 为 null 时使用服务端当前 UTC 日。
     */
    List<DecayRecordResponse> queryDecayRecords(String campaignId, String visitorId, LocalDate utcDate);
}
