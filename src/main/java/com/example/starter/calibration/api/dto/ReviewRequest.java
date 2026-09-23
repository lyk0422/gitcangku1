package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 复核驳回请求。针对一个生效中的放行批次提交 1～全部测量的驳回集合。
 *
 * @param reviewKey 复核幂等键，全局唯一
 * @param batchId   被复核的放行批次 ID
 * @param items     驳回项集合（1～批次全部位置）
 */
public record ReviewRequest(String reviewKey, String batchId, List<ReviewItemRequest> items) {

    /**
     * 单个驳回项。
     *
     * @param measurementKey 测量键
     * @param version        复核时看到的测量版本号
     * @param reason         驳回原因
     */
    public record ReviewItemRequest(String measurementKey, Integer version, String reason) {
    }
}
