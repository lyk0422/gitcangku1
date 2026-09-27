package com.example.starter.consent.dto;

import java.util.List;
import java.util.Set;

import com.example.starter.consent.Purpose;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 跨域查询请求：一次跨对象查询指定允许区域集合；仅当授权有效、epoch 一致且全部对象
 * 驻留区域均在集合内时返回数据，任一对象不满足则整次失败（403/410），不返回部分结果。
 * 成功时生成冻结的合规快照。
 *
 * @param requestId      幂等请求标识；同参重放返回首次完整快照，异参 409，失败不占键
 * @param allowedRegions 允许的区域代码集合，非空
 * @param objects        待查询对象列表，非空
 */
public record CrossQueryRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty Set<@NotBlank @Size(max = 64) String> allowedRegions,
        @NotEmpty List<@Valid ObjectRef> objects) {

    /**
     * 查询对象引用。
     *
     * @param subjectKey 主体标识（合成字符串）
     * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
     * @param recordKey  记录键
     * @param epoch      期望的授权代次；NULL 表示使用当前最新代次。指定后若该代次已撤回
     *                   或已被迁移取代则整次查询 410
     */
    public record ObjectRef(
            @NotBlank @Size(max = 128) String subjectKey,
            @NotNull Purpose purpose,
            @NotBlank @Size(max = 128) String recordKey,
            @Min(1) Integer epoch) {
    }
}
