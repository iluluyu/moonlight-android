package com.limelight.binding.input.touch;

/** Converts continuous one-axis travel into discrete selection steps. */
public final class TrackpadSelectionAccumulator {
    private final float firstStepDistance;
    private final float stepDistance;
    private final int maxStepsPerSample;

    private boolean active;
    private boolean firstStepEmitted;
    private float lastPosition;
    private float accumulatedDistance;

    public TrackpadSelectionAccumulator(float stepDistance, int maxStepsPerSample) {
        this(stepDistance, stepDistance, maxStepsPerSample);
    }

    /** Creates an accumulator with extra travel required only for its first step. */
    public TrackpadSelectionAccumulator(float firstStepDistance, float stepDistance,
            int maxStepsPerSample) {
        if (firstStepDistance <= 0 || stepDistance <= 0) {
            throw new IllegalArgumentException("step distances must be positive");
        }
        if (maxStepsPerSample <= 0) {
            throw new IllegalArgumentException("maxStepsPerSample must be positive");
        }
        this.firstStepDistance = firstStepDistance;
        this.stepDistance = stepDistance;
        this.maxStepsPerSample = maxStepsPerSample;
    }

    public void start(float position) {
        active = true;
        firstStepEmitted = false;
        lastPosition = position;
        accumulatedDistance = 0;
    }

    /** Returns negative steps for decreasing coordinates and positive steps for increasing ones. */
    public int move(float position) {
        if (!active) {
            return 0;
        }

        accumulatedDistance += position - lastPosition;
        lastPosition = position;

        int steps = 0;
        float currentStepDistance = firstStepEmitted ? stepDistance : firstStepDistance;
        while (accumulatedDistance >= currentStepDistance && steps < maxStepsPerSample) {
            accumulatedDistance -= currentStepDistance;
            steps++;
            firstStepEmitted = true;
            currentStepDistance = stepDistance;
        }
        while (accumulatedDistance <= -currentStepDistance && steps > -maxStepsPerSample) {
            accumulatedDistance += currentStepDistance;
            steps--;
            firstStepEmitted = true;
            currentStepDistance = stepDistance;
        }
        return steps;
    }

    public boolean isActive() {
        return active;
    }

    /** Discards pending travel without restarting the first-step hysteresis. */
    public void rebase(float position) {
        if (!active) {
            return;
        }
        lastPosition = position;
        accumulatedDistance = 0;
    }

    public void finish() {
        active = false;
        firstStepEmitted = false;
        accumulatedDistance = 0;
    }
}
