package dev.s1mp1e.o.event;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取代 Forge 的 {@code EVENT_BUS}：只做 S1mp1e 用得到的部分（Ornithe 沒有 Forge）。
 *
 * <p>和 Forge 一樣的語意：{@link #register(Object)} 掃描物件上所有標了 {@link SubscribeEvent}、只有一個參數（事件型別）
 * 的方法；{@link #post(Event)} 依 {@link EventPriority}（HIGHEST→LOWEST，同優先序依註冊順序）呼叫所有
 * 參數型別是「事件類別或其父類別」的監聽者；事件被取消後，沒標 {@code receiveCanceled} 的監聽者就不再收到。
 * 回傳值＝事件是否被取消（和 Forge 的 {@code post} 一樣）。
 *
 * <p>效能：HUD 每幀會發十幾個事件、目標是 1000+ fps，所以監聽者用 {@link MethodHandle}（綁定實例）呼叫，
 * 並依事件的具體類別快取已排序好的監聽者陣列；註冊/取消註冊時清快取。
 */
public final class EventBus {

    private static final class Listener {
        final Object owner;
        final Class<?> type;
        final EventPriority priority;
        final boolean receiveCanceled;
        final MethodHandle handle;
        final long order;

        Listener(Object owner, Class<?> type, EventPriority priority, boolean receiveCanceled, MethodHandle handle, long order) {
            this.owner = owner;
            this.type = type;
            this.priority = priority;
            this.receiveCanceled = receiveCanceled;
            this.handle = handle;
            this.order = order;
        }
    }

    private static final Listener[] NONE = new Listener[0];

    private final List<Listener> all = new ArrayList<Listener>();
    private final Map<Object, List<Listener>> byOwner = new IdentityHashMap<Object, List<Listener>>();
    private final Map<Class<?>, Listener[]> cache = new ConcurrentHashMap<Class<?>, Listener[]>();
    private long counter;

    public synchronized void register(Object target) {
        if (target == null || byOwner.containsKey(target)) return;
        List<Listener> mine = new ArrayList<Listener>();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                SubscribeEvent ann = m.getAnnotation(SubscribeEvent.class);
                if (ann == null || m.getParameterTypes().length != 1) continue;
                if (!Event.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                try {
                    m.setAccessible(true);
                    MethodHandle h = lookup.unreflect(m);
                    if (!Modifier.isStatic(m.getModifiers())) h = h.bindTo(target);
                    h = h.asType(h.type().changeParameterType(0, Event.class));
                    mine.add(new Listener(target, m.getParameterTypes()[0], ann.priority(), ann.receiveCanceled(), h, counter++));
                } catch (Throwable t) {
                    System.out.println("[S1mp1e][EventBus] cannot register " + m + ": " + t);
                }
            }
        }
        byOwner.put(target, mine);
        all.addAll(mine);
        cache.clear();
    }

    public synchronized void unregister(Object target) {
        List<Listener> mine = byOwner.remove(target);
        if (mine == null) return;
        all.removeAll(mine);
        cache.clear();
    }

    private Listener[] listenersFor(Class<?> evt) {
        Listener[] arr = cache.get(evt);
        if (arr != null) return arr;
        synchronized (this) {
            List<Listener> out = new ArrayList<Listener>();
            for (Listener l : all) if (l.type.isAssignableFrom(evt)) out.add(l);
            Collections.sort(out, (a, b) -> a.priority != b.priority
                    ? a.priority.ordinal() - b.priority.ordinal()
                    : Long.compare(a.order, b.order));
            arr = out.isEmpty() ? NONE : out.toArray(new Listener[0]);
            cache.put(evt, arr);
            return arr;
        }
    }

    /** 發送事件；回傳是否被取消。監聽者丟例外只記錄、不中斷其他監聽者（也絕不拖垮渲染迴圈）。 */
    public boolean post(Event e) {
        Listener[] ls = listenersFor(e.getClass());
        for (Listener l : ls) {
            if (e.isCanceled() && !l.receiveCanceled) continue;
            try {
                l.handle.invokeExact(e);
            } catch (Throwable t) {
                System.out.println("[S1mp1e][EventBus] listener " + l.owner.getClass().getName() + " failed on "
                        + e.getClass().getSimpleName() + ": " + t);
            }
        }
        return e.isCanceled();
    }
}
