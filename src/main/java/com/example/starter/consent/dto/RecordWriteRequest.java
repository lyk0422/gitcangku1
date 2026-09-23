package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：主体直写或处理方沿委托链写入。
 *
 * <p>主体直写时 {@code callerKey} 等于 {@code subjectKey} 且 {@code delegationPath} 为空；
 * 处理方写入时 {@code callerKey} 为处理方标识，{@code delegationPath} 为从主体连续到调用方的
 * 有序边版本引用，任一边缺失、过期、版本不符或路径不是最短有效路径均拒绝写入。
 *
 * @param requestId      幂等请求标识
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param callerKey      实际写入方标识：主体本身或末端处理方
 * @param recordKey      记录键，同一代内唯一
 * @param payload        记录内容（合成字符串）
 * @param delegationPath 委托边版本引用列表（主体→处理方有序），主体直写为空
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String callerKey,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @NotNull @Valid List<EdgeVersionInput> delegationPath) {
}
