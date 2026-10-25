package dev.s1mp1e.client.perf;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

import java.util.HashSet;
import java.util.Set;

/**
 * Gives the render thread a physical core of its own. At 900+ FPS on 26.3 (4K, Vulkan) frame times came in two modes,
 * ~0.85 ms and ~1.5 ms, the slow ones in runs of ~15 frames (one Windows scheduling quantum): whenever a busy game
 * thread — Sodium's async cull thread (busy all the time), the chunk builders, the integrated server — lands on the
 * render thread's SMT sibling, the render thread shares that core's execution units. So: the render thread is pinned
 * to the last physical core (both of its logical processors) and every other thread of the game is kept off that core;
 * new threads are picked up every 2 s. The other cores are left to the rest of the game. Windows only, 4+ cores with SMT
 * or not. Experiment, off by default: it measured slower (the slow-frame runs stayed, and the median frame got longer on
 * the reserved core); {@code S1MP1E_CORE_ISOLATION=1} turns it on. Call {@link #start()} on the render (main) thread.
 */
public final class CoreIsolation {
    private interface K32 extends Library {
        K32 I = Native.load("kernel32", K32.class);
        long SetThreadAffinityMask(WinNT.HANDLE thread, long mask);
    }

    private static final int THREAD_SET_INFORMATION = 0x0020, THREAD_QUERY_INFORMATION = 0x0040;
    private static final int TH32CS_SNAPTHREAD = 0x00000004;

    private CoreIsolation() {}

    public static void start() {
        // measured slower (26.3 Vulkan 4K: p50 0.87 -> 1.01 ms with the last core reserved), so opt-in only
        if (!"1".equals(System.getenv("S1MP1E_CORE_ISOLATION"))) return;
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return;
        try {
            long reserved = 0L, all = 0L;
            int cores = 0;
            for (WinNT.SYSTEM_LOGICAL_PROCESSOR_INFORMATION info : Kernel32Util.getLogicalProcessorInformation()) {
                if (info.relationship != WinNT.LOGICAL_PROCESSOR_RELATIONSHIP.RelationProcessorCore) continue;
                long mask = info.processorMask.longValue();
                all |= mask;
                cores++;
                if (Long.compareUnsigned(mask, reserved) > 0) reserved = mask;   // the highest-numbered core
            }
            long others = all & ~reserved;
            if (cores < 4 || reserved == 0L || others == 0L) return;
            int renderTid = Kernel32.INSTANCE.GetCurrentThreadId();
            K32.I.SetThreadAffinityMask(Kernel32.INSTANCE.GetCurrentThread(), reserved);
            System.out.printf("[S1mp1e] render thread on its own core (mask %x), %d other cores for the rest%n", reserved, cores - 1);
            final long othersMask = others;
            Thread t = new Thread(() -> {
                int pid = Kernel32.INSTANCE.GetCurrentProcessId();
                Set<Integer> done = new HashSet<>();
                while (true) {
                    try {
                        keepOff(pid, renderTid, othersMask, done);
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable e) {
                        System.out.println("[S1mp1e] core isolation stopped: " + e);
                        return;
                    }
                }
            }, "S1mp1e-CoreIsolation");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (Throwable t) {
            System.out.println("[S1mp1e] core isolation unavailable: " + t);
        }
    }

    /** Every thread of this process except the render thread: off the reserved core. */
    private static void keepOff(int pid, int renderTid, long othersMask, Set<Integer> done) {
        WinNT.HANDLE snap = Kernel32.INSTANCE.CreateToolhelp32Snapshot(new WinDef.DWORD(TH32CS_SNAPTHREAD), new WinDef.DWORD(0));
        if (snap == null || WinNT.INVALID_HANDLE_VALUE.equals(snap)) return;
        try {
            Tlhelp32.THREADENTRY32 e = new Tlhelp32.THREADENTRY32();
            if (!Kernel32.INSTANCE.Thread32First(snap, e)) return;
            do {
                if (e.th32OwnerProcessID != pid || e.th32ThreadID == renderTid || done.contains(e.th32ThreadID)) continue;
                WinNT.HANDLE h = Kernel32.INSTANCE.OpenThread(THREAD_SET_INFORMATION | THREAD_QUERY_INFORMATION, false, e.th32ThreadID);
                if (h == null) continue;
                try {
                    if (K32.I.SetThreadAffinityMask(h, othersMask) != 0L) done.add(e.th32ThreadID);
                } finally {
                    Kernel32.INSTANCE.CloseHandle(h);
                }
            } while (Kernel32.INSTANCE.Thread32Next(snap, e));
        } finally {
            Kernel32.INSTANCE.CloseHandle(snap);
        }
    }
}
