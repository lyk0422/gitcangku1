package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控业务服务：三层额度（公告当日总额度、访客跨展示位每日共享上限、展示位每日额度）。
 *
 * <p>所有写操作以 requestId 为全局幂等键：同键同参重放原成功结果，异参 409；
 * 业务失败随事务回滚，不占幂等键。所有操作与额度查询先结算相关过期预占，
 * 不依赖后台定时器。终态竞争由行锁 + 状态 CAS 保证只允许一个终态。</p>
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    PlacementResponse createPlacement(String campaignId, CreatePlacementRequest request);

    /** 旧申请接口：等价于申请 DEFAULT 展示位。 */
    ReservationResponse apply(ApplyExposureRequest request);

    /** 按展示位申请曝光：同一事务同时取得三层额度。 */
    ReservationResponse applyPlacement(ApplyPlacementExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    /** 旧额度查询：不按展示位过滤，响应保持兼容。 */
    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /** 三层额度查询：placementCode 为 null 时不返回展示位层与明细之外的过滤效果。 */
    QuotaResponse queryQuota(String campaignId, String visitorId, String placementCode,
                             LocalDate utcDate);

    /** 列出某公告下全部展示位（DEFAULT 在前）。 */
    List<PlacementResponse> listPlacements(String campaignId);
}
