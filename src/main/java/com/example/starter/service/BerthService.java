package com.example.starter.service;

import com.example.starter.domain.Allocation;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.BerthTimelineResponse;
import com.example.starter.web.dto.PublishTideRequest;
import com.example.starter.web.dto.ReviewRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 泊位靠泊申请、潮高版本发布与人工复核应用服务。
 */
public interface BerthService {

    /**
     * 提交靠泊申请并完成首次校核；同一 requestId 同参重放首次结果，异参 409。
     *
     * @param request 申请参数
     * @return 申请详情
     */
    Allocation apply(ApplyRequest request);

    /**
     * 发布新的潮高版本；已确认但未开始且深度不再满足的申请标记待复核并释放占用。
     *
     * @param request 版本与潮高区间
     * @return 新版本号
     */
    int publishTide(PublishTideRequest request);

    /**
     * 人工复核待复核申请：APPROVE 时按当前最新潮高版本重新校核通过才确认，否则驳回。
     *
     * @param request 复核请求
     * @return 更新后的申请
     */
    Allocation review(ReviewRequest request);

    /**
     * 查询申请详情。
     *
     * @param requestId 幂等键
     * @return 申请详情
     */
    Allocation getByRequestId(String requestId);

    /**
     * 查询泊位时间轴。
     *
     * @param berthCode 泊位编码
     * @return 时间轴
     */
    BerthTimelineResponse getTimeline(String berthCode);
}
