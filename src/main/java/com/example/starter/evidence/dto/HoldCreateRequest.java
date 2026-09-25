package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保全冻结创建请求。holdKey 兼作幂等命令键：指纹含案件号、规范化证物集合、
 * 生效区间与原因，同键同参重放返回首次结果，失败不占键。
 * evidenceKeys 在构造时规范化（去空白、去重、字典序排序），保证指纹与存储一致。
 *
 * @param holdKey       冻结业务键（兼幂等命令键），全局唯一
 * @param caseKey       案件号
 * @param evidenceKeys  证物键集合（提交后规范化排序）
 * @param effectiveFrom 生效起点（UTC，左闭），不得早于服务端当前时刻
 * @param effectiveTo   生效终点（UTC，右开），须晚于起点
 * @param reason        冻结原因
 */
public record HoldCreateRequest(
        @NotBlank @Size(max = 64) String holdKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotNull LocalDateTime effectiveFrom,
        @NotNull LocalDateTime effectiveTo,
        @NotBlank @Size(max = 512) String reason) {

    public HoldCreateRequest {
        if (evidenceKeys != null) {
            evidenceKeys = evidenceKeys.stream().distinct().sorted().toList();
        }
    }
}
