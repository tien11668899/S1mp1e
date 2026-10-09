package dev.s1mp1e.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import java.io.ByteArrayInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 靈動島的封面貼圖。啟動器已把封面做成 160×160、烤好 Apple 比例圓角的 PNG，這裡只負責上傳成貼圖、
 * 換歌時換掉，並且保留「上一張」讓換歌時可以交叉淡化。只能在渲染執行緒呼叫。
 */
public final class AlbumArt {
    private AlbumArt() {}

    public static final int SIZE = 160;

    private static com.mojang.renderpearl.api.textures.GpuSampler linear;

    /** 雙線性取樣（原版 GUI 貼圖是最近鄰，160 px 縮到十幾 px 會有鋸齒） */
    private static final class LinearTexture extends DynamicTexture {
        LinearTexture(Supplier<String> label, NativeImage image) {
            super(label, image);
            if (linear == null) {
                linear = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            }
            this.sampler = linear;
        }
    }
    private static final Identifier[] IDS = {
            Identifier.fromNamespaceAndPath("s1mp1e", "island_art_0"),
            Identifier.fromNamespaceAndPath("s1mp1e", "island_art_1")};
    private static final DynamicTexture[] TEX = new DynamicTexture[2];
    private static final long[] TEX_ART = new long[2];
    private static int cur = -1;
    /** 目前那張開始顯示的時間（交叉淡化用） */
    private static long swappedNanos;

    /** 有新封面就上傳（交替用兩個槽，舊的那張留著淡出）。 */
    public static void update() {
        long id = MediaClient.artPngId();
        byte[] png = MediaClient.artPng();
        if (png == null || id == 0 || (cur >= 0 && TEX_ART[cur] == id)) return;
        int slot = cur < 0 ? 0 : 1 - cur;
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
            if (TEX[slot] != null) {
                TEX[slot].setPixels(img);
                TEX[slot].upload();
            } else {
                final int s = slot;
                TEX[slot] = new LinearTexture(() -> "s1mp1e:island_art_" + s, img);
                Minecraft.getInstance().getTextureManager().register(IDS[slot], TEX[slot]);
            }
            TEX_ART[slot] = id;
            cur = slot;
            swappedNanos = System.nanoTime();
        } catch (Throwable ignored) {
        }
    }

    public static boolean ready() { return cur >= 0; }

    /** 換歌後經過的秒數（交叉淡化） */
    public static float sinceSwap() { return (System.nanoTime() - swappedNanos) / 1e9f; }

    /** 畫目前的封面（已含圓角），可指定不透明度；{@code previous} 為 true 畫上一張。 */
    public static void draw(GuiGraphicsExtractor g, float x, float y, float size, float alpha, boolean previous) {
        int slot = previous ? (cur < 0 ? -1 : 1 - cur) : cur;
        if (slot < 0 || TEX[slot] == null || alpha <= 0.003f) return;
        int a = Math.round(Math.min(1f, alpha) * 255f);
        float s = size / SIZE;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        g.blit(RenderPipelines.GUI_TEXTURED, IDS[slot], 0, 0, 0f, 0f, SIZE, SIZE, SIZE, SIZE, (a << 24) | 0xFFFFFF);
        g.pose().popMatrix();
    }
}
