package com.example.starter.baggage;

/**
 * 平面舱位整数几何工具：只处理合成整数毫米尺寸与坐标。
 * 边界接触合法（边/点重合不算重叠），仅在两个矩形内部相交时判为重叠。
 * 所有乘加使用 long，防止极端坐标 x+宽 与面积累加溢出。
 */
final class Geometry {

    private Geometry() {
    }

    /** 轴对齐矩形：左下角 (x,y)，宽 w（x 轴）、高 h（y 轴），单位毫米。 */
    record Rect(long x, long y, long w, long h) {

        /** 右上角 x（不含），long 计算防止整数溢出。 */
        long right() {
            return x + w;
        }

        /** 右上角 y（不含）。 */
        long top() {
            return y + h;
        }

        /** 是否完全落在 [0,floorW] x [0,floorH] 舱底内（边界重合合法）。 */
        boolean inside(long floorW, long floorH) {
            return x >= 0 && y >= 0 && w > 0 && h > 0 && right() <= floorW && top() <= floorH;
        }

        /**
         * 两矩形内部是否重叠：仅边界接触（一条边或一个点）返回 false。
         */
        boolean overlaps(Rect other) {
            return x < other.right() && other.x < right()
                    && y < other.top() && other.y < top();
        }
    }

    /** 旋转 90 度后的 x 轴占用长度。 */
    static long placedWidth(long widthMm, long heightMm, boolean rotated) {
        return rotated ? heightMm : widthMm;
    }

    /** 旋转 90 度后的 y 轴占用长度。 */
    static long placedHeight(long widthMm, long heightMm, boolean rotated) {
        return rotated ? widthMm : heightMm;
    }

    /** 构造落位矩形。 */
    static Rect rect(long widthMm, long heightMm, boolean rotated, long x, long y) {
        return new Rect(x, y, placedWidth(widthMm, heightMm, rotated),
                placedHeight(widthMm, heightMm, rotated));
    }

    /** 矩形面积（平方毫米），long 防溢出。 */
    static long area(Rect rect) {
        return rect.w() * rect.h();
    }
}
