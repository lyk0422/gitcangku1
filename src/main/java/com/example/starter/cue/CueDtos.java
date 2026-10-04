package com.example.starter.cue;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 提示点编排相关传输对象。时长合法性（1至600秒）与序号唯一性在冻结时统一校验，
 * 因此草稿创建仅做非空校验，允许暂存不合法数据。
 */
public final class CueDtos {

    private CueDtos() {
    }

    /** 单个提示点输入：序号、类型、触发口令、持续秒数。 */
    public record CueInput(@NotNull Integer seqNo,
                           @NotBlank String cueType,
                           @NotBlank String triggerPhrase,
                           @NotNull Integer durationSeconds) {
    }

    /** 单个提示点视图。 */
    public record CueView(int seqNo, String cueType, String triggerPhrase, int durationSeconds) {
    }

    /** 创建编排版本请求。 */
    public record CreateVersionRequest(@NotEmpty @Valid List<CueInput> cues) {
    }

    /** 编排版本视图，含全部提示点；时间均为UTC。 */
    public record VersionView(long id, String playCode, int versionNo, String status,
                              Instant createdAt, Instant frozenAt, List<CueView> cues) {
    }

    /** 同一序号在两个版本间的差异。 */
    public record CueChange(int seqNo, CueView from, CueView to) {
    }

    /** 版本差异视图：新增、移除、修改三类。 */
    public record DiffView(long fromVersionId, long toVersionId,
                           List<CueView> added, List<CueView> removed, List<CueChange> modified) {
    }
}