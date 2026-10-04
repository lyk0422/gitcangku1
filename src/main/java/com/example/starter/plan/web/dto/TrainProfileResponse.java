package com.example.starter.plan.web.dto;

/**
 * 列车牵引档案响应。
 */
public record TrainProfileResponse(String trainNo, String tractionMode, int version) {
}