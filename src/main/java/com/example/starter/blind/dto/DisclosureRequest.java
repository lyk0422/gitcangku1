package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 泄露披露登记请求：已获知处理代码的操作者登记自己向哪些操作者直接披露了某参与者的处理代码。
 *
 * @param exposureKey      披露凭证，全局唯一，非空，最长 64
 * @param targetActorIds   直接披露接收人编号列表，1~20 名，去重后仍须在 1~20 之间
 */
public record DisclosureRequest(
        @NotBlank(message = "exposureKey 不能为空")
        @Size(max = 64, message = "exposureKey 最长 64 字符")
        String exposureKey,
        List<@NotBlank(message = "targetActorId 不能为空")
        @Size(max = 64, message = "targetActorId 最长 64 字符") String> targetActorIds
) {
}
