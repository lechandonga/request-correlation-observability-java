package com.github.lechandonga.requestcorrelationobservabilityjava.correlation;

import org.slf4j.MDC;

/**
 * Binds the current request's correlation id to the executing thread.
 *
 * <p>The id is kept both in a {@link ThreadLocal} and in the SLF4J
 * {@link MDC} (key {@link #MDC_KEY}) so that every log line on this thread
 * is automatically tagged. {@link #begin(String)} returns the previously
 * bound id and {@link #end(String)} restores it (or clears the state when
 * there was none), which guarantees that a pooled, reused thread never
 * keeps the previous task's correlation id.
 */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private CorrelationContext() {
    }

    /** @return the correlation id bound to the current thread, or null */
    public static String currentId() {
        return HOLDER.get();
    }

    /**
     * Bind {@code correlationId} to the current thread.
     *
     * @return the id that was previously bound (may be null); pass it back to
     *         {@link #end(String)} to restore the prior state exactly.
     */
    public static String begin(String correlationId) {
        String previous = HOLDER.get();
        HOLDER.set(correlationId);
        MDC.put(MDC_KEY, correlationId);
        return previous;
    }

    /**
     * Restore the state captured by {@link #begin(String)}: rebinds the
     * previous id, or clears everything when there was none.
     */
    public static void end(String previous) {
        if (previous == null) {
            clear();
        } else {
            HOLDER.set(previous);
            MDC.put(MDC_KEY, previous);
        }
    }

    /** Unconditionally remove all correlation state from this thread. */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(MDC_KEY);
    }
}
