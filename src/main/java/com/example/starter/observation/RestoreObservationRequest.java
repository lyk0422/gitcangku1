package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 墓碑显式恢复请求：仅当记录当前为墓碑且 expectedVersion 匹配当前墓碑版本时，
 * 从本记录已存在的非墓碑历史版本 sourceVersion 复制内容，生成新版本快照。
 *
 * @param requestId       全局唯一请求标识（幂等去重键，与其他写操作共用同一套规则）
 * @param expectedVersion 期望恢复时的当前（墓碑）版本号，不匹配返回 409
 * @param sourceVersion   恢复内容来源的历史版本号，必须存在且为非墓碑版本，可跨代次
 * @param reason          非空恢复原因说明
 */
public record RestoreObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(1) Integer sourceVersion,
        @NotBlank @Size(max = 1024) String reason) {
}
