package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 直接披露登记请求体：持已批准揭盲颁发的 exposureKey，登记调用方向哪些操作者直接披露。
 *
 * @param exposureKey 揭盲批准后颁发的泄露登记凭据，全局唯一，确定目标参与者
 * @param recipients  本次直接披露的接收操作者编号集合，去重后 1～20 个，集合顺序不影响幂等判定
 */
public record ExposureRecordRequest(
        @NotBlank(message = "exposureKey 不能为空")
        String exposureKey,
        @NotNull(message = "recipients 不能为空")
        @NotEmpty(message = "至少登记 1 名接收操作者")
        @Size(max = 20, message = "单次登记的接收操作者不能超过 20 名")
        List<@NotBlank(message = "接收操作者编号不能为空") String> recipients
) {
}
