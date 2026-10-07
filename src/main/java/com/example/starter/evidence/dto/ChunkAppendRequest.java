package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 追加块请求。块可乱序到达；解码后长度 1 至 4096 字节，范围不得越过声明的总长度。
 * 完全相同范围且解码字节相同返回已有块，任意其他重叠返回 409。
 *
 * @param commandKey      幂等命令键（全局 requestId）
 * @param offset          块起始偏移（字节），非负
 * @param dataBase64      Base64 编码的块字节（合成数据）；等价编码按解码字节视为同一内容
 * @param expectedVersion 期望的记录版本，与当前版本不一致返回 409
 */
public record ChunkAppendRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull @Min(0) Integer offset,
        @NotBlank @Size(max = 8192) String dataBase64,
        @NotNull @Min(0) Integer expectedVersion) {
}
