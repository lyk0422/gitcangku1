package com.example.starter.api.dto;

/** 测色上报响应：replayed=true 表示同参幂等重放，未新增记录。 */
public record MeasurementResponse(
        long measurementId,
        long rollId,
        String kind,
        double deltaE,
        boolean qualified,
        int targetVersion,
        boolean replayed) {
}