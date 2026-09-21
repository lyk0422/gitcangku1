package com.example.starter.curtailment.dispatch;

import java.util.List;

/**
 * 整体替换草稿分配请求。
 *
 * @param commandKey     命令幂等键
 * @param expectedVersion 期望的调度版本，不匹配返回 409
 * @param allocations    新的站点分配，1～50 条，站点唯一，功率之和须精确等于目标功率
 */
public record ReplaceAllocationsRequest(String commandKey, Long expectedVersion,
                                        List<AllocationRequest> allocations) {
}
