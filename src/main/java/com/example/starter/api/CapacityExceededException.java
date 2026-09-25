package com.example.starter.api;

import org.springframework.http.HttpStatus;

/**
 * 走廊容量已满：提交时刻与新预约时间窗重叠的生效预约数已达容量上限。
 * 额外携带当前占用数返回给客户端。
 */
public class CapacityExceededException extends ApiException {

    /** 提交时间窗内当前重叠的生效预约数。 */
    private final int currentOccupancy;
    /** 走廊容量上限。 */
    private final int capacity;

    public CapacityExceededException(int currentOccupancy, int capacity) {
        super(HttpStatus.TOO_MANY_REQUESTS, "CORRIDOR_CAPACITY_EXCEEDED",
                "走廊在该时段容量已满：currentOccupancy=" + currentOccupancy
                        + ", capacity=" + capacity);
        this.currentOccupancy = currentOccupancy;
        this.capacity = capacity;
    }

    public int currentOccupancy() {
        return currentOccupancy;
    }

    public int capacity() {
        return capacity;
    }
}
