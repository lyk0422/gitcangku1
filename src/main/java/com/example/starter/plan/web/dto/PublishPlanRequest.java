package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 发布计划请求：requestKey 为幂等键；crewMembers 为本次登记的乘务组（可为空，
 * 空表示不启用乘务资格门禁）。同一 requestKey 重放返回首次人员与时隙分配，
 * 同键换乘务组返回 409。
 */
public record PublishPlanRequest(
        @NotBlank String requestKey,
        List<@NotBlank String> crewMembers) {
}