package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换草稿危险品编组请求，expectedVersion 必须与当前版本一致。
 * 车厢位置重复不在 Bean 校验阶段处理，由业务层返回 422 并列出冲突位置。
 */
public record UpdateConsistRequest(
        @NotBlank String requestKey,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 200) List<@Valid ConsistCarRequest> cars) {
}
