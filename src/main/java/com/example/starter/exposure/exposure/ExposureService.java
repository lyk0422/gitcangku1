package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePoolRequest;
import com.example.starter.exposure.web.PoolResponse;
import com.example.starter.exposure.web.PoolSelectionResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SelectFromPoolRequest;

import java.time.LocalDate;

/**
 * 公告曝光频控业务服务：两级 UTC 日额度预占/确认/取消，以及缺额轮转选择池。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    PoolResponse createPool(CreatePoolRequest request);

    PoolSelectionResponse selectFromPool(String poolId, SelectFromPoolRequest request);

    PoolResponse getPool(String poolId);
}
