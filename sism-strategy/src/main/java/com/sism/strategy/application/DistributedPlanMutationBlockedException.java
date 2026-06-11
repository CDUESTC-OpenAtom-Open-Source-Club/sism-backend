package com.sism.strategy.application;

/**
 * Raised when a write attempts to mutate indicators bound to a distributed plan.
 */
public class DistributedPlanMutationBlockedException extends RuntimeException {

    public DistributedPlanMutationBlockedException(String message) {
        super(message);
    }
}
