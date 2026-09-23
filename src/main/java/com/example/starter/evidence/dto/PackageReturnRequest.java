package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Comparator;
import java.util.List;

/**
 * 组合包分批归还请求。每批提交包内一个非空子集、各件借出时冻结的 sealVersion、
 * 接收人和复核人；两人必须不同且都具备案件权限。
 *
 * @param requestId  幂等请求键（子集换序视为同参）
 * @param receiverId 接收人，须具备案件权限且与复核人不同
 * @param reviewerId 复核人，须具备案件权限且与接收人不同
 * @param items      本批归还子集（非空，不允许重复证物）
 */
public record PackageReturnRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String receiverId,
        @NotBlank @Size(max = 64) String reviewerId,
        @NotNull @Size(min = 1, max = 20) List<@Valid Item> items) {

    /**
     * 本批归还的单件证物及冻结封条版本。
     *
     * @param evidenceKey 证物业务键，须属于本组合包
     * @param sealVersion 借出时冻结的封条版本，须与包内明细一致
     */
    public record Item(
            @NotBlank @Size(max = 64) String evidenceKey,
            long sealVersion) {
    }

    /**
     * 规范化副本：items 按 evidenceKey 排序，保证子集换序的请求指纹一致。
     */
    public PackageReturnRequest canonical() {
        List<Item> sorted = items.stream()
                .sorted(Comparator.comparing(Item::evidenceKey))
                .toList();
        return new PackageReturnRequest(requestId, receiverId, reviewerId, sorted);
    }
}
