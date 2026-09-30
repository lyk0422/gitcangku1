package com.example.starter.cue.service;

import java.util.List;

import com.example.starter.cue.dto.CuePointRequest;
import com.example.starter.cue.exception.CueValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link CuePointValidator} 纯逻辑单元测试，不启动 Spring 与数据库。
 */
class CuePointValidatorTest {

    private final CuePointValidator validator = new CuePointValidator();

    private CuePointRequest cue(int seq, String type, String word, int duration) {
        return new CuePointRequest(seq, type, word, duration);
    }

    @Test
    @DisplayName("合法提示点通过校验并按序号升序返回")
    void validCuesSortedBySeq() {
        List<CuePointRequest> input = List.of(
                cue(3, "LIGHT", "三", 30),
                cue(1, "LIGHT", "一", 10),
                cue(2, "SOUND", "二", 20));

        List<CuePointRequest> result = validator.validate(input);

        assertThat(result).extracting(CuePointRequest::seqNo).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("持续秒数为 0 与 601 均非法，合法边界 1 与 600 通过")
    void durationBoundary() {
        assertThat(validator.validate(List.of(cue(1, "LIGHT", "下限", 1)))).hasSize(1);
        assertThat(validator.validate(List.of(cue(1, "LIGHT", "上限", 600)))).hasSize(1);

        CueValidationException tooSmall = catchThrowableOfType(
                () -> validator.validate(List.of(cue(1, "LIGHT", "零", 0))),
                CueValidationException.class);
        assertThat(tooSmall.getErrors()).anyMatch(e -> e.contains("持续秒数"));

        CueValidationException tooLarge = catchThrowableOfType(
                () -> validator.validate(List.of(cue(1, "LIGHT", "超大", 601))),
                CueValidationException.class);
        assertThat(tooLarge.getErrors()).anyMatch(e -> e.contains("持续秒数"));
    }

    @Test
    @DisplayName("重复序号使整个版本不可用，错误信息指出重复序号")
    void duplicateSeqRejectsWholeVersion() {
        CueValidationException ex = catchThrowableOfType(
                () -> validator.validate(List.of(
                        cue(1, "LIGHT", "一", 10),
                        cue(2, "SOUND", "二", 20),
                        cue(2, "LIGHT", "重复的二", 20))),
                CueValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("序号 2 重复"));
    }

    @Test
    @DisplayName("序号不连续（缺号）使整个版本不可冻结")
    void nonContinuousSeqRejected() {
        CueValidationException ex = catchThrowableOfType(
                () -> validator.validate(List.of(
                        cue(1, "LIGHT", "一", 10),
                        cue(3, "SOUND", "三", 30))),
                CueValidationException.class);
        assertThat(ex.getErrors()).anyMatch(e -> e.contains("连续递增"));
    }

    @Test
    @DisplayName("多个错误一次性全部返回，而不是只报第一个")
    void allErrorsCollected() {
        CueValidationException ex = catchThrowableOfType(
                () -> validator.validate(List.of(
                        cue(1, "LIGHT", "正常", 10),
                        cue(2, "SOUND", "越界", 999),
                        cue(2, " ", "也越界", 0))),
                CueValidationException.class);
        assertThat(ex.getErrors()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("空列表非法")
    void emptyListRejected() {
        assertThatThrownBy(() -> validator.validate(List.of()))
                .isInstanceOf(CueValidationException.class);
    }

    @Test
    @DisplayName("类型或触发口令为空被拒绝")
    void blankTypeOrWordRejected() {
        CueValidationException ex = catchThrowableOfType(
                () -> validator.validate(List.of(
                        new CuePointRequest(1, " ", "口令", 10),
                        new CuePointRequest(2, "SOUND", " ", 10))),
                CueValidationException.class);
        assertThat(ex.getErrors()).hasSizeGreaterThanOrEqualTo(2);
    }
}
