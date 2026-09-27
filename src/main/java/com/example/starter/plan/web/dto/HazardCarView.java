package com.example.starter.plan.web.dto;

/**
 * 危险品车厢视图，按车厢位置升序返回。
 */
public record HazardCarView(Integer carPosition, String hazardClass) {
}
