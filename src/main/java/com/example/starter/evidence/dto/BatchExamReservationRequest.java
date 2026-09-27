package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量检验预约请求：先校验全部台位与证物，任一失败整批不落库；
 * 全部合法时在同一事务中提交。
 *
 * @param commandKey 幂等命令键（整批共用一个）
 * @param items      预约项列表，非空；批内证物与预约键不得重复
 */
public record BatchExamReservationRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty @Size(max = 50) List<@Valid ExamReservationItem> items) {
}
