package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交器材检录/复检请求。
 *
 * <p>PASS 有效分钟数取赛事配置（建赛时给定的 validMinutes），PASS 有效至
 * 检录时刻加该分钟数；选手仅提交器材序列号与检录结果。
 *
 * @param inspectionKey   检录记录业务键（第二层幂等键），同键同参重放首次结果、异参409
 * @param equipmentSerial 器材序列号，赛事内同一时刻最多绑定一个未完赛选手
 * @param result          检录结果，仅接受 PASS 或 FAIL
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SubmitInspectionRequest(
        @NotBlank String inspectionKey,
        @NotBlank String equipmentSerial,
        @NotBlank String result,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
