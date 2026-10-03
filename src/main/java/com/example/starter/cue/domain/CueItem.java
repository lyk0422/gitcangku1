package com.example.starter.cue.domain;

/**
 * 版本内的提示点明细。
 *
 * @param id              提示点主键
 * @param versionId       所属版本 ID
 * @param seq             场次内递增序号，从 1 开始，版本内唯一
 * @param cueType         提示点类型：LIGHT 灯光 / SOUND 音效
 * @param triggerPhrase   触发口令
 * @param durationSeconds 持续秒数，冻结时校验必须为 1 至 600
 */
public record CueItem(long id, long versionId, int seq, String cueType,
                      String triggerPhrase, int durationSeconds) {

    /** 灯光提示点类型。 */
    public static final String TYPE_LIGHT = "LIGHT";
    /** 音效提示点类型。 */
    public static final String TYPE_SOUND = "SOUND";
    /** 持续秒数下限（含）。 */
    public static final int MIN_DURATION_SECONDS = 1;
    /** 持续秒数上限（含）。 */
    public static final int MAX_DURATION_SECONDS = 600;
}