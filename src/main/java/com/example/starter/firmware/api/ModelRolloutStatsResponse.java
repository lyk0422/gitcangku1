package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单按硬件型号投放统计列表。
 */
public record ModelRolloutStatsResponse(List<ModelRolloutStat> stats) {
}
