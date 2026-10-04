package dev.s1mp1e.o.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 和 Forge 的 {@code @SubscribeEvent} 同形：標在只有一個事件參數的方法上，由 {@link EventBus#register} 掃描。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {
    EventPriority priority() default EventPriority.NORMAL;

    boolean receiveCanceled() default false;
}
