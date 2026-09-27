package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 引文锚点纯规则单元测试：标识规范化、左闭右开区间、交叉检测、
 * 引用文本保留与一一映射的缺失、重复、越界、文本不一致等分支。
 */
class AnchorSupportTest {

    private static final AnchorSupport.Range R_FIRST = new AnchorSupport.Range(0, 2);
    private static final AnchorSupport.Range R_TOUCH = new AnchorSupport.Range(2, 4);
    private static final AnchorSupport.Range R_CROSS = new AnchorSupport.Range(1, 3);

    @Test
    @DisplayName("引用标识规范化：去首尾空白、小写；空标识 422")
    void normalizeCitationKey() {
        assertThat(AnchorSupport.normalizeCitationKey("  Cite-Key  ")).isEqualTo("cite-key");
        assertThatThrownBy(() -> AnchorSupport.normalizeCitationKey("   "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(422));
        assertThatThrownBy(() -> AnchorSupport.normalizeCitationKey(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("区间校验：左闭右开、非负、非空、不越界；end 等于长度合法")
    void validateRangeBoundaries() {
        assertThat(AnchorSupport.validateRange(0, 4, 4)).isNull();
        assertThat(AnchorSupport.validateRange(-1, 4, 4)).isNotNull();
        assertThat(AnchorSupport.validateRange(2, 2, 5)).isNotNull();
        assertThat(AnchorSupport.validateRange(3, 2, 5)).isNotNull();
        assertThat(AnchorSupport.validateRange(0, 5, 4)).isNotNull();
    }

    @Test
    @DisplayName("交叉检测：端点相接允许；任何重叠 422")
    void noOverlap() {
        AnchorSupport.assertNoOverlap(List.of(R_FIRST, R_TOUCH));
        assertThatThrownBy(() -> AnchorSupport.assertNoOverlap(List.of(R_FIRST, R_CROSS)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AnchorSupport.assertNoOverlap(List.of(
                new AnchorSupport.Range(0, 5), new AnchorSupport.Range(2, 3))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("无锚点时不允许提供映射；无锚点无映射直接通过")
    void noAnchors() {
        assertThat(AnchorSupport.resolveAndAssert("abc", "xyz", List.of(), List.of())).isEmpty();
        AnchorSupport.Migration ghost = new AnchorSupport.Migration("ghost", new AnchorSupport.Range(0, 1));
        assertThatThrownBy(() -> AnchorSupport.resolveAndAssert("abc", "xyz", List.of(), List.of(ghost)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("区间随文本移动但未提供映射：沿用旧区间切片文本不一致，422")
    void missingMappingWhenTextMoved() {
        // 旧文本 "abCITEcd"，锚点 [2,6)="CITE"；新文本 "xCITEx" 中该文本位于 [1,5)
        List<AnchorSupport.OldAnchor> anchors = List.of(new AnchorSupport.OldAnchor("cite", 2, 6));
        assertThatThrownBy(() -> AnchorSupport.resolveAndAssert("abCITEcd", "xCITEx", anchors, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("引用文本不一致");
    }

    @Test
    @DisplayName("提供一一映射后迁移成功，返回新区间；两个锚点交换位置同样合法（端点相接）")
    void mappingMovesAndSwaps() {
        List<AnchorSupport.OldAnchor> one = List.of(new AnchorSupport.OldAnchor("cite", 2, 6));
        List<AnchorSupport.Range> resolved = AnchorSupport.resolveAndAssert(
                "abCITEcd", "xCITEx", one,
                List.of(new AnchorSupport.Migration("cite", new AnchorSupport.Range(1, 5))));
        assertThat(resolved).containsExactly(new AnchorSupport.Range(1, 5));

        List<AnchorSupport.OldAnchor> two = List.of(
                new AnchorSupport.OldAnchor("aa", 0, 2), new AnchorSupport.OldAnchor("bb", 2, 4));
        List<AnchorSupport.Range> swapped = AnchorSupport.resolveAndAssert("AABB", "BBAA", two, List.of(
                new AnchorSupport.Migration("aa", new AnchorSupport.Range(2, 4)),
                new AnchorSupport.Migration("bb", new AnchorSupport.Range(0, 2))));
        assertThat(swapped).containsExactly(new AnchorSupport.Range(2, 4), new AnchorSupport.Range(0, 2));
    }

    @Test
    @DisplayName("映射目标文本与固化引用不一致：422 且消息含实际值、要求值与差额")
    void mappingTextMismatch() {
        // 旧文本 "aa[CITE]bb" 锚点 [2,8) 固化 "[CITE]"；新文本 "xx[XXXX]yy" 同区间切片为 "[XXXX]"
        List<AnchorSupport.OldAnchor> anchors = List.of(new AnchorSupport.OldAnchor("cite", 2, 8));
        assertThatThrownBy(() -> AnchorSupport.resolveAndAssert("aa[CITE]bb", "xx[XXXX]yy", anchors,
                List.of(new AnchorSupport.Migration("cite", new AnchorSupport.Range(2, 8)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("[CITE]")
                .hasMessageContaining("[XXXX]")
                .hasMessageContaining("差额");
    }

    @Test
    @DisplayName("映射重复、映射指向不存在锚点、新区间越界：分别 422")
    void mappingErrors() {
        List<AnchorSupport.OldAnchor> anchors = List.of(new AnchorSupport.OldAnchor("cite", 2, 6));
        assertThatThrownBy(() -> AnchorSupport.resolveAndAssert("abCITEcd", "xCITEx", anchors, List.of(
                new AnchorSupport.Migration("cite", new AnchorSupport.Range(1, 5)),
                new AnchorSupport.Migration("CITE", new AnchorSupport.Range(1, 5)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重复");

        assertThatThrownBy(() -> AnchorSupport.resolveAndAssert("abCITEcd", "xCITEx", anchors,
                List.of(new AnchorSupport.Migration("other", new AnchorSupport.Range(1, 5)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不存在");

        assertThatThrownBy(() -> AnchorSupport.resolveAndAssert("abCITEcd", "xCITEx", anchors,
                List.of(new AnchorSupport.Migration("cite", new AnchorSupport.Range(4, 9)))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超出");
    }

    @Test
    @DisplayName("两个锚点迁移后区间交叉：422（两锚点固化文本相同，故交叉与文本一致可同时成立）")
    void mappedRangesOverlap() {
        List<AnchorSupport.OldAnchor> two = List.of(
                new AnchorSupport.OldAnchor("aa", 0, 2), new AnchorSupport.OldAnchor("bb", 2, 4));
        // 旧文本 "AAAA"：两个锚点固化文本均为 "AA"；新文本 "xAAAAy" 中映射到 [1,3) 与 [2,4)，文本均一致但区间重叠
        List<AnchorSupport.Migration> overlapping = List.of(
                new AnchorSupport.Migration("aa", new AnchorSupport.Range(1, 3)),
                new AnchorSupport.Migration("bb", new AnchorSupport.Range(2, 4)));
        assertThatThrownBy(() -> AnchorSupport.resolveAndAssert("AAAA", "xAAAAy", two, overlapping))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("交叉");
    }
}
