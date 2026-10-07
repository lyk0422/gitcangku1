package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyBundleRequest;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BundleResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;

import java.time.LocalDate;

/**
 * 公告曝光频控业务服务（骨架）。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    BundleResponse applyBundle(ApplyBundleRequest request);

    BundleResponse confirmBundle(String groupId, ReservationActionRequest request);

    BundleResponse cancelBundle(String groupId, ReservationActionRequest request);

    BundleResponse getBundle(String groupId);
}
