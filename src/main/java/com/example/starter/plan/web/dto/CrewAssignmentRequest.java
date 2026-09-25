package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 乘务指派对：一名乘务员与其所依据的资质代码。
 */
public record CrewAssignmentRequest(
        @NotBlank String crewId,
        @NotBlank String qualCode) {
}
