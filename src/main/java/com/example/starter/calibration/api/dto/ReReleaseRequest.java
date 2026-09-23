package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 重新放行请求。必须提交原批次全部位置的精确映射并附版本。
 *
 * @param batchId   被复核驳回（REVIEW_REQUIRED）的来源批次 ID
 * @param requestId 重新放行幂等键，全局唯一；同参换序重放返回原结果，异参 409
 * @param items     逐位置映射（驳回项用最新修订，未驳回项用原测量），不得缺漏或重复
 */
public record ReReleaseRequest(String batchId, String requestId, List<ReReleaseItem> items) {
}
