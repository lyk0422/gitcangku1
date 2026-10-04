package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 进路视图。
 *
 * @param routeId   进路编号
 * @param createdAt 注册时刻，UTC
 */
public record RouteResponse(String routeId, Instant createdAt) {
}