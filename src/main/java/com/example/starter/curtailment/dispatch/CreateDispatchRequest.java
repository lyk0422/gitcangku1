package com.example.starter.curtailment.dispatch;

import java.time.Instant;
import java.util.List;

/**
 * 创建削减调度请求，创建后为草稿状态。
 *
 * @param commandKey    命令幂等键
 * @param dispatchKey   调度业务键，全局唯一
 * @param feederId      馈线ID
 * @param executeFrom   执行区间起点（UTC，含）
 * @param executeTo     执行区间终点（UTC，不含）
 * @param targetPowerKw 目标削减功率，十进制字符串，最多 3 位小数，单位 kW
 * @param allocations   初始站点分配，1～50 条，站点唯一，功率之和须精确等于目标功率
 */
public record CreateDispatchRequest(String commandKey, String dispatchKey, String feederId,
                                    Instant executeFrom, Instant executeTo, String targetPowerKw,
                                    List<AllocationRequest> allocations) {
}
