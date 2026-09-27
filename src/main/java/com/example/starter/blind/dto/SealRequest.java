package com.example.starter.blind.dto;

/**
 * 封存请求体；全部字段可选。
 *
 * @param expectedDigest 调用方持有的随机表摘要；提供时必须与服务端复算摘要一致，否则 422
 */
public record SealRequest(
        String expectedDigest
) {
}
