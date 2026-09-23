package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 重新放行请求。必须提交原批次全部位置的精确映射：
 * 驳回项用最新修订（键+版本），未驳回项用原测量（键+冻结版本）。
 *
 * @param requestId 重新放行幂等键，全局唯一
 * @param items     全部位置的映射项
 */
public record ReReleaseRequest(String requestId, List<ReReleaseItemRequest> items) {

    /**
     * 单个映射项。
     *
     * @param measurementKey 测量键
     * @param version        版本号：驳回项为最新修订版本，未驳回项为冻结的原版本
     */
    public record ReReleaseItemRequest(String measurementKey, Integer version) {
    }
}
