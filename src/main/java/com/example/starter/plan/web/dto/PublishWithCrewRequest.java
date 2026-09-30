package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 带乘务组登记的发布请求。requestKey 为发布幂等键；
 * crew 为本趟值乘乘务组（1～8 人，成员工号不得重复）。同一 requestKey 换乘务组重放返回 409。
 */
public record PublishWithCrewRequest(
        @NotBlank String requestKey,
        @NotEmpty @Size(max = 8) @Valid List<CrewMemberItem> crew) {
}
