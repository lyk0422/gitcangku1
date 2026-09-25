package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 撤销更正附页请求：只允许撤销最新版本附页，撤销后写入不可变撤销记录并恢复上一个有效版本。
 *
 * @param corrKey     幂等键：指纹含附页版本与操作者；同键同参重放，失败不占键
 * @param corrVersion 待撤销的附页版本号，必须为当前最新附页版本，否则 409
 * @param operator    执行撤销的操作者标识
 * @param reason      撤销原因（可选）
 */
public record RevokeCorrigendumRequest(
        @NotBlank @Size(max = 128) String corrKey,
        @NotNull @Min(1) Integer corrVersion,
        @NotBlank @Size(max = 128) String operator,
        @Size(max = 1024) String reason) {
}
