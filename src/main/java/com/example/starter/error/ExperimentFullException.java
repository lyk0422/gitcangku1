package com.example.starter.error;

/** 422：实验席位已满，无法再接受新分配。 */
public class ExperimentFullException extends ApiException {
    public ExperimentFullException(String message) {
        super(422, message);
    }
}
