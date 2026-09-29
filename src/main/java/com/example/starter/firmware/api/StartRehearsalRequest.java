package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 开始回滚演练请求。
 * 规范设备集合 deviceIds 必须非空且不重复；集合层面的业务校验（空/重复/不相容）在服务层返回 422，
 * 开始时刻由服务端统一时钟生成（UTC），不由客户端指定。
 *
 * @param requestId       幂等请求ID
 * @param operator        操作者，纳入幂等指纹
 * @param model           规范设备型号
 * @param targetVersion   目标版本（待发布版本），设备开始时必须处于该版本
 * @param rollbackVersion 回退版本（正式投放来源版本），必须与目标版本不同
 * @param deviceIds       规范设备ID集合，至少一台；服务端按ID升序规范化
 */
public record StartRehearsalRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String model,
        @NotBlank @Size(max = 64) String targetVersion,
        @NotBlank @Size(max = 64) String rollbackVersion,
        List<@Size(max = 64) String> deviceIds) {
}
