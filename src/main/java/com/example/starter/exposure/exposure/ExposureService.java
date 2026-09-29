package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivityDayConfigRequest;
import com.example.starter.exposure.web.ActivityDayConfigResponse;
import com.example.starter.exposure.web.ActivityDayDiagnosticsResponse;
import com.example.starter.exposure.web.ActivityDaySettlementResponse;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AssignCampaignActivityRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationHistoryResponse;
import com.example.starter.exposure.web.ReservationResponse;

import java.time.LocalDate;

/**
 * 公告曝光频控与活动匀速投放业务服务。
 *
 * <p>写操作（创建活动/公告、配置活动日、活动迁移、申请、确认、取消）均以 requestId
 * 为全局幂等键：同键同参重放首次成功结果，异参 409，业务失败不占键。</p>
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 创建活动。 */
    ActivityResponse createActivity(CreateActivityRequest request);

    /**
     * 新建或修改活动某 UTC 日配置（24 小时预算合计必须等于日总额）；
     * 修改须携带 expectedVersion，已开始小时不可下调。
     */
    ActivityDayConfigResponse configureActivityDay(String activityId, LocalDate utcDate,
                                                   ActivityDayConfigRequest request);

    /** 查询活动某日配置（只读）；不存在返回 404。 */
    ActivityDayConfigResponse getActivityDayConfig(String activityId, LocalDate utcDate);

    /** 迁移公告当前归属活动（activityId 为 null 表示移出活动）；只影响迁移后的新预占。 */
    CampaignResponse assignCampaignActivity(String campaignId, AssignCampaignActivityRequest request);

    /** 活动某日 24 小时名额诊断（只读）。 */
    ActivityDayDiagnosticsResponse activityDayDiagnostics(String activityId, LocalDate utcDate);

    /** 日结：固化活动某日每小时确认数与取消数快照，已固化小时不改写。 */
    ActivityDaySettlementResponse settleActivityDay(String activityId, LocalDate utcDate);

    /**
     * 预占历史查询（只读）。申请时刻区间左闭右开，边界 null 表示不限制；
     * 结果按申请时刻降序、预占单编号升序。
     */
    ReservationHistoryResponse findReservations(String activityId, String campaignId, String visitorId,
                                                Long fromCreatedAtUtc, Long toCreatedAtUtc, Integer limit);
}
