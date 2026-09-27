package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布单模块豁免整体修改请求。entries 为修改后态的全量豁免集合（整体替换）：
 * 先验证完整后态（时刻合法、到期时刻晚于当前时刻、无重复条目），再在一个事务内提交并产生新版本号。
 * entries 允许为空列表，表示清空豁免（同样产生新版本）。
 */
public record ModifyExemptionsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Valid List<ExemptionEntry> entries) {
}
