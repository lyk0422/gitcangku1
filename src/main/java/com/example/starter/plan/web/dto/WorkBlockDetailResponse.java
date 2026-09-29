package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 封锁详情查询响应：封锁窗口与受影响计划处理结果列表（按登记事务内处理顺序）。
 */
public record WorkBlockDetailResponse(WorkBlockView block, List<WorkBlockClearanceView> clearances) {
}
