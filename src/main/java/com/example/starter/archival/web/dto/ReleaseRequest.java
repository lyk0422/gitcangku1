package com.example.starter.archival.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 页面调拨/归还请求：从未封存批次归还档案页，并同步释放处理占用。
 *
 * @param batchNo     批次号
 * @param accessionNo 馆藏号
 */
public record ReleaseRequest(
        @NotBlank(message = "批次号不能为空") String batchNo,
        @NotBlank(message = "馆藏号不能为空") String accessionNo) {
}
