package com.limelight.binding.input.touch;

public final class TrackpadGestureConfiguration {
    public enum Direction {
        LEFT,
        RIGHT,
        UP,
        DOWN
    }

    public enum Action {
        NONE,
        PREVIOUS_APP,
        NEXT_APP,
        OPEN_TASK_VIEW,
        SHOW_DESKTOP,
        RESTORE_WINDOWS,
        OPEN_NOTIFICATION_CENTER,
        PREVIOUS_DESKTOP,
        NEXT_DESKTOP
    }

    private TrackpadGestureConfiguration() {
    }

    public static Action resolve(int fingerCount, Direction direction) {
        if (fingerCount == 3) {
            switch (direction) {
                case LEFT:
                    return Action.PREVIOUS_APP;
                case RIGHT:
                    return Action.NEXT_APP;
                case UP:
                    return Action.OPEN_TASK_VIEW;
                case DOWN:
                    return Action.SHOW_DESKTOP;
                default:
                    return Action.NONE;
            }
        }

        if (fingerCount == 4) {
            switch (direction) {
                case LEFT:
                    return Action.PREVIOUS_DESKTOP;
                case RIGHT:
                    return Action.NEXT_DESKTOP;
                case UP:
                    return Action.OPEN_TASK_VIEW;
                case DOWN:
                    return Action.SHOW_DESKTOP;
                default:
                    return Action.NONE;
            }
        }

        return Action.NONE;
    }

    public static Action resolveTap(int fingerCount) {
        return fingerCount == 4 ? Action.OPEN_NOTIFICATION_CENTER : Action.NONE;
    }

    public static Action resolveFourFingerSwipe(Direction direction, boolean desktopRestorePending) {
        if (desktopRestorePending) {
            if (direction == Direction.UP) {
                return Action.RESTORE_WINDOWS;
            }
            if (direction == Direction.DOWN) {
                return Action.NONE;
            }
        }
        return resolve(4, direction);
    }
}
