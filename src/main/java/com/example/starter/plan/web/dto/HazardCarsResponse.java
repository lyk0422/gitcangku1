package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划危险品编组响应：当前版本与完整编组（按车厢位置升序；取消后历史编组仍原样返回）。
 */
public record HazardCarsResponse(String scheduleKey, int version, List<HazardCarView> cars) {
}
