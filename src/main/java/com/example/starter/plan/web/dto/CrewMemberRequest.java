package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 乘务员登记请求，memberKey 为工号业务键。
 */
public record CrewMemberRequest(
        @NotBlank @Size(max = 64) String memberKey,
        @NotBlank @Size(max = 64) String name) {
}
