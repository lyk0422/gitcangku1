package com.example.starter.salinity.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 质控采纳命令：质控员将指定样本采纳为其站位深度的唯一版本。
 *
 * @param voyageCode 质控员指定的航次编号，非空；必须与样本所属航次一致
 * @param sampleNo   待采纳样本的采样编号，非空
 */
public record AdoptSampleCommand(
        @NotBlank(message = "航次编号不能为空")
        String voyageCode,

        @NotBlank(message = "采样编号不能为空")
        String sampleNo
) {
}
