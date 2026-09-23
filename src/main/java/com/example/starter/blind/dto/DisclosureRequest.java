package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 泄露披露登记请求体：登记人（X-Actor-Id）作为披露源，声明自己把哪些参与者的处理代码
 * 直接披露给了哪些操作者接收人。
 *
 * @param exposureKey    调用方提供的披露键，全局唯一，重复使用拒绝
 * @param receiverActors 接收披露的操作者编号，去重后须为 1~20 名，且不得包含登记人自己
 * @param participantIds 被披露处理代码的参与者编号；登记人必须已获知（已批准揭盲或已在污染闭包内）
 */
public record DisclosureRequest(
        @NotBlank(message = "exposureKey 不能为空")
        @Size(max = 64, message = "exposureKey 最长 64 字符")
        String exposureKey,
        @NotEmpty(message = "receiverActors 不能为空")
        List<@NotBlank(message = "receiverActor 不能为空")
                @Size(max = 64, message = "receiverActor 最长 64 字符") String> receiverActors,
        @NotEmpty(message = "participantIds 不能为空")
        List<@NotBlank(message = "participantId 不能为空")
                @Size(max = 64, message = "participantId 最长 64 字符") String> participantIds
) {
}
