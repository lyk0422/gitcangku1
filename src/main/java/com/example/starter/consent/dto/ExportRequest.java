package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 导出快照请求：在一个事务内固化主体各用途当前有效代次的全部记录。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回首次响应快照
 * @param exportKey  导出标识，全局唯一；失败请求不占用
 * @param subjectKey 主体标识（合成字符串）
 * @param purposes   请求用途集合，1～2 个且不重复，换序视为同参
 */
public record ExportRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String exportKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull @Size(min = 1, max = 2) List<@NotNull Purpose> purposes) {
}
