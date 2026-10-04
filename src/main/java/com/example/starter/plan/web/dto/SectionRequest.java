package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 区段登记请求。区段登记后不可变；同 sectionId 重复登记相同起讫站返回原记录，
 * 不同起讫站返回 409。
 */
public record SectionRequest(
        @NotBlank String sectionId,
        @NotBlank String fromStation,
        @NotBlank String toStation) {
}