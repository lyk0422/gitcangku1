package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 补块请求。块可乱序到达；偏移非负，dataBase64 为原始字节的标准 Base64 编码。
 * 解码后长度须为 1..4096，且 offset+长度 不得越过接收记录的总长度。
 *
 * @param requestId       全局请求幂等键（非空，全局唯一）
 * @param offset          块起始偏移（字节，非负）
 * @param dataBase64      块原始字节的标准 Base64；身份比较按解码字节，等价编码不形成不同内容
 * @param expectedVersion 期望版本（当前已接收块数），可空；非空且不匹配返回 409
 */
public record ChunkUploadRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(0) Integer offset,
        @NotBlank @Size(max = 8192) String dataBase64,
        @Min(0) Integer expectedVersion) {
}
