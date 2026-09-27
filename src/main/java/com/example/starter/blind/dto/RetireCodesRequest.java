package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 盲态代码退役请求体；一次可退役多个处理代码，整批先校验后态再一次提交。
 *
 * @param reason 退役原因，必填非空，最长 500 字符，提交后不可变
 * @param codes  待退役处理代码集合（指纹按去重、升序规范化，元素顺序与重复不影响语义），不能为空
 */
public record RetireCodesRequest(
        @NotBlank(message = "reason 不能为空")
        @Size(max = 500, message = "reason 最长 500 字符")
        String reason,

        @NotEmpty(message = "codes 不能为空")
        List<@NotBlank(message = "codes 不能含空白代码") String> codes
) {
}
