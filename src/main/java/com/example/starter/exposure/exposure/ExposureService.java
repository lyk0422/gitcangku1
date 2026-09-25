package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignAttributionResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.ChannelResponse;
import com.example.starter.exposure.web.ChannelUsageResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateChannelRequest;
import com.example.starter.exposure.web.MigrateCampaignChannelRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateChannelCapRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控业务服务：公告/访客两级额度 + 渠道总量频控。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 创建渠道总量配置（渠道按 UTC 自然日配置总确认额度）。 */
    ChannelResponse createChannel(CreateChannelRequest request);

    /** 修改渠道日额度：携带 expectedVersion，冲突 409，不得下调到低于当前已确认（占用）数。 */
    ChannelResponse updateChannelCap(String channelKey, UpdateChannelCapRequest request);

    /** 公告迁移归属渠道：只影响迁移后新申请，既有预占按创建时固化渠道结算。 */
    CampaignResponse migrateCampaignChannel(String campaignId, MigrateCampaignChannelRequest request);

    /** 查询渠道日用量；utcDate 缺省使用服务端当前 UTC 日。 */
    ChannelUsageResponse queryChannelUsage(String channelKey, LocalDate utcDate);

    /** 预占明细查询：按公告/渠道/UTC 日任意组合过滤。 */
    List<ReservationResponse> listReservations(String campaignId, String channelKey, LocalDate utcDate);

    /** 按公告归属统计：以预占创建时固化的渠道分组。 */
    CampaignAttributionResponse attributionStats(String campaignId);
}
