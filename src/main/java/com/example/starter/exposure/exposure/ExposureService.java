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
     * 修改公告冷却配置：须携带 expectedVersion，版本冲突 409；只影响后续申请。
     *
     * @param campaignId 路径中的公告编号
     */
    CampaignResponse updateCooldown(String campaignId, UpdateCooldownRequest request);

    /**
     * 查询某访客对某公告的冷却状态（含最近一次 CONFIRMED 确认时刻与冷却结束时刻）。
     */
    CooldownStatusResponse queryCooldown(String campaignId, String visitorId);

    /**
     * 查询某访客某公告某 UTC 日的衰减权重明细，按当日确认次序（N）升序；
     * utcDate 为 null 时使用服务端当前 UTC 日。
     */
    List<DecayRecordResponse> queryDecay(String campaignId, String visitorId, LocalDate utcDate);
}
