package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 容量交换单项：调度员为一个已发布计划提交期望版本、当前占用段与目标占用段。
 * 两个占用段列表的顺序均不影响语义（服务端按规范排序后比较与存证）。
 *
 * @param scheduleKey       参与交换的计划业务键
 * @param expectedVersion   期望的计划当前版本，激活时重读必须一致
 * @param currentOccupancies 提交时认定的当前占用段，激活时必须与库内实际占用精确一致
 * @param targetOccupancies 交换后目标占用段，全单目标占用互不重复且不得冲突未参与计划
 */
public record SwapItemRequest(
        @NotBlank String scheduleKey,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 30) List<@Valid SwapSegmentRequest> currentOccupancies,
        @NotNull @Size(min = 1, max = 30) List<@Valid SwapSegmentRequest> targetOccupancies) {
}
