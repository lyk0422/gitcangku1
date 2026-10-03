package com.example.starter.cue.domain;

import java.time.LocalDateTime;

/**
 * 提示点编排版本。每次编排修改产生一个新版本，版本号在剧目内递增。
 *
 * @param id         版本主键
 * @param production 剧目名称
 * @param versionNo  剧目内递增版本号，从 1 开始
 * @param status     版本状态：DRAFT 草稿 / FROZEN 已冻结，仅冻结版本可绑定场次
 * @param createdAt  创建时间（Asia/Shanghai）
 */
public record CueVersion(long id, String production, int versionNo, String status,
                         LocalDateTime createdAt) {

    /** 草稿状态，冻结前可整体校验，不允许绑定场次。 */
    public static final String STATUS_DRAFT = "DRAFT";
    /** 已冻结状态，内容不可再变，可绑定场次。 */
    public static final String STATUS_FROZEN = "FROZEN";
}