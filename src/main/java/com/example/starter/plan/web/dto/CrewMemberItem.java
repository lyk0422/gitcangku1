package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 乘务组成员项：登记到计划上的一位乘务员（以工号标识）。
 */
public record CrewMemberItem(@NotBlank String memberKey) {
}
