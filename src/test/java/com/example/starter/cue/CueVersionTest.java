package com.example.starter.cue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 版本创建、整体校验冻结与版本差异测试。 */
class CueVersionTest extends CueTestSupport {

    @Test
    void freezeValidVersionMarksFrozen() {
        long versionId = frozenVersion(List.of(
                cue(1, CueType.LIGHT, "开幕", 30),
                cue(2, CueType.SOUND, "雷声", 5)));

        CueVersion version = cueService.requireVersion(versionId);

        assertThat(version.status()).isEqualTo(VersionStatus.FROZEN);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cue_item WHERE version_id = ?",
                Integer.class, versionId)).isEqualTo(2);
    }

    @Test
    void invalidDurationRejectsWholeVersionAndKeepsDraft() {
        long versionId = cueService.createVersion(new CueDtos.CreateVersionRequest(List.of(
                cue(1, CueType.LIGHT, "开幕", 30),
                cue(2, CueType.SOUND, "超长音效", 601),
                cue(3, CueType.LIGHT, "零秒灯光", 0))));

        assertThatThrownBy(() -> cueService.freeze(versionId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("601")
                .hasMessageContaining("0");

        // 整个版本不能冻结，坏提示点也不能被丢弃
        assertThat(cueService.requireVersion(versionId).status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cue_item WHERE version_id = ?",
                Integer.class, versionId)).isEqualTo(3);
    }

    @Test
    void duplicateSeqRejectsWholeVersion() {
        long versionId = cueService.createVersion(new CueDtos.CreateVersionRequest(List.of(
                cue(1, CueType.LIGHT, "开幕", 30),
                cue(1, CueType.SOUND, "重复序号", 10))));

        assertThatThrownBy(() -> cueService.freeze(versionId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("重复");
        assertThat(cueService.requireVersion(versionId).status()).isEqualTo(VersionStatus.DRAFT);
    }

    @Test
    void diffShowsAddedRemovedAndChangedCues() {
        long v1 = frozenVersion(List.of(
                cue(1, CueType.LIGHT, "开幕", 30),
                cue(2, CueType.SOUND, "雷声", 5),
                cue(3, CueType.LIGHT, "谢幕", 20)));
        long v2 = frozenVersion(List.of(
                cue(1, CueType.LIGHT, "开幕", 30),
                cue(2, CueType.SOUND, "雷声加强", 8),
                cue(4, CueType.SOUND, "掌声", 15)));

        CueDtos.VersionDiff diff = cueService.diff(v1, v2);

        assertThat(diff.added()).extracting(CueItem::seq).containsExactly(4);
        assertThat(diff.removed()).extracting(CueItem::seq).containsExactly(3);
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.changed().get(0).from().triggerPhrase()).isEqualTo("雷声");
        assertThat(diff.changed().get(0).to().triggerPhrase()).isEqualTo("雷声加强");
    }
}