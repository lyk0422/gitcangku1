package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 组合包分批归还请求：提交包内一个非空子集及各件借出时冻结的 sealVersion。
 * 接收人与复核人必须不同且都具备案件权限。重复证物、已归还证物、非本包证物、
 * 封条版本不符或任一件失败，整批不落账。
 *
 * @param commandKey 幂等命令键
 * @param receiverId 接收人，必须具备案件权限且与复核人不同
 * @param reviewerId 复核人，必须具备案件权限且与接收人不同
 * @param note       批次备注，可空
 * @param items      本批归还的非空证物子集（1~20 件）及其冻结封条版本
 */
public record PackageReturnRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String receiverId,
        @NotBlank @Size(max = 64) String reviewerId,
        @Size(max = 512) String note,
        @NotEmpty @Size(min = 1, max = 20) @Valid List<ReturnItemRequest> items) {

    /**
     * 分批归还单件请求。
     *
     * @param evidenceKey 证物业务键
     * @param sealVersion 借出时冻结的封条版本，须与 package_item.frozen_seal_version 相等
     */
    public record ReturnItemRequest(
            @NotBlank @Size(max = 64) String evidenceKey,
            @NotNull Long sealVersion) {
    }
}
