package dev.s1mp1e.o.event;

/** 事件基底。可取消的事件覆寫 {@link #isCancelable()} 回傳 true（和 Forge 的 {@code @Cancelable} 同義）。 */
public class Event {
    private boolean canceled;

    public boolean isCancelable() {
        return false;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean cancel) {
        if (!isCancelable()) throw new IllegalArgumentException("Attempted to cancel an uncancelable event: " + getClass().getName());
        canceled = cancel;
    }
}
