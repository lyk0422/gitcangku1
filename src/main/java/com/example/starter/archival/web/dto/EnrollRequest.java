package com.example.starter.archival.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 入组请求：把档案页加入一个未封存脱酸批次。requestId 用于幂等。
 *
 * @param batchNo     批次号
 * @param accessionNo 馆藏号
 * @param requestId   入组幂等键；同参重放原结果，异参冲突 409
 */
public record EnrollRequest(
        @NotBlank(message = "批次号不能为空") String batchNo,
        @NotBlank(message = "馆藏号不能为空") String accessionNo,
        @NotBlank(message = "requestId不能为空") String requestId) {
}
