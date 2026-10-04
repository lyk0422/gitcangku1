package com.example.starter.translation;

import com.example.starter.translation.service.AnchorMigration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锚点迁移规则纯单元测试：区间按 Unicode 码点从 0 计数、左闭右开。
 * 合成样例：A😀BC 按 4 个码点计，批注 BC 为 [2, 4)。
 */
class AnchorMigrationTest {

    @Test
    @DisplayName("合成样例：位置 1 插入 X 后批注 [2,4) 平移为 [3,5)，再删除 B 则孤立")
    void syntheticScenario() {
        String content = "A😀BC";
        assertThat(AnchorMigration.codePointCount(content)).isEqualTo(4);
        assertThat(AnchorMigration.substringByCodePoints(content, 2, 4)).isEqualTo("BC");

        // 在位置 1 插入 X（纯插入，发生在批注起点或更早）：平移
        AnchorMigration.Result shifted = AnchorMigration.migrate(2, 4, 1, 1, 1);
        assertThat(shifted.orphaned()).isFalse();
        assertThat(shifted.start()).isEqualTo(3);
        assertThat(shifted.end()).isEqualTo(5);

        // 删除 B：编辑区间 [3,4) 与批注 [3,5) 相交：孤立
        AnchorMigration.Result orphaned = AnchorMigration.migrate(3, 5, 3, 4, 0);
        assertThat(orphaned.orphaned()).isTrue();
    }

    @Test
    @DisplayName("纯插入：起点或更早平移，严格内部孤立，终点或更晚不变")
    void pureInsertionRules() {
        // 批注 [2, 4)，插入位置 0/2（起点或更早）：平移 +1
        assertThat(AnchorMigration.migrate(2, 4, 0, 0, 1).start()).isEqualTo(3);
        AnchorMigration.Result atStart = AnchorMigration.migrate(2, 4, 2, 2, 1);
        assertThat(atStart.orphaned()).isFalse();
        assertThat(atStart.start()).isEqualTo(3);
        assertThat(atStart.end()).isEqualTo(5);
        // 插入位置 3（严格内部）：孤立
        assertThat(AnchorMigration.migrate(2, 4, 3, 3, 1).orphaned()).isTrue();
        // 插入位置 4/5（终点或更晚）：不变
        AnchorMigration.Result atEnd = AnchorMigration.migrate(2, 4, 4, 4, 1);
        assertThat(atEnd.orphaned()).isFalse();
        assertThat(atEnd.start()).isEqualTo(2);
        assertThat(atEnd.end()).isEqualTo(4);
        assertThat(AnchorMigration.migrate(2, 4, 5, 5, 1).start()).isEqualTo(2);
    }

    @Test
    @DisplayName("替换与删除：相交孤立，前方不变，后方按新增减删除码点数平移")
    void replacementRules() {
        // 批注 [5, 8)，删除 [1, 3)：后方平移 -2
        AnchorMigration.Result shifted = AnchorMigration.migrate(5, 8, 1, 3, 0);
        assertThat(shifted.orphaned()).isFalse();
        assertThat(shifted.start()).isEqualTo(3);
        assertThat(shifted.end()).isEqualTo(6);
        // 替换 [1, 3) 为 4 码点：平移 +2
        assertThat(AnchorMigration.migrate(5, 8, 1, 3, 4).start()).isEqualTo(7);
        // 编辑区间与批注相交（覆盖起点、覆盖终点、包含、被包含）：均孤立
        assertThat(AnchorMigration.migrate(5, 8, 4, 6, 1).orphaned()).isTrue();
        assertThat(AnchorMigration.migrate(5, 8, 7, 9, 1).orphaned()).isTrue();
        assertThat(AnchorMigration.migrate(5, 8, 4, 9, 1).orphaned()).isTrue();
        assertThat(AnchorMigration.migrate(5, 8, 6, 7, 1).orphaned()).isTrue();
        // 编辑区间恰在批注终点开始：不相交、位置不变
        AnchorMigration.Result atEnd = AnchorMigration.migrate(5, 8, 8, 9, 1);
        assertThat(atEnd.orphaned()).isFalse();
        assertThat(atEnd.start()).isEqualTo(5);
        // 编辑区间恰在批注起点结束：不相交，批注在编辑后方，平移 -2
        AnchorMigration.Result before = AnchorMigration.migrate(5, 8, 3, 5, 0);
        assertThat(before.orphaned()).isFalse();
        assertThat(before.start()).isEqualTo(3);
        assertThat(before.end()).isEqualTo(6);
    }

    @Test
    @DisplayName("码点工具：增补字符按 1 码点计，子串按码点区间截取")
    void codePointUtilities() {
        String text = "A😀BC";
        assertThat(text.length()).isEqualTo(5);
        assertThat(AnchorMigration.codePointCount(text)).isEqualTo(4);
        assertThat(AnchorMigration.substringByCodePoints(text, 0, 1)).isEqualTo("A");
        assertThat(AnchorMigration.substringByCodePoints(text, 1, 2)).isEqualTo("😀");
        assertThat(AnchorMigration.substringByCodePoints(text, 2, 4)).isEqualTo("BC");
    }
}