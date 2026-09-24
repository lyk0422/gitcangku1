package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建销毁令请求。保管人取自 X-Actor-Id；入列证物须全部合格，否则整单 422 且不创建销毁令。
 *
 * @param commandKey         幂等命令键
 * @param destructionKey     销毁令业务键，全局唯一
 * @param evidenceKeys      入列证物键集合，1～20 件；换序视为同参
 * @param legalBasis       非空法律依据编号
 * @param destructionMethod 销毁方式，非空
 * @param forceIncludeBroken 是否显式允许封条异常证物入列；false 时入列封条异常证物整单 422
 */
public record DestructionCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String destructionKey,
        @NotEmpty @Size(min = 1, max = 20) List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotBlank @Size(max = 128) String legalBasis,
        @NotBlank @Size(max = 128) String destructionMethod,
        @NotNull Boolean forceIncludeBroken) {
}
