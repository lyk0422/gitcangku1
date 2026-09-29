package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 封锁登记成功响应：封锁快照与同一事务内对全部相交计划的处理结果（有序）。
 */
public record WorkBlockResponse(WorkBlockView block, List<WorkBlockActionView> clearances) {
}
