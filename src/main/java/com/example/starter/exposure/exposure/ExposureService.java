package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyResult;
import com.example.starter.exposure.web.BatchUpdateSuppressionListRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateSuppressionIntervalRequest;
import com.example.starter.exposure.web.EndSuppressionIntervalRequest;
import com.example.starter.exposure.web.IdempotentRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressionIntervalResponse;
import com.example.starter.exposure.web.SuppressionListResponse;
import com.example.starter.exposure.web.VisitorSuppressionStatusResponse;

import java.time.LocalDate;

/**
 * 公告曝光频控与访客抑制名单业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /**
     * 申请曝光：命中抑制名单返回 SUPPRESSED（不创建预占、不扣额度），否则创建 60 秒预占。
     */
    ApplyResult apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /**
     * 创建单条抑制区间：起止非法 422，与同访客现有生效区间重叠 409。
     */
    SuppressionIntervalResponse createSuppressionInterval(String campaignId,
                                                          CreateSuppressionIntervalRequest request);

    /**
     * 批量更新抑制名单：expectedVersion 不一致 409；任一重叠或起止非法整批 422，原名单不变。
     */
    SuppressionListResponse batchUpdateSuppressionList(String campaignId,
                                                       BatchUpdateSuppressionListRequest request);

    /**
     * 删除未开始的抑制区间：立即失效并保留不可变删除记录；已开始或非 ACTIVE 返回 409。
     */
    SuppressionIntervalResponse deleteSuppressionInterval(String campaignId, String intervalId,
                                                          IdempotentRequest request);

    /**
     * 提前结束已开始的抑制区间：结束时刻不得早于当前时刻，且须早于当前生效结束时刻。
     */
    SuppressionIntervalResponse endSuppressionInterval(String campaignId, String intervalId,
                                                       EndSuppressionIntervalRequest request);

    /**
     * 查询访客当前抑制状态与被抑制原因。
     */
    VisitorSuppressionStatusResponse getVisitorSuppressionStatus(String campaignId, String visitorId);

    /**
     * 查询区间历史（全部状态，含不可变删除/提前结束记录）；visitorId 为 null 时返回全公告。
     */
    SuppressionListResponse listSuppressionIntervals(String campaignId, String visitorId);
}
