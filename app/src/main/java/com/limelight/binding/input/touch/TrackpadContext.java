package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

public class TrackpadContext implements TouchContext {
    private double pendingDeltaX = 0;
    private double pendingDeltaY = 0;
    private double relativeRemainderX = 0;
    private double relativeRemainderY = 0;
    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedScroll;
    private double distanceMoved;
    private int pointerCount;
    private boolean clickedMiddle = false;
    private int maxPointerCountInGesture;
    private boolean isClickPending;
    private boolean isDblClickPending;
    private boolean isFlicking;
    private double velocityX = 0.0;
    private double velocityY = 0.0;
    private long lastMoveTime;
    private long lastMomentumFrameTime;
    private boolean isScrollTransitioning = false;
    /** The exact button held by the delayed tap/double-tap/drag state. Pointer count
     *  can change before cancellation, so recomputing this with getMouseButtonIndex()
     *  can release a different button and leave the original one stuck remotely. */
    private byte pendingButtonDown = 0;
    /** EventTime when a second tap landed (for double-tap-and-drag hold gating). */
    private long dragArmTime = 0;
    /** Global: is the primary (left) mouse button currently held down? Shared across
     *  both trackpad contexts AND the hardware press-to-click path in Game, so that
     *  movement while the button is held drags instead of scrolling. */
    public static volatile boolean primaryButtonDown = false;

    private final NvConnection conn;
    private final int actionIndex;
    private final Handler handler;

    private boolean swapAxis = false;
    private float sensitivityX = 1;
    private float sensitivityY = 1;

    // Empirical starting values for the target OPPO touchpad coordinate scale.
    private static final int TAP_MOVEMENT_THRESHOLD = 10; // px per axis
    private static final int TAP_TIME_THRESHOLD = 180;    // ms
    private static final int CLICK_RELEASE_DELAY = TAP_TIME_THRESHOLD;
    private static final float SCROLL_SPEED_FACTOR_X = 1.5f;
    private static final float SCROLL_SPEED_FACTOR_Y = 2f;
    // Acceleration is based on velocity rather than per-event distance. Per-event
    // distance changes with the touchpad/reporting refresh rate, while px/ms remains
    // stable across 60/120/240 Hz input streams.
    private static final double ACCELERATION_START_SPEED = 0.8; // px/ms
    private static final double ACCELERATION_MAX_SPEED = 4.0;   // px/ms
    private static final double ACCELERATION_MAX_MULTIPLIER = 2.5;
    private static final double VELOCITY_SMOOTHING_TIME_CONSTANT_MS = 30.0;
    // Initial scroll feel-tuning value: 0.98 per 10ms gives a decay time constant of
    // roughly 500ms. Apply it by elapsed time so main-thread jitter doesn't change it.
    private static final double FLICK_FRICTION = 0.98;
    // Unit: pixels/ms. Cursor movement stops at finger lift like macOS; only content
    // scrolling has a kinetic phase.
    private static final double SCROLL_FLICK_THRESHOLD = 0.25;
    // Slow content scrolls should retain a small tail without coasting as long as a
    // deliberate flick. Blend back to the normal curve by 1 px/ms, leaving the
    // already-good fast-scroll behavior unchanged.
    private static final double SCROLL_SLOW_FRICTION = 0.965;
    private static final double SCROLL_FULL_FRICTION_SPEED = 1.0;
    private static final int MOMENTUM_FRAME_INTERVAL_MS = 10;
    private static final int FLICK_VELOCITY_DECAY_TIMEOUT_MS = 50;
    private static final int SCROLL_TRANSITION_TIMEOUT_MS = 60;
    private static final double SCROLL_SECONDARY_AXIS_RATIO = 0.35;
    /** A second tap must linger this long (without significant movement) before a
     *  double-tap-and-drag activates, so a quick "tap then move the cursor" is not
     *  mistaken for a drag (which previously left the left button stuck down). */
    private static final int DRAG_HOLD_DELAY_MS = 120;

    public TrackpadContext(NvConnection conn, int actionIndex) {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public TrackpadContext(NvConnection conn, int actionIndex, boolean swapAxis, int sensitivityX, int sensitivityY) {
        this(conn, actionIndex);
        this.swapAxis = swapAxis;
        this.sensitivityX = (float) sensitivityX / 100;
        this.sensitivityY = (float) sensitivityY / 100;
    }

    private final Runnable scrollTransitionRunnable = new Runnable() {
        @Override
        public void run() {
            isScrollTransitioning = false;
        }
    };

    private final Runnable scrollMomentumRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFlicking) {
                return;
            }

            long elapsedMs = advanceMomentumClock();
            long movementTimeMs = Math.min(elapsedMs, MOMENTUM_FRAME_INTERVAL_MS * 3L);
            double frameVelocityX = velocityX * movementTimeMs;
            double frameVelocityY = velocityY * movementTimeMs;

            sendScrollDelta(frameVelocityX, frameVelocityY);

            double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
            double friction = calculateScrollFlickFriction(speed, elapsedMs);
            velocityX *= friction;
            velocityY *= friction;

            if (Math.sqrt(velocityX * velocityX + velocityY * velocityY) * MOMENTUM_FRAME_INTERVAL_MS < 0.5) {
                isFlicking = false;
            }

            if (isFlicking) {
                handler.postDelayed(this, MOMENTUM_FRAME_INTERVAL_MS);
            }
        }
    };

    private long advanceMomentumClock() {
        long now = SystemClock.uptimeMillis();
        long elapsedMs = Math.max(1, now - lastMomentumFrameTime);
        lastMomentumFrameTime = now;
        return elapsedMs;
    }

    static double calculateAccelerationMultiplier(double distance, long elapsedMs) {
        if (elapsedMs <= 0 || distance <= 0) {
            return 1.0;
        }

        double speed = distance / elapsedMs;
        if (speed <= ACCELERATION_START_SPEED) {
            return 1.0;
        }
        if (speed >= ACCELERATION_MAX_SPEED) {
            return ACCELERATION_MAX_MULTIPLIER;
        }

        // A gentle logarithmic ramp preserves low-speed precision while still
        // allowing a quick stroke to cross the host display efficiently.
        return 1.0 + (ACCELERATION_MAX_MULTIPLIER - 1.0)
                * Math.log(speed / ACCELERATION_START_SPEED)
                / Math.log(ACCELERATION_MAX_SPEED / ACCELERATION_START_SPEED);
    }

    static double calculateScrollFlickFriction(double speed, long elapsedMs) {
        double speedRatio = (speed - SCROLL_FLICK_THRESHOLD)
                / (SCROLL_FULL_FRICTION_SPEED - SCROLL_FLICK_THRESHOLD);
        speedRatio = Math.max(0.0, Math.min(1.0, speedRatio));
        double perFrameFriction = SCROLL_SLOW_FRICTION
                + (FLICK_FRICTION - SCROLL_SLOW_FRICTION) * speedRatio;
        return Math.pow(perFrameFriction,
                Math.max(0, elapsedMs) / (double) MOMENTUM_FRAME_INTERVAL_MS);
    }

    static double calculateVelocityBlendWeight(long elapsedMs) {
        if (elapsedMs <= 0) {
            return 0.0;
        }
        return 1.0 - Math.exp(-elapsedMs / VELOCITY_SMOOTHING_TIME_CONSTANT_MS);
    }

    private void sendScrollDelta(double deltaX, double deltaY) {
        double absDeltaX = Math.abs(deltaX);
        double absDeltaY = Math.abs(deltaY);

        if (absDeltaX >= absDeltaY) {
            sendHorizontalScroll(deltaX);
            if (absDeltaY >= absDeltaX * SCROLL_SECONDARY_AXIS_RATIO) {
                sendVerticalScroll(deltaY);
            }
        } else {
            sendVerticalScroll(deltaY);
            if (absDeltaX >= absDeltaY * SCROLL_SECONDARY_AXIS_RATIO) {
                sendHorizontalScroll(deltaX);
            }
        }
    }

    private void sendHorizontalScroll(double deltaX) {
        short amount = clampToShort(-deltaX * SCROLL_SPEED_FACTOR_X);
        if (amount != 0) {
            conn.sendMouseHighResHScroll(amount);
        }
    }

    private void sendVerticalScroll(double deltaY) {
        short amount = clampToShort(deltaY * SCROLL_SPEED_FACTOR_Y);
        if (amount != 0) {
            conn.sendMouseHighResScroll(amount);
        }
    }

    private static short clampToShort(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    /** Stops kinetic scrolling without cancelling tap state. A new first-finger contact
     *  must brake momentum from either context, while still being eligible as the
     *  second tap of a double-tap sequence. */
    public void stopFling() {
        if (!isFlicking) {
            return;
        }

        handler.removeCallbacks(scrollMomentumRunnable);
        isFlicking = false;
        velocityX = velocityY = 0;
        pendingDeltaX = pendingDeltaY = 0;

        if (confirmedDrag) {
            releasePendingButton();
            confirmedDrag = false;
        }
    }

    @Override
    public int getActionIndex() {
        return actionIndex;
    }

    private boolean isWithinTapBounds(int touchX, int touchY) {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    private boolean isTap(long eventTime) {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false;
        }

        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    private byte getMouseButtonIndex() {
        if (pointerCount == 2) {
            return MouseButtonPacket.BUTTON_RIGHT;
        } else if (pointerCount == 3) {
            clickedMiddle = true;
            return MouseButtonPacket.BUTTON_MIDDLE;
        } else {
            return MouseButtonPacket.BUTTON_LEFT;
        }
    }

    /** Wraps mouse button sends to keep {@link #primaryButtonDown} in sync for the
     *  primary (left) button, so the trackpad can tell drags from scrolls. */
    private void mouseDown(byte button) {
        conn.sendMouseButtonDown(button);
        if (button == MouseButtonPacket.BUTTON_LEFT) {
            primaryButtonDown = true;
        }
    }

    private void mouseUp(byte button) {
        conn.sendMouseButtonUp(button);
        if (button == MouseButtonPacket.BUTTON_LEFT) {
            primaryButtonDown = false;
        }
    }

    private void releasePendingButton() {
        if (pendingButtonDown != 0) {
            byte button = pendingButtonDown;
            pendingButtonDown = 0;
            mouseUp(button);
        }
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger) {
        if (cancelled && !isNewFinger) {
            // Context was cancelled (e.g. a 3-finger gesture ended while this finger
            // was down). A "remaining finger becomes primary" re-down must not fire
            // a phantom right-click — only a fresh gesture (isNewFinger) revives us.
            return true;
        }

        if (isFlicking) {
            isFlicking = false;
            handler.removeCallbacksAndMessages(null);
        }

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

        pendingDeltaX = pendingDeltaY = 0;
        relativeRemainderX = relativeRemainderY = 0;

        if (isNewFinger) {
            // A completely new gesture has started.
            // Cancel any pending scroll->move transition.
            clickedMiddle = false;
            handler.removeCallbacks(scrollTransitionRunnable);
            isScrollTransitioning = false;
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedMove = confirmedScroll = false;
            distanceMoved = 0;
            velocityX = 0;
            velocityY = 0;
            lastMoveTime = eventTime;
            if (isClickPending) {
                // A second tap landed within the click window — could be a double-click
                // or a double-tap-and-drag. Do NOT arm a drag immediately: only promote
                // to drag in touchMoveEvent after the finger lingers DRAG_HOLD_DELAY_MS,
                // so "tap then move cursor" doesn't drag (and stick the left button).
                isClickPending = false;
                isDblClickPending = true;
                dragArmTime = eventTime;
            } else if (pendingButtonDown != 0) {
                // Defensive recovery for an anomalous new DOWN without the previous
                // UP/CANCEL. A normal second tap takes the branch above and keeps the
                // first button held until double-click/drag resolution.
                releasePendingButton();
            }
        } else {
            if (pointerCount == 2 && !confirmedMove) {
                releasePendingButton();
                mouseDown(MouseButtonPacket.BUTTON_MIDDLE);
                mouseUp(MouseButtonPacket.BUTTON_MIDDLE);
                isClickPending = false;
                isDblClickPending = false;
                confirmedDrag = false;
                clickedMiddle = true;
            // Second finger released, should trigger right click immediately
            } else if (pointerCount == 1 && !confirmedMove && !clickedMiddle) {
                releasePendingButton();
                mouseDown(MouseButtonPacket.BUTTON_RIGHT);
                mouseUp(MouseButtonPacket.BUTTON_RIGHT);
                isClickPending = false;
                isDblClickPending = false;
                confirmedDrag = false;
            }
        }

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

        pendingDeltaX = pendingDeltaY = 0;

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime) {
        if (cancelled) {
            return;
        }

        decayVelocityBeforeLift(eventTime);

        byte buttonIndex = getMouseButtonIndex();

        if (isDblClickPending) {
            handler.removeCallbacksAndMessages(null);
            releasePendingButton();
            mouseDown(buttonIndex);
            mouseUp(buttonIndex);
            isClickPending = false;
            confirmedDrag = false;
        }
        else if (confirmedDrag) {
            handler.removeCallbacksAndMessages(null);
            // A pointer/drag has no momentum phase on macOS. Release the held button
            // at finger lift instead of continuing to move and drag remotely.
            releasePendingButton();
            confirmedDrag = false;
        }
        else if (isTap(eventTime)) {
            mouseDown(buttonIndex);
            pendingButtonDown = buttonIndex;
            isClickPending = true;

            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(() -> {
                if (isClickPending) {
                    releasePendingButton();
                    isClickPending = false;
                }
                isDblClickPending = false;
            }, CLICK_RELEASE_DELAY);
        }
        else if (confirmedMove) {
            // Only content scrolling has a momentum phase. Cursor movement stops at
            // finger lift; acceleration has already been applied while contact was down.
            double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
            if (confirmedScroll && speed > SCROLL_FLICK_THRESHOLD) {
                isFlicking = true;
                lastMomentumFrameTime = SystemClock.uptimeMillis();
                handler.postDelayed(scrollMomentumRunnable, MOMENTUM_FRAME_INTERVAL_MS);
            }
        }
    }

    /** Ends a two-finger scroll when the primary contact lifts before the secondary
     *  contact. Scroll velocity is owned by context index 1, but Android reports the
     *  lift with action index 0 in this ordering, so the normal touchUpEvent() call
     *  targets the other context. Explicitly hand off the end of the scroll without
     *  running tap/right-click handling a second time. */
    public boolean finishScrollGesture(long eventTime) {
        if (cancelled || !confirmedScroll || isFlicking) {
            return false;
        }

        decayVelocityBeforeLift(eventTime);
        double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
        if (speed <= SCROLL_FLICK_THRESHOLD) {
            return false;
        }

        isFlicking = true;
        lastMomentumFrameTime = SystemClock.uptimeMillis();
        handler.postDelayed(scrollMomentumRunnable, MOMENTUM_FRAME_INTERVAL_MS);
        return true;
    }

    private void decayVelocityBeforeLift(long eventTime) {
        // Avoid a fling if the fingers paused before lifting. MotionEvent eventTime
        // and SystemClock uptime share the same monotonic time base.
        long timeSinceLastMove = eventTime - lastMoveTime;
        if (timeSinceLastMove > 0) {
            double decay = Math.max(0.0,
                    1.0 - (double) timeSinceLastMove / FLICK_VELOCITY_DECAY_TIMEOUT_MS);
            velocityX *= decay;
            velocityY *= decay;
        }
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime) {
        if (cancelled) {
            return true;
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            long deltaTime = eventTime - lastMoveTime;

            checkForConfirmedMove(eventX, eventY);

            if (isDblClickPending) {
                if (eventTime - dragArmTime >= DRAG_HOLD_DELAY_MS) {
                    // Held long enough — deliberate double-tap-and-drag. The original
                    // tap button is still down, so just enter drag mode.
                    isDblClickPending = false;
                    confirmedDrag = true;
                } else {
                    // Moved too soon after the second tap — treat as a plain cursor move.
                    // Release the pending tap button so this movement does not drag.
                    handler.removeCallbacksAndMessages(null);
                    releasePendingButton();
                    isDblClickPending = false;
                    isClickPending = false;
                }
            }

            int rawDeltaX = eventX - lastTouchX;
            int rawDeltaY = eventY - lastTouchY;

            double magnitude = Math.sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY);
            // Pointer acceleration belongs to cursor/drag motion, not two-finger
            // content scrolling. Keeping scroll linear makes content track the
            // fingers and avoids reporting-rate-dependent speed jumps.
            boolean twoFingerScrollMotion = pointerCount == 2
                    && !primaryButtonDown && !confirmedDrag;
            double precisionMultiplier = twoFingerScrollMotion ? 1.0
                    : calculateAccelerationMultiplier(magnitude, deltaTime);

            float deltaX, deltaY;
            if (swapAxis) {
                deltaY = rawDeltaX;
                deltaX = rawDeltaY;
            } else {
                deltaX = rawDeltaX;
                deltaY = rawDeltaY;
            }

            deltaX *= precisionMultiplier;
            deltaY *= precisionMultiplier;

            deltaX *= sensitivityX;
            deltaY *= sensitivityY;

            // Update velocity for flicking
            if (deltaTime > 0 && (confirmedMove || confirmedDrag)) {
                double currentVelocityX = deltaX / deltaTime;
                double currentVelocityY = deltaY / deltaTime;

                if (velocityX == 0 && velocityY == 0) {
                    // First measurement
                    velocityX = currentVelocityX;
                    velocityY = currentVelocityY;
                } else {
                    // Time-based EMA: the same physical movement gets the same
                    // smoothing at 60/120/240Hz instead of one 0.2 step per event.
                    double blendWeight = calculateVelocityBlendWeight(deltaTime);
                    velocityX += (currentVelocityX - velocityX) * blendWeight;
                    velocityY += (currentVelocityY - velocityY) * blendWeight;
                }
            }

            lastMoveTime = eventTime;

            pendingDeltaX += deltaX;
            pendingDeltaY += deltaY;

            lastTouchX = eventX;
            lastTouchY = eventY;

            short sendDeltaX = (short)pendingDeltaX;
            short sendDeltaY = (short)pendingDeltaY;

            if (confirmedDrag || (actionIndex == 1 && pointerCount == 2 && primaryButtonDown)) {
                // Dragging: either this finger is in a software drag, or the primary
                // button is held (e.g. hardware press-to-click on the OPPO trackpad)
                // while another finger moves. Move the cursor instead of scrolling, so
                // "hold button with one finger, drag with another" works.
                if (sendDeltaX != 0 || sendDeltaY != 0) {
                    conn.sendMouseMove(sendDeltaX, sendDeltaY);
                }
            } else if (pointerCount == 1) {
                // Don't move the mouse immediately after a scroll
                if (!isScrollTransitioning) {
                    if (sendDeltaX != 0 || sendDeltaY != 0) {
                        conn.sendMouseMove(sendDeltaX, sendDeltaY);
                    }
                }
            } else {
                if (actionIndex == 1 && pointerCount == 2) {
                    checkForConfirmedScroll();
                    if (confirmedScroll) {
                        sendScrollDelta(sendDeltaX, sendDeltaY);
                    }
                }
            }

            pendingDeltaX -= sendDeltaX;
            pendingDeltaY -= sendDeltaY;
        }

        return true;
    }

    /** Feeds Android's captured-touchpad relative axes into the same cursor state
     *  machine used by absolute contact coordinates. Fractional deltas are retained
     *  across events so high-resolution slow movement is not rounded away. */
    public boolean touchMoveRelativeEvent(float deltaX, float deltaY, long eventTime) {
        if (cancelled) {
            return true;
        }

        relativeRemainderX += deltaX;
        relativeRemainderY += deltaY;
        int wholeDeltaX = (int) relativeRemainderX;
        int wholeDeltaY = (int) relativeRemainderY;
        if (wholeDeltaX == 0 && wholeDeltaY == 0) {
            return true;
        }

        relativeRemainderX -= wholeDeltaX;
        relativeRemainderY -= wholeDeltaY;
        return touchMoveEvent(lastTouchX + wholeDeltaX, lastTouchY + wholeDeltaY, eventTime);
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        handler.removeCallbacksAndMessages(null);

        if (isFlicking) {
            isFlicking = false;
        }
        relativeRemainderX = relativeRemainderY = 0;

        // Release the exact button that was pressed when the tap began. It may be
        // right (two-finger tap) or middle, and pointer count may have changed since.
        if (pendingButtonDown != 0 || confirmedDrag || isClickPending || isDblClickPending) {
            releasePendingButton();
            confirmedDrag = false;
            isClickPending = false;
            isDblClickPending = false;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        if (this.pointerCount == 2 && pointerCount == 1) {
            // We just finished a 2-finger scroll.
            // Block mouse movement for a short period to avoid stray movement
            // from the remaining finger before it's lifted.
            isScrollTransitioning = true;
            handler.postDelayed(scrollTransitionRunnable, SCROLL_TRANSITION_TIMEOUT_MS);
        } else if (this.pointerCount == 1 && pointerCount == 2) {
            // User put a second finger down. If we were in a scroll transition, cancel it.
            handler.removeCallbacks(scrollTransitionRunnable);
            isScrollTransitioning = false;
        }

        if (pointerCount < this.pointerCount && confirmedDrag && !isFlicking) {
            releasePendingButton();
            confirmedDrag = false;
            confirmedMove = false;
            confirmedScroll = false;
            isClickPending = false;
            isDblClickPending = false;
        }

        // If a finger lifts while any tap button is still pending (not yet
        // auto-released and not a confirmed drag), release that exact button now.
        if (pointerCount < this.pointerCount && !isFlicking && !confirmedDrag &&
                (pendingButtonDown != 0 || isClickPending || isDblClickPending)) {
            handler.removeCallbacksAndMessages(null);
            releasePendingButton();
            isClickPending = false;
            isDblClickPending = false;
        }

        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }

    private void checkForConfirmedMove(int eventX, int eventY) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag) {
            return;
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true;
            return;
        }

        // Check if we've exceeded the maximum distance moved
        distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        if (distanceMoved >= TAP_MOVEMENT_THRESHOLD) {
            confirmedMove = true;
        }
    }

    private void checkForConfirmedScroll() {
        confirmedScroll = (actionIndex == 1 && pointerCount == 2 && confirmedMove);
    }
}
