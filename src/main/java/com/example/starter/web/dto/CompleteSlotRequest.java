package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 除冰完成请求，fluidType为液型代码A或B。 */
public record CompleteSlotRequest(
        @NotBlank(message = "fluidType不能为空") String fluidType) {
}