package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MaskGrid} 纯单元测试：逐格三方合并规则与四邻接连通判定，含斜角不连通边界。
 */
class MaskGridTest {

    /**
     * 按 4 行字符串构造栅格：'1' 勾选，其余字符视为 0，便于用行列位置写用例。
     */
    private static MaskGrid grid(String... rows) {
        List<Integer> cells = new ArrayList<>();
        for (String row : rows) {
            for (int col = 0; col < MaskGrid.SIZE; col++) {
                cells.add(row.charAt(col) == '1' ? 1 : 0);
            }
        }
        return MaskGrid.of(cells);
    }

    @Test
    void requiresExactlySixteenCells() {
        assertThatThrownBy(() -> MaskGrid.of(List.of(1, 0, 1)))
                .isInstanceOf(ApiException.class)
                .matches(ex -> ((ApiException) ex) != null && ((ApiException) ex).status().value() == 400);
        List<Integer> fifteen = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            fifteen.add(0);
        }
        assertThatThrownBy(() -> MaskGrid.of(fifteen)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsNonBinaryValuesAndNulls() {
        List<Integer> bad = new ArrayList<>(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        bad.set(7, 2);
        assertThatThrownBy(() -> MaskGrid.of(bad)).isInstanceOf(ApiException.class);
        List<Integer> withNull = new ArrayList<>(bad);
        withNull.set(7, null);
        assertThatThrownBy(() -> MaskGrid.of(withNull)).isInstanceOf(ApiException.class);
    }

    @Test
    void connectedShapesPassConnectivity() {
        assertThat(grid(
                "1000",
                "0000",
                "0000",
                "0000").isConnected()).isTrue();
        // 整行横连
        assertThat(grid(
                "0000",
                "0111",
                "0000",
                "0000").isConnected()).isTrue();
        // L 形四邻接
        assertThat(grid(
                "1100",
                "0100",
                "0110",
                "0000").isConnected()).isTrue();
        // 环形连通
        assertThat(grid(
                "0110",
                "0110",
                "0110",
                "0110").isConnected()).isTrue();
    }

    @Test
    void emptyAndDiagonalShapesAreDisconnected() {
        assertThat(grid(
                "0000",
                "0000",
                "0000",
                "0000").isConnected()).isFalse();
        // 斜角两格不算连接
        assertThat(grid(
                "1000",
                "0100",
                "0000",
                "0000").isConnected()).isFalse();
        // 主对角四个格：彼此只有斜角
        assertThat(grid(
                "1000",
                "0100",
                "0010",
                "0001").isConnected()).isFalse();
        // 两个分离分量，中间斜角不桥接
        assertThat(grid(
                "1100",
                "1000",
                "0001",
                "0011").isConnected()).isFalse();
    }

    @Test
    void mergeKeepsServerValueWhenCandidateUnchanged() {
        MaskGrid base = grid(
                "1100",
                "0000",
                "0000",
                "0000");
        MaskGrid current = grid(
                "1000",
                "0000",
                "0000",
                "0000");
        MaskGrid candidate = grid(
                "1100",
                "0000",
                "0000",
                "0000");
        MaskGrid merged = base.merge(current, candidate);
        assertThat(merged).isEqualTo(current);
    }

    @Test
    void mergeTakesCandidateWhenServerUnchanged() {
        MaskGrid base = grid(
                "1100",
                "0000",
                "0000",
                "0000");
        MaskGrid current = grid(
                "1100",
                "0000",
                "0000",
                "0000");
        MaskGrid candidate = grid(
                "1000",
                "0000",
                "0000",
                "0000");
        MaskGrid merged = base.merge(current, candidate);
        assertThat(merged).isEqualTo(candidate);
    }

    @Test
    void mergeAcceptsSameChangeOnBothSides() {
        MaskGrid base = grid(
                "1000",
                "1000",
                "0000",
                "0000");
        MaskGrid current = grid(
                "1100",
                "1000",
                "0000",
                "0000");
        MaskGrid candidate = grid(
                "1100",
                "1000",
                "0000",
                "0000");
        MaskGrid merged = base.merge(current, candidate);
        assertThat(merged).isEqualTo(candidate);
        assertThat(merged.isConnected()).isTrue();
    }

    @Test
    void headlineScenarioMergesToDiagonalCornersAndFailsConnectivity() {
        // 基版：(1,1)(1,2)(2,1)(2,2) 四格田字
        MaskGrid base = grid(
                "1100",
                "1100",
                "0000",
                "0000");
        // 服务器删 (2,1)：下标4 置 0，剩余 (1,1)(1,2)(2,2) 仍连通
        MaskGrid server = grid(
                "1100",
                "0100",
                "0000",
                "0000");
        // 客户端删 (1,2)：下标1 置 0，剩余 (1,1)(2,1)(2,2) 仍连通
        MaskGrid client = grid(
                "1000",
                "1100",
                "0000",
                "0000");
        assertThat(server.isConnected()).isTrue();
        assertThat(client.isConnected()).isTrue();

        MaskGrid merged = base.merge(server, client);
        // 合并后仅剩斜角两格 (1,1)=下标0 与 (2,2)=下标5
        assertThat(merged.cells()).isEqualTo(grid(
                "1000",
                "0100",
                "0000",
                "0000").cells());
        assertThat(merged.isConnected()).isFalse();
    }

    @Test
    void independentAdditionsMergeIntoOneConnectedGrid() {
        MaskGrid base = grid(
                "1100",
                "1100",
                "0000",
                "0000");
        MaskGrid current = grid(
                "1110",
                "1100",
                "0000",
                "0000");
        MaskGrid candidate = grid(
                "1100",
                "1110",
                "0000",
                "0000");
        MaskGrid merged = base.merge(current, candidate);
        assertThat(merged).isEqualTo(grid(
                "1110",
                "1110",
                "0000",
                "0000"));
        assertThat(merged.isConnected()).isTrue();
    }
}
