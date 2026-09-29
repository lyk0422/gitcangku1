package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量提交演练结果请求。业务层先校验完整集合（非空、与演练规范集合完全一致、无重复、每台结果非空），
 * 再在一个事务内整批写入；任一失败不产生部分结果与 READY 门禁。空集合由业务层返回 422。
 *
 * @param requestId 幂等请求ID
 * @param operator  操作者，参与幂等指纹
 * @param results   每台设备的回退结果，集合必须与演练规范集合一致
 */
public record RehearsalResultRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Valid List<DeviceResult> results) {

    /**
     * 单台设备的回退结果。
     *
     * @param deviceId 设备ID
     * @param result   回退结果 SUCCESS / FAILED
     */
    public record DeviceResult(
            @NotBlank @Size(max = 64) String deviceId,
            @NotNull String result) {
    }
}
