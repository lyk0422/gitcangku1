package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 拆分请求中的单个子批描述：仅提供子批业务键与批号；
 * 产品编码、生产 UTC 时间与必做检验项均继承自父批。
 */
public record SplitChildRequest(
        @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
        @NotBlank(message = "子批 batchNo 不能为空") String batchNo
) {
}
