package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivityDayResponse;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateActivityRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.ActivityResponse;
import com.example.starter.exposure.web.DailySettlementResponse;
import com.example.starter.exposure.web.MigrateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateActivityRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控与活动匀速投放业务服务。
 *
 * <p>申请曝光除访客频控外，必须为当前 UTC 小时原子预占活动名额；确认同时结算公告、访客、
 * 活动三侧；取消或过期只释放尚未确认的同一小时预占。所有写操作以 requestId 为全局幂等键。</p>
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /** 创建活动：24 个小时预算均为非负整数且合计等于日总额。 */
    ActivityResponse createActivity(CreateActivityRequest request);

    /** 修改活动配置：携带 expectedVersion；已开始小时不可下调；历史记录不迁移。 */
    ActivityResponse updateActivity(String activityId, UpdateActivityRequest request);

    /** 只读活动配置。 */
    ActivityResponse getActivity(String activityId);

    /** 公告活动迁移：只影响迁移后的新预占。 */
    CampaignResponse migrateCampaign(String campaignId, MigrateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /**
     * 日结查询：往日首次查询固化每小时确认数与取消数快照，后续不改写；查询当日返回实时账目不固化。
     */
    DailySettlementResponse dailySettlement(String activityId, LocalDate utcDate);

    /** 活动某日 24 小时投放诊断（预算、占用、确认/取消/过期、剩余差额），只读。 */
    ActivityDayResponse activityDay(String activityId, LocalDate utcDate);

    /** 公告预占历史，按创建时刻倒序，只读；utcDate/status 可过滤。 */
    List<ReservationResponse> listReservations(String campaignId, LocalDate utcDate,
                                               String status, int limit);
}
