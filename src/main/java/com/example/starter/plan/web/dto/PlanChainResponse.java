package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 改签链查询响应：按改签先后顺序返回从最早计划到最新计划的完整有序链，
 * 每一项为该版本计划的明细（含其当时的占用快照，取消/改签后仍原样保留）。
 *
 * @param plans 有序计划链，plans[0] 为最早版本，末项为当前最新版本
 */
public record PlanChainResponse(List<PlanResponse> plans) {
}
