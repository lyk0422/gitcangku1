package com.example.starter.web.dto;

import com.example.starter.model.FluidType;
import jakarta.validation.constraints.NotNull;

/**
 * 完成除冰作业请求。
 *
 * @param fluidType 使用液型
 */
public record CompleteSlotRequest(@NotNull FluidType fluidType) {
}