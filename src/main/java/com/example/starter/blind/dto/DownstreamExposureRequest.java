package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 下游披露登记请求体：接收人继续向更下游操作者登记自己发起的披露。
 * 接收人不持有 exposureKey，其「已获知」资格由其是否已在污染闭包中强制校验。
 *
 * @param recipients 本次直接披露的接收操作者编号集合，去重后 1～20 个，集合顺序不影响幂等判定
 */
public record DownstreamExposureRequest(
        @NotEmpty(message = "至少登记 1 名接收操作者")
        @Size(max = 20, message = "单次登记的接收操作者不能超过 20 名")
        List<@NotBlank(message = "接收操作者编号不能为空") String> recipients
) {
}
