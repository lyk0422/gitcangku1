package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 数据清除请求：清除指定 epoch 下单个对象的内容；清除后 payload 为 null，历史与已生成快照不改写。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH / PERSONALIZATION
 * @param epoch      对象所属授权代次
 * @param recordKey  待清除对象的记录键
 */
public record EraseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Positive Integer epoch,
        @NotBlank @Size(max = 128) String recordKey) {
}
