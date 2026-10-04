package com.example.starter.translation.service;

/**
 * 批注锚点迁移：定点编辑后按 Unicode 码点区间规则计算批注新位置或孤立。
 * 所有偏移均按码点从 0 计数、左闭右开；纯函数，不依赖数据库，便于独立单元测试。
 */
public final class AnchorMigration {

    private AnchorMigration() {
    }

    /**
     * 迁移结果。
     *
     * @param start    迁移后锚点起点（孤立时保留原起点，仅供追溯）
     * @param end      迁移后锚点终点（孤立时保留原终点，仅供追溯）
     * @param orphaned 是否孤立：编辑区间与批注相交，或纯插入严格落在批注内部
     */
    public record Result(int start, int end, boolean orphaned) {
    }

    /**
     * 计算批注 [anchorStart, anchorEnd) 在定点编辑后的位置。
     *
     * @param anchorStart    批注起点（码点）
     * @param anchorEnd      批注终点（码点）
     * @param editStart      被替换区间起点（码点），纯插入时等于 editEnd
     * @param editEnd        被替换区间终点（码点）
     * @param insertedLength 插入文本的码点数
     */
    public static Result migrate(int anchorStart, int anchorEnd,
                                 int editStart, int editEnd, int insertedLength) {
        int deleted = editEnd - editStart;
        if (deleted > 0 && anchorStart < editEnd && anchorEnd > editStart) {
            // 与非空替换区间相交：孤立
            return new Result(anchorStart, anchorEnd, true);
        }
        if (deleted == 0 && anchorStart < editStart && editStart < anchorEnd) {
            // 纯插入严格落在批注内部：孤立
            return new Result(anchorStart, anchorEnd, true);
        }
        if (editStart >= anchorEnd) {
            // 编辑发生在批注终点或更晚：位置不变
            return new Result(anchorStart, anchorEnd, false);
        }
        // 编辑发生在批注起点或更早：按新增减删除码点数平移
        int delta = insertedLength - deleted;
        return new Result(anchorStart + delta, anchorEnd + delta, false);
    }

    /** 文本码点长度。 */
    public static int codePointCount(String text) {
        return text.codePointCount(0, text.length());
    }

    /** 按码点区间 [start, end) 截取子串。 */
    public static String substringByCodePoints(String text, int start, int end) {
        int from = text.offsetByCodePoints(0, start);
        int to = text.offsetByCodePoints(0, end);
        return text.substring(from, to);
    }
}