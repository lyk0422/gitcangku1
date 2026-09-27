package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos.AnchorMappingInput;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.AnchorRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 译文修订锚点映射校验单元测试：无需数据库，直接构造锚点行，
 * 覆盖保留、映射迁移、缺失、重复、未知锚点、文本不一致与区间交叉等 422 分支。
 */
class CitationServiceRevisionTest {

    private final CitationService service =
            new CitationService(mock(com.example.starter.translation.repo.TranslationRepository.class),
                    Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneOffset.UTC));

    private AnchorRow anchor(long id, int start, int end, String quote) {
        return new AnchorRow(id, "s1", "en", "key" + id, "KEY-" + id, start, end, quote,
                1, "reason", "alice", Instant.parse("2026-09-27T09:00:00Z"),
                "ACTIVE", null, null, null, null, null);
    }

    @Test
    @DisplayName("区间与文本均未变动：无需映射，锚点自动保留")
    void unchangedKeptWithoutMapping() {
        List<AnchorRow> anchors = List.of(anchor(1, 0, 5, "hello"));
        List<CitationService.MovedAnchor> moved =
                service.validateRevision("s1", "en", "hello world", anchors, null);
        assertThat(moved).isEmpty();
    }

    @Test
    @DisplayName("区间前移但引用文本一致：提供映射后迁移成功")
    void movedWithValidMapping() {
        List<AnchorRow> anchors = List.of(anchor(1, 0, 5, "hello"));
        List<CitationService.MovedAnchor> moved = service.validateRevision("s1", "en", "well hello again",
                anchors, List.of(new AnchorMappingInput(1, 5, 10)));
        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).startOffset()).isEqualTo(5);
        assertThat(moved.get(0).endOffset()).isEqualTo(10);
    }

    @Test
    @DisplayName("锚点区间随文本移动但未提供映射：422 且错误含要求值、实际值、差额")
    void missingMapping() {
        List<AnchorRow> anchors = List.of(anchor(1, 0, 5, "hello"));
        assertThatThrownBy(() -> service.validateRevision("s1", "en", "well hello", anchors, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("锚点映射缺失")
                .hasMessageContaining("要求映射数 1")
                .hasMessageContaining("差额 1");
    }

    @Test
    @DisplayName("同一锚点在提交中映射两次：422 重复映射")
    void duplicateMapping() {
        List<AnchorRow> anchors = List.of(anchor(1, 0, 5, "hello"));
        List<AnchorMappingInput> mappings = List.of(
                new AnchorMappingInput(1, 5, 10), new AnchorMappingInput(1, 6, 11));
        assertThatThrownBy(() -> service.validateRevision("s1", "en", "well hello", anchors, mappings))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("锚点映射重复");
    }

    @Test
    @DisplayName("映射指向不存在的锚点：422 未知锚点")
    void unknownMappingTarget() {
        List<AnchorRow> anchors = List.of(anchor(1, 0, 5, "hello"));
        assertThatThrownBy(() -> service.validateRevision("s1", "en", "hello world", anchors,
                List.of(new AnchorMappingInput(99, 6, 11))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未知或已解除锚点");
    }

    @Test
    @DisplayName("迁移后区间内文本与原引用不一致：422 文本不一致并展示实际/要求文本")
    void quoteTextMismatch() {
        List<AnchorRow> anchors = List.of(anchor(1, 0, 5, "hello"));
        assertThatThrownBy(() -> service.validateRevision("s1", "en", "hellX world", anchors,
                List.of(new AnchorMappingInput(1, 0, 5))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("引用文本不一致")
                .hasMessageContaining("hellX");
    }

    @Test
    @DisplayName("迁移区间越出新译文长度：422 含实际长度与差额")
    void movedRangeOutOfBounds() {
        List<AnchorRow> anchors = List.of(anchor(1, 0, 5, "hello"));
        assertThatThrownBy(() -> service.validateRevision("s1", "en", "hello", anchors,
                List.of(new AnchorMappingInput(1, 2, 9))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("迁移区间越界")
                .hasMessageContaining("新译文码点长度 5")
                .hasMessageContaining("差额 4");
    }

    @Test
    @DisplayName("两个锚点迁移后区间交叉：422；端点相接允许通过")
    void movedRangesCross() {
        // 旧文 "aaaXaaa"：两锚点 quote 均为 "aaa"，区间 [0,3) 与 [4,7) 不交叉
        List<AnchorRow> anchors = List.of(
                anchor(1, 0, 3, "aaa"), anchor(2, 4, 7, "aaa"));
        // 新文 "aaaaa"：[0,3)="aaa" 与 [1,4)="aaa" 文本都一致但区间交叉
        assertThatThrownBy(() -> service.validateRevision("s1", "en", "aaaaa", anchors,
                List.of(new AnchorMappingInput(1, 0, 3), new AnchorMappingInput(2, 1, 4))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("迁移后锚点区间交叉");

        // 端点相接（[0,3) 与 [3,6)）不交叉，可通过
        List<CitationService.MovedAnchor> moved = service.validateRevision("s1", "en", "aaaaaa",
                anchors, List.of(new AnchorMappingInput(1, 0, 3), new AnchorMappingInput(2, 3, 6)));
        assertThat(moved).hasSize(2);
    }

    @Test
    @DisplayName("迁移锚点与未迁移锚点交叉：422")
    void movedCrossesUnmoved() {
        // 旧文 "helloXellZ" 长度 10：锚点 1 引用 "hello"[0,5) 保持原位；
        // 锚点 2 引用 "ell"[6,9)，新文 "hello world" 中映射到 [1,4)（文本仍为 ell）但与锚点 1 交叉
        List<AnchorRow> anchors = List.of(
                anchor(1, 0, 5, "hello"), anchor(2, 6, 9, "ell"));
        assertThatThrownBy(() -> service.validateRevision("s1", "en", "hello world", anchors,
                List.of(new AnchorMappingInput(2, 1, 4))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("与未迁移锚点交叉");
    }
}
