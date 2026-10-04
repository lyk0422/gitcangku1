package com.example.starter.session;

import com.example.starter.observation.CreateObservationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 创建观测命令：包装现有创建请求。
 *
 * @param request 现有观测创建请求
 */
public record CreateObservationCommand(
        @NotNull @Valid CreateObservationRequest request) implements ObservationCommand {
}