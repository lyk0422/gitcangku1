package com.example.starter.salinity.command;

import jakarta.validation.constraints.NotBlank;

/**
 * 封存后勘误追加命令。
 *
 * @param voyageCode 航次编号，非空，必须已封存
 * @param note       更正说明正文，非空
 */
public record AddErratumCommand(
        @NotBlank(message = "航次编号不能为空")
        String voyageCode,

        @NotBlank(message = "勘误说明不能为空")
        String note
) {
}
