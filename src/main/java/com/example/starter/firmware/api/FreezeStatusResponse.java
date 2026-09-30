package com.example.starter.firmware.api;

import java.util.List;

/**
 * 版本冻结查询视图（只读）：freeze 为该版本最近一条冻结区间（从未冻结为 null），
 * restores 为该区间全部恢复申请与审批审计，按创建顺序排列。
 */
public record FreezeStatusResponse(String version, FreezeView freeze, List<RestoreView> restores) {
}
