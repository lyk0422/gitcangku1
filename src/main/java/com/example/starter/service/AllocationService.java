package com.example.starter.service;

import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.TimelineView;

/**
 * 观测窗口业务入口：申请、批准、撤销（含候补接替）与查询。
 */
public interface AllocationService {

    /** 提交窗口申请；申请通过即进入待批准/候补队列。 */
    RequestView apply(ApplyRequest request);

    /** 管理员批准：锁定整段窗口，与已分配窗口冲突返回 409。 */
    RequestView approve(String requestNo, String requestId);

    /** 研究组撤销自己尚未开始的申请，并触发候补接替。 */
    RequestView cancel(String requestNo, String researchGroup, String requestId);

    /** 查询单个申请状态与接替来源。 */
    RequestView getRequest(String requestNo);

    /** 查询仪器时间轴：已分配窗口、申请状态、候补顺序与接替流水。 */
    TimelineView getTimeline(String instrumentId);
}
