package com.example.starter.cue.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.cue.dto.CuePointRequest;
import com.example.starter.cue.exception.CueValidationException;
import org.springframework.stereotype.Component;

/**
 * 提示点整版本校验：任何无效时长、重复序号或不连续序号都使整个版本不可创建/冻结，
 * 不允许只丢弃坏提示点。该校验为纯逻辑，不依赖数据库，便于单元测试。
 */
@Component
public class CuePointValidator {

    /** 提示点持续时长下限（含），单位：秒。 */
    public static final int MIN_DURATION_SECONDS = 1;

    /** 提示点持续时长上限（含），单位：秒。 */
    public static final int MAX_DURATION_SECONDS = 600;

    /**
     * 校验整版本提示点；合法时返回按序号升序排列的新列表，非法时抛出携带全部错误的异常。
     *
     * @param cues 待校验提示点，至少一条
     * @return 按序号升序排列的提示点列表
     * @throws CueValidationException 存在任一非法项时抛出，包含全部错误原因
     */
    public List<CuePointRequest> validate(List<CuePointRequest> cues) {
        List<String> errors = new ArrayList<>();
        if (cues == null || cues.isEmpty()) {
            throw new CueValidationException(List.of("提示点列表不能为空"));
        }

        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < cues.size(); i++) {
            CuePointRequest cue = cues.get(i);
            String label = "第" + (i + 1) + "个提示点";
            if (cue == null) {
                errors.add(label + "不能为空");
                continue;
            }
            if (cue.seqNo() == null || cue.seqNo() < 1) {
                errors.add(label + "序号必须为不小于 1 的正整数");
            } else if (!seen.add(cue.seqNo())) {
                errors.add(label + "序号 " + cue.seqNo() + " 重复");
            }
            if (cue.cueType() == null || cue.cueType().isBlank()) {
                errors.add(label + "类型不能为空");
            }
            if (cue.triggerWord() == null || cue.triggerWord().isBlank()) {
                errors.add(label + "触发口令不能为空");
            }
            if (cue.durationSeconds() == null
                    || cue.durationSeconds() < MIN_DURATION_SECONDS
                    || cue.durationSeconds() > MAX_DURATION_SECONDS) {
                errors.add(label + "（序号" + cue.seqNo() + "）持续秒数必须在 "
                        + MIN_DURATION_SECONDS + " 至 " + MAX_DURATION_SECONDS + " 之间");
            }
        }

        if (errors.isEmpty()) {
            List<CuePointRequest> sorted = cues.stream()
                    .sorted(Comparator.comparingInt(CuePointRequest::seqNo))
                    .toList();
            for (int i = 0; i < sorted.size(); i++) {
                int expected = i + 1;
                int actual = sorted.get(i).seqNo();
                if (actual != expected) {
                    errors.add("提示点序号必须从 1 开始连续递增，期望序号 " + expected + " 但遇到 " + actual);
                    break;
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new CueValidationException(errors);
        }
        return cues.stream()
                .sorted(Comparator.comparingInt(CuePointRequest::seqNo))
                .toList();
    }
}
