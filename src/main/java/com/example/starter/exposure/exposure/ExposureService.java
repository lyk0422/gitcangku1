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

/**
 * 公告曝光频控业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /**
     * 新增展示位：携带 expectedConfigVersion 做乐观并发控制，创建后不可修改删除。
     */
    PlacementResponse createPlacement(String campaignId, CreatePlacementRequest request);

    /** 旧申请入口：等价于按 DEFAULT 展示位申请。 */
    ReservationResponse apply(ApplyExposureRequest request);

    /** 按展示位申请曝光：同一事务同时占用公告总额、访客跨位共享上限、展示位额度三层。 */
    ReservationResponse applyPlacement(ApplyPlacementExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    /**
     * 按公告/访客/展示位/UTC 日查询三层额度及预占展示位明细；
     * visitorId、placementCode 为空时对应维度不返回。
     */
    QuotaResponse queryQuota(String campaignId, String visitorId, String placementCode,
                             LocalDate utcDate);
}
