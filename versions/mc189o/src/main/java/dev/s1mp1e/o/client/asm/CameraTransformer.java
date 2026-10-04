package dev.s1mp1e.o.client.asm;

/**
 * 1.8.9 Forge 版這裡是鏡頭 ASM 修補；Ornithe 版改成 mixin。這裡只留「修補有沒有生效」的旗標，
 * 由 {@code S1mp1eMixinPlugin} 在對應 mixin 套用後設成 true，CameraHooks 的事件後備路徑照舊用來判斷。
 */
public final class CameraTransformer {
    private CameraTransformer() {}

    /** GameRenderer.updateLightMap 的 gamma 修補已生效。 */
    public static volatile boolean gammaPatched = false;
    /** ItemInHandRenderer.renderInFirstPerson 的手部位移已生效。 */
    public static volatile boolean handPatched = false;
    /** 縮放時滑鼠視角變慢已生效。 */
    public static volatile boolean lookScalePatched = false;
    /** 其他模組的縮放鍵過濾已生效。 */
    public static volatile boolean keyFilterPatched = false;
}
