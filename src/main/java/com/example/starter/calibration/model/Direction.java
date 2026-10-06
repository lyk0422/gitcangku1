package com.example.starter.calibration.model;

/**
 * 读数变化方向（迟滞校准）。
 */
public enum Direction {

    /** 上升：读数较上一条增大，或首条显式声明上升；使用上升偏移。 */
    UP,

    /** 下降：读数较上一条减小，或首条显式声明下降；使用下降偏移。 */
    DOWN
}
