package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 批次消息携带的观测业务请求：复用现有创建/合并参数，type 决定解释方式。
 *
 * @param type          请求类型：CREATE 创建 / MERGE 三方合并
 * @param requestId     观测业务请求标识（幂等去重键）
 * @param observationId 观测记录唯一标识
 * @param location      观测地点（CREATE/MERGE 均必填）
 * @param reading       观测读数，十进制字符串，最多三位小数（CREATE/MERGE 均必填）
 * @param note          观测备注（CREATE/MERGE 均必填）
 * @param baseVersion   合并基线版本号（仅 MERGE 必填）
 */
public record ObservationCommand(
        @NotNull CommandType type,
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @Size(max = 512) String location,
        @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "reading must be a decimal string with at most 3 fraction digits")
        String reading,
        @Size(max = 1024) String note,
        @Min(1) Integer baseVersion) {
}