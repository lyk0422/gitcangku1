package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布计划请求。requestKey 为幂等键；crewGroup 可选，登记乘务组后发布将按 UTC
 * 校验每位成员的资格区间与线路等级，并检查人员占用冲突；同一 requestKey
 * 重放返回首次人员与时隙分配，更换乘务组则按参数不一致返回 409。
 */
public record PublishRequest(@NotBlank String requestKey, String crewGroup) {
}