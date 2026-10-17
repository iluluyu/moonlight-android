package com.limelight.binding.input.touch;

import java.util.HashMap;
import java.util.Map;

/**
 * Drops contacts that must not drive gestures when the physical touchpad is read
 * directly through evdev.
 *
 * <p>Reading the device node bypasses the input stack's own filters (Android's
 * {@code Finger Merge}, {@code Fat Finger}, palm detection and the vendor idc tunables),
 * so a thumb resting on the pad arrives as a normal finger. That phantom contact
 * changes the pointer count, which breaks exactly the finger-count gestures this app
 * is built around: a resting thumb turns a two-finger scroll into a three-finger
 * swipe.</p>
 *
 * <p>The rules are deliberately conservative - a filter that is too eager would eat
 * real gestures:</p>
 * <ul>
 *   <li>A single contact is never dropped, so one-finger use is always untouched.</li>
 *   <li>A contact smaller than {@link #NOISE_SIZE} (0..1000 normalized contact area)
 *       is dropped as sensor noise only while another contact exists.</li>
 *   <li>A contact that has been stationary for {@link #REST_MS} is treated as a
 *       resting thumb/palm, but only when at least three contacts are down <em>and</em>
 *       another contact is being moved. Holding three fingers still (about to start a
 *       gesture) is therefore never filtered, and two deliberate fingers always
 *       survive, so slow two-finger scrolling keeps working.</li>
 *   <li>If every contact would be dropped, the oldest one is kept.</li>
 * </ul>
 */
public final class TrackpadContactFilter {
    /** Normalized contact area (0..1000) below which a contact is sensor noise. */
    static final int NOISE_SIZE = 40;
    /** Normalized contact area (0..1000) a resting thumb/palm is expected to exceed. */
    static final int REST_MIN_SIZE = 0;
    /** How long a contact must stay put before it counts as resting. */
    static final long REST_MS = 500;
    /** Movement (in pad units) that still counts as "stationary". */
    static final int REST_RADIUS = 12;
    /** Contacts below this count are never filtered - single/two-finger use is sacred. */
    static final int MIN_CONTACTS_FOR_FILTERING = 2;
    /** Resting contacts are only dropped when this many contacts are down. */
    static final int MIN_CONTACTS_FOR_REST_SUPPRESSION = 3;
    /** Forget per-contact state after this long without a frame mentioning it. */
    static final long STATE_TIMEOUT_MS = 1000;

    private static final class State {
        /** Anchor position of the current "has not moved" evaluation window. */
        int anchorX;
        int anchorY;
        long stillSinceMs;
        long lastMoveMs;
        long lastSeenMs;
    }

    private final Map<Integer, State> states = new HashMap<>();

    /**
     * Chooses which contacts of the current frame may drive gestures.
     *
     * @param timeMs  frame timestamp (uptime millis)
     * @param ids     stable contact ids (evdev tracking ids); may be null
     * @param xs      contact x positions in pad units
     * @param ys      contact y positions in pad units
     * @param sizes   normalized contact area 0..1000 per contact, or null
     * @param keep    output flags, one per input contact
     * @return number of contacts kept
     */
    public int select(long timeMs, int[] ids, int[] xs, int[] ys, int[] sizes, boolean[] keep) {
        int count = xs == null ? 0 : xs.length;
        if (keep.length < count) {
            throw new IllegalArgumentException("keep[] too small");
        }

        updateStates(timeMs, ids, xs, ys);

        // Single contact: never filter. One finger must always work, and with a single
        // contact there is no gesture identity for a phantom finger to corrupt.
        if (count < MIN_CONTACTS_FOR_FILTERING) {
            for (int i = 0; i < count; i++) {
                keep[i] = true;
            }
            return count;
        }

        // A resting finger only matters when the rest of the hand is actually moving:
        // three fingers held still before a gesture look identical to "thumb + one
        // finger", but nothing has moved yet, so filtering them would break the gesture.
        boolean anyMoving = false;
        for (int i = 0; i < count; i++) {
            State state = stateFor(ids, i);
            if (state != null && timeMs - state.lastMoveMs < REST_MS) {
                anyMoving = true;
                break;
            }
        }

        // A hand has at most one resting thumb/palm, so at most one contact may be
        // dropped by the resting rule per frame; dropping more would silently turn a
        // deliberate multi-finger gesture into a one-finger gesture.
        int restingIndex = -1;
        long restingSince = Long.MAX_VALUE;

        int kept = 0;
        int oldestIndex = 0;
        long oldestStillSince = Long.MAX_VALUE;

        for (int i = 0; i < count; i++) {
            boolean drop = false;
            int size = (sizes != null && i < sizes.length) ? sizes[i] : 1000;
            State state = stateFor(ids, i);

            if (size < NOISE_SIZE) {
                // Noise only matters when it changes the pointer count.
                drop = true;
            } else if (anyMoving && count >= MIN_CONTACTS_FOR_REST_SUPPRESSION
                    && state != null && size >= REST_MIN_SIZE
                    && timeMs - state.lastMoveMs >= REST_MS
                    && state.stillSinceMs < restingSince) {
                restingIndex = i;
                restingSince = state.stillSinceMs;
            }

            keep[i] = !drop;
            if (!drop) {
                kept++;
            }

            if (state != null && state.stillSinceMs < oldestStillSince) {
                oldestStillSince = state.stillSinceMs;
                oldestIndex = i;
            }
        }

        if (restingIndex >= 0) {
            keep[restingIndex] = false;
            kept--;
        }

        if (kept == 0) {
            // Never forward an empty frame: that would look like "all fingers lifted".
            keep[oldestIndex] = true;
            kept = 1;
        }

        return kept;
    }

    /** True when the contact was dropped by the resting-thumb rule (for logging). */
    public long stationaryMs(long timeMs, int[] ids, int index, int[] xs, int[] ys) {
        State state = stateFor(ids, index);
        if (state == null) {
            return 0;
        }
        return timeMs - state.stillSinceMs;
    }

    public void reset() {
        states.clear();
    }

    private State stateFor(int[] ids, int index) {
        return states.get(contactId(ids, index));
    }

    private static int contactId(int[] ids, int index) {
        return (ids != null && index < ids.length) ? ids[index] : index;
    }

    private void updateStates(long timeMs, int[] ids, int[] xs, int[] ys) {
        if (xs == null || ys == null) {
            return;
        }

        for (int i = 0; i < xs.length && i < ys.length; i++) {
            int id = contactId(ids, i);
            State state = states.get(id);
            if (state == null) {
                state = new State();
                state.anchorX = xs[i];
                state.anchorY = ys[i];
                state.stillSinceMs = timeMs;
                state.lastMoveMs = timeMs;
                states.put(id, state);
            } else {
                // Compare against the anchor, not the previous frame. A slow scroll moves
                // only a few pad units per frame, so a per-frame delta test would call a
                // steadily moving finger "stationary" and drop it as a resting thumb.
                int dx = Math.abs(xs[i] - state.anchorX);
                int dy = Math.abs(ys[i] - state.anchorY);
                if (dx > REST_RADIUS || dy > REST_RADIUS) {
                    state.anchorX = xs[i];
                    state.anchorY = ys[i];
                    state.stillSinceMs = timeMs;
                    state.lastMoveMs = timeMs;
                }
            }
            state.lastSeenMs = timeMs;
        }

        if (states.size() > 8) {
            states.entrySet().removeIf(entry -> timeMs - entry.getValue().lastSeenMs > STATE_TIMEOUT_MS);
        }
    }
}
