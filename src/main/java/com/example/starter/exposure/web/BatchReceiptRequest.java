package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量曝光确认回执请求。整批先核对最终状态再一次事务提交：
 * 任一预占不存在、归属不匹配、已取消或回执时刻早于预占，整批不结算、不留半成品。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param operator   操作者标识，参与幂等指纹
 * @param campaignId 本批回执归属的公告编号；任一预占归属不匹配则整批失败
 * @param items      回执项，按提交顺序裁决，1～200 条
 */
public record BatchReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Size(min = 1, max = 200) List<@Valid ReceiptItem> items
) {
}
