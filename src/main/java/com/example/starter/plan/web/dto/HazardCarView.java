package com.example.starter.plan.web.dto;

/**
 * 危险品车厢视图：车厢位置与危险品类别，按列车、位置升序返回。
 */
public record HazardCarView(String trainNo, int position, String hazardClass) {
}
