package com.example.starter.observation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 固定 4x4 二值栅格值对象：16 个格值按行优先排列（下标 = 行 * 4 + 列），取值只能为 0 或 1。
 *
 * <p>逐格三方合并规则：候选未改（等于基版）取当前；当前未改或两边改为相同值取候选；
 * 二值输入下不存在“同格改值冲突”（0 与 1 互改时任一侧未改或两边同改必然成立之一）。
 *
 * <p>连通性要求：所有值为 1 的格必须通过上、下、左、右四邻接形成恰好一个连通分量；
 * 仅斜角相邻不算连接。全 0 栅格不合法（最终所有 1 必须非空）。
 *
 * @param cells 16 个格值，按行优先，元素只能为 0 或 1
 */
public record MaskGrid(List<Integer> cells) {

    /**
     * 栅格边长，固定 4。
     */
    public static final int SIZE = 4;

    /**
     * 格值总数：16。
     */
    public static final int CELL_COUNT = SIZE * SIZE;

    public MaskGrid {
        cells = cells == null ? null : List.copyOf(cells);
    }

    /**
     * 构造并校验格值数量与取值；非法时抛 400。
     */
    public static MaskGrid of(List<Integer> cells) {
        if (cells == null || cells.size() != CELL_COUNT) {
            throw ApiException.badRequest("mask must contain exactly " + CELL_COUNT + " cells (row-major)");
        }
        for (Integer cell : cells) {
            if (cell == null || (cell != 0 && cell != 1)) {
                throw ApiException.badRequest("mask cells must be 0 or 1");
            }
        }
        return new MaskGrid(cells);
    }

    /**
     * 取指定下标的格值。
     */
    public int get(int index) {
        return cells.get(index);
    }

    /**
     * 逐格三方合并：以本栅格为基版、current 为服务器当前版、candidate 为客户端全图。
     */
    public MaskGrid merge(MaskGrid current, MaskGrid candidate) {
        List<Integer> merged = new ArrayList<>(CELL_COUNT);
        for (int i = 0; i < CELL_COUNT; i++) {
            int baseValue = cells.get(i);
            int currentValue = current.cells.get(i);
            int candidateValue = candidate.cells.get(i);
            if (candidateValue == baseValue) {
                // 候选未改该格：采用服务器值
                merged.add(currentValue);
            } else if (currentValue == baseValue || candidateValue == currentValue) {
                // 仅候选改动，或两边改为共同值：采用候选值
                merged.add(candidateValue);
            } else {
                // 二值域理论不可达：两边从同一基版改成不同值才会进入，防御性处理
                throw new IllegalStateException("binary cell conflict at index " + i);
            }
        }
        return new MaskGrid(merged);
    }

    /**
     * 所有 1 是否非空且通过上下左右四邻接形成一个连通分量；斜角相邻不算连接。
     */
    public boolean isConnected() {
        int start = -1;
        for (int i = 0; i < CELL_COUNT; i++) {
            if (cells.get(i) == 1) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            // 全 0：没有任何勾选格，不满足“所有 1 必须非空”
            return false;
        }
        boolean[] visited = new boolean[CELL_COUNT];
        flood(start, visited);
        for (int i = 0; i < CELL_COUNT; i++) {
            if (cells.get(i) == 1 && !visited[i]) {
                return false;
            }
        }
        return true;
    }

    private void flood(int index, boolean[] visited) {
        visited[index] = true;
        int row = index / SIZE;
        int col = index % SIZE;
        if (row > 0) {
            visitNeighbor(index - SIZE, visited);
        }
        if (row < SIZE - 1) {
            visitNeighbor(index + SIZE, visited);
        }
        if (col > 0) {
            visitNeighbor(index - 1, visited);
        }
        if (col < SIZE - 1) {
            visitNeighbor(index + 1, visited);
        }
    }

    private void visitNeighbor(int neighbor, boolean[] visited) {
        if (!visited[neighbor] && cells.get(neighbor) == 1) {
            flood(neighbor, visited);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MaskGrid maskGrid)) {
            return false;
        }
        return Objects.equals(cells, maskGrid.cells);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(cells.toArray());
    }
}
