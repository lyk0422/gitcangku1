package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交回滚演练结果请求。必须一次性提交与演练规范集合完全一致的每台设备结果，
 * 服务端先校验完整最终状态，再在同一事务内批量落库；校验失败不产生任何终态或门禁。
 * 集合层面的业务校验（空/重复/缺漏/失败原因缺失）在服务层返回 422。
 *
 * @param requestId 幂等请求ID
 * @param operator  结果提交操作者（门禁授予操作者），纳入幂等指纹
 * @param results   每台设备的回退结果，集合须与开始时规范集合完全相等
 */
public record CompleteRehearsalRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        List<DeviceResultInput> results) {

    /**
     * 单台设备回退结果。FAILED 时 failureReason 必填；SUCCESS 时忽略该字段（落库为 null）。
     */
    public record DeviceResultInput(
            String deviceId,
            ReceiptResult result,
            String failureReason) {
    }
}
