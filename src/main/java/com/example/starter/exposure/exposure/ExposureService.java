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
 * 公告曝光频控业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /**
     * 新增展示位：受最多 20 个唯一 code、日额度 1～100000 且不超过公告总额度、
     * expectedConfigVersion 乐观并发控制约束；创建后不可修改或删除。
     */
    PlacementResponse createPlacement(String campaignId, CreatePlacementRequest request);

    /** 列出公告下全部展示位（含 DEFAULT），按配置版本号升序。 */
    List<PlacementResponse> listPlacements(String campaignId);

    /** 旧申请接口：等价于申请 DEFAULT 展示位。 */
    ReservationResponse apply(ApplyExposureRequest request);

    /** 按展示位申请曝光：同一事务同时取得三层额度。 */
    ReservationResponse applyPlacement(ApplyPlacementExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    /**
     * 按 公告/访客/展示位/UTC 日查询三层额度及预占展示位明细；
     * visitorId、placementCode 缺省时对应维度不过滤。
     */
    QuotaResponse queryQuota(String campaignId, String visitorId, String placementCode,
                             LocalDate utcDate);
}
