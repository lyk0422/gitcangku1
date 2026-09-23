package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
import com.example.starter.exposure.web.QuotaDetailResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控业务服务：公告、展示位、三层额度（公告当日总额 / 访客跨展示位当日共享额 /
 * 展示位当日额）预占与回执。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /** 新增展示位（创建后不可修改、不可删除）；expectedConfigVersion 不匹配返回 409。 */
    PlacementResponse createPlacement(String campaignId, CreatePlacementRequest request);

    /** 列出公告下全部展示位（含 DEFAULT）。 */
    List<PlacementResponse> listPlacements(String campaignId);

    /** 旧申请接口，等价于在 DEFAULT 展示位申请。 */
    ReservationResponse apply(ApplyExposureRequest request);

    /** 按展示位申请曝光：同一事务占用公告/访客/展示位三层额度。 */
    ReservationResponse applyPlacement(ApplyPlacementExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    /**
     * 三层额度查询；visitorId/placementCode 缺省时对应层字段为 null。
     * 旧调用（不传 placementCode）响应结构保持兼容。
     */
    QuotaResponse queryQuota(String campaignId, String visitorId,
                             String placementCode, LocalDate utcDate);

    /** 三层额度查询并附匹配维度的预占展示位明细。 */
    QuotaDetailResponse queryQuotaDetail(String campaignId, String visitorId,
                                         String placementCode, LocalDate utcDate);
}
