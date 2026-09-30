package com.example.starter.archival.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 档案页登记请求。
 *
 * @param accessionNo 馆藏号，全局唯一
 */
public record RegisterPageRequest(
        @NotBlank(message = "馆藏号不能为空")
        @Size(max = 64, message = "馆藏号长度不能超过64")
        String accessionNo) {
}
