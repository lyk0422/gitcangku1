package com.example.starter.evidence.destruction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建销毁令请求。由当前保管人提交，入列 1～20 件证物；
 * evidenceKeys 集合换序视为同参（幂等指纹按排序后集合计算），重复键按非法参数处理。
 *
 * @param commandKey          幂等命令键
 * @param destructionKey      销毁令业务键，全局唯一
 * @param evidenceKeys        入列证物业务键集合，1～20 件且不得重复
 * @param legalBasis          非空法律依据编号
 * @param destructionMethod   非空销毁方式
 * @param forceIncludeBroken 是否显式声明允许封条异常证物入列
 */
public record DestructionCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String destructionKey,
        @NotEmpty @Size(min = 1, max = 20) List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotBlank @Size(max = 128) String legalBasis,
        @NotBlank @Size(max = 128) String destructionMethod,
        @NotNull Boolean forceIncludeBroken) {
}
