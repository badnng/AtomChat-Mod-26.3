package com.atom.chat.miuix.api;

/** Serializes back/close intent with the host's acknowledged native page transition. */
public final class NativeNavigationInput {
    public enum Request { BACK, CLOSE }

    private Request pending;
    private boolean escapeDown;
    private long backStartedAt = -1L;

    /** A held Escape is one intent, even when the OS repeats its key-down event. */
    public boolean pressEscape() {
        if (escapeDown) return false;
        escapeDown = true;
        return true;
    }

    public void releaseEscape() { escapeDown = false; }

    public boolean backInFlight() { return backStartedAt >= 0L; }

    /** Repeated back intents during the same push/pop are coalesced. */
    public void defer(Request request) {
        // An explicit Close button remains meaningful during a queued/in-flight
        // back. Escape never queues Close while a page transition is running.
        if (request == Request.CLOSE) {
            pending = request;
            return;
        }
        if (backInFlight() || pending != null) return;
        pending = request;
    }

    public Request takeReady(boolean navigationReady) {
        if (!navigationReady || backInFlight()) return null;
        Request result = pending;
        pending = null;
        return result;
    }

    public void backStarted(long sequence) {
        if (pending == Request.BACK) pending = null;
        backStartedAt = sequence;
    }

    public void transitionFinished(long sequence) {
        if (sequence > backStartedAt) backStartedAt = -1L;
    }

    public void clear() { pending = null; }
}
