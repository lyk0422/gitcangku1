package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DailySnapshotResponse;
import com.example.starter.exposure.web.HourDiagnosticResponse;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationHistoryResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;

import java.time.LocalDate;

/**
 * 公告曝光频控与活动匀速投放业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /** 创建活动：24 个小时预算均为非负整数且合计等于日总额。 */
    ActivityResponse createActivity(CreateActivityRequest request);

    /** 修改活动配置：携带 expectedVersion，已开始小时不可下调，历史记录不迁移。 */
    ActivityResponse updateActivity(String activityId, UpdateActivityRequest request);

    /** 活动明细（只读）。 */
    ActivityResponse getActivity(String activityId);

    /**
     * 公告活动迁移：只影响迁移之后的新预占，既有预占固化活动与小时归属。
     * activityId 为 null/空表示移出活动。
     */
    CampaignResponse migrateCampaign(String campaignId, MigrateCampaignRequest request);

    /** 公告明细（只读，含当前归属活动）。 */
    CampaignResponse getCampaign(String campaignId);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 活动某 UTC 日 24 小时匀速投放诊断（只读）。 */
    HourDiagnosticResponse queryHourDiagnostic(String activityId, LocalDate utcDate);

    /**
     * 活动日结：固化每小时确认数与取消数快照。已固化日只读不改写；
     * 当天或尚未彻底终结返回 frozen=false 的实时值。
     */
    DailySnapshotResponse queryDailySnapshot(String activityId, LocalDate utcDate);

    /**
     * 预占历史查询（只读）。区间左闭右开 [fromUtc, toUtc)，按创建时刻、预占单编号稳定排序。
     */
    ReservationHistoryResponse queryHistory(String campaignId, String visitorId,
                                            LocalDate fromUtc, LocalDate toUtc, Integer limit);
}
