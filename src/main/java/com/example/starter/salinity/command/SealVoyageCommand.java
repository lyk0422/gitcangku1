package com.example.starter.salinity.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 航次封存命令：将各站位 0、50、100 米采纳样本固化为剖面。
 *
 * @param voyageCode 航次编号，非空
 */
public record SealVoyageCommand(
        @NotBlank(message = "航次编号不能为空")
        String voyageCode
) {
}
