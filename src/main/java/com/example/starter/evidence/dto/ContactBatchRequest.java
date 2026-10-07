package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 接触批次登记请求。当前保管人（X-Actor-Id）登记同属自己的 2~5 件
 * 正常 SEALED 且未污染证物组成一次接触批次；借出、待交接或已隔离对象不得参加。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKeys 参与接触的证物业务键，2~5 件且不重复
 */
public record ContactBatchRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull @Size(min = 2, max = 5) List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
