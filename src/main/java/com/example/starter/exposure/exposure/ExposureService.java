package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ChannelConfigResponse;
import com.example.starter.exposure.web.ChannelReservationResponse;
import com.example.starter.exposure.web.ChannelStatsResponse;
import com.example.starter.exposure.web.ChannelUsageResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.MigrateChannelRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpsertChannelConfigRequest;

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

    /** 创建或修改渠道日总量频控配置；修改须携带 expectedVersion，冲突 409。 */
    ChannelConfigResponse upsertChannelConfig(String channelKey, UpsertChannelConfigRequest request);

    /** 迁移公告归属渠道；只影响迁移后的新申请。 */
    CampaignResponse migrateCampaignChannel(String campaignId, MigrateChannelRequest request);

    /** 渠道某 UTC 日用量；utcDate 缺省为当前 UTC 日。 */
    ChannelUsageResponse queryChannelUsage(String channelKey, LocalDate utcDate);

    /** 渠道某 UTC 日预占明细；utcDate 缺省为当前 UTC 日。 */
    List<ChannelReservationResponse> listChannelReservations(String channelKey, LocalDate utcDate);

    /** 渠道某 UTC 日按公告归属统计；utcDate 缺省为当前 UTC 日。 */
    ChannelStatsResponse queryChannelStats(String channelKey, LocalDate utcDate);
}
