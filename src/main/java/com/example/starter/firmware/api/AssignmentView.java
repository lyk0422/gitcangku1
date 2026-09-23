package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DeviceAssignment;

/**
 * 设备队列分配视图。
 */
public record AssignmentView(long campaignId, String deviceId, long cohortId,
                             int assignmentVersion, int assignmentGeneration, String installStatus) {

    public static AssignmentView of(DeviceAssignment assignment) {
        return new AssignmentView(assignment.campaignId(), assignment.deviceId(),
                assignment.cohortId(), assignment.assignmentVersion(),
                assignment.assignmentGeneration(), assignment.installStatus().name());
    }
}
