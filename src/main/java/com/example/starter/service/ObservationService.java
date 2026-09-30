package com.example.starter.service;

import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.CancelResponse;
import com.example.starter.web.dto.PromotionView;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.SlotView;
import com.example.starter.web.dto.WaitlistView;

import java.util.List;

/**
 * 观测窗口申请、批准、撤销与候补接替服务。所有时间均为 UTC，窗口左闭右开。
 */
public interface ObservationService {

    /**
     * 提交窗口申请（进入候补）。
     *
     * @param idempotencyKey 幂等键，可空
     */
    RequestView apply(String idempotencyKey, ApplyRequest request);

    /**
     * 管理员批准：锁定整段窗口；与已分配窗口重叠抛 409。
     */
    RequestView approve(String idempotencyKey, String requestNo);

    /**
     * 研究组撤销自己尚未开始的申请；已分配窗口撤销后按候补顺序寻找首个完整适配者接替。
     */
    CancelResponse cancel(String idempotencyKey, String requestNo, String projectId);

    /**
     * 某仪器已分配时间轴。
     */
    List<SlotView> timeline(String instrumentId);

    /**
     * 申请列表，可按仪器/项目过滤。
     */
    List<RequestView> requests(String instrumentId, String projectId);

    /**
     * 某仪器候补顺序。
     */
    WaitlistView waitlist(String instrumentId);

    /**
     * 全部候补接替事件，按接替时刻升序。
     */
    List<PromotionView> promotions();
}
