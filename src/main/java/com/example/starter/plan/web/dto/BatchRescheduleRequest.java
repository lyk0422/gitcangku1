package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 批量改签请求：整批先验证完整后态（含批内新计划互相冲突、危险品隔离），
 * 全部合法后在一个事务内提交；任一项非法则整批 422，时隙与编组均不改变。
 * requestKey 为幂等键；operator 缺省 system，参与幂等指纹。
 */
public record BatchRescheduleRequest(
        @NotBlank String requestKey,
        String operator,
        @NotEmpty List<@Valid BatchRescheduleItemRequest> items) {
}
