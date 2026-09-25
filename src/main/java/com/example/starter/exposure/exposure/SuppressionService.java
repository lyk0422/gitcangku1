package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.CreateSuppressionRequest;
import com.example.starter.exposure.web.SuppressionBatchUpdateRequest;
import com.example.starter.exposure.web.SuppressionBatchUpdateResponse;
import com.example.starter.exposure.web.SuppressionHistoryResponse;
import com.example.starter.exposure.web.SuppressionIntervalResponse;
import com.example.starter.exposure.web.SuppressionStatusResponse;

/**
 * 访客抑制名单业务服务：区间创建、批量原子更新、状态/历史/原因查询。
 */
public interface SuppressionService {

    /**
     * 创建单条抑制区间；同一访客重叠区间返回 409，起止非法返回 422。
     */
    SuppressionIntervalResponse createInterval(String campaignId, CreateSuppressionRequest request);

    /**
     * 携带 expectedVersion 的批量原子更新：先校验完整最终区间集合，
     * 任一重叠或起止非法整批 422 且原名单不变；版本不匹配 409。
     */
    SuppressionBatchUpdateResponse batchUpdate(String campaignId, SuppressionBatchUpdateRequest request);

    /**
     * 查询访客在某时刻（缺省为当前时刻）的抑制状态及被抑制原因。
     */
    SuppressionStatusResponse queryStatus(String campaignId, String visitorId, Long atUtc);

    /**
     * 查询访客在公告下的区间历史（含 DELETED 快照）与不可变删除记录。
     */
    SuppressionHistoryResponse queryHistory(String campaignId, String visitorId);
}
