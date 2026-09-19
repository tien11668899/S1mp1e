using System;
using System.Collections.Generic;
using System.Linq;
using System.Reflection;
using Avalonia;
using Avalonia.Media;
using Avalonia.VisualTree;

namespace LiquidGlassAvaloniaUI
{
    internal static class LiquidGlassVisualRenderer
    {
        public static void Render(DrawingContext context, Visual visual, Rect clipRect, ISet<Visual>? excludedRoots)
        {
            if (clipRect.Width <= 0 || clipRect.Height <= 0)
                return;

            // Clip in TARGET (bitmap) space first, then translate the window into it. The other order put the clip
            // (0,0,W,H) in WINDOW coordinates, so any capture region not starting at the window's top-left kept only the
            // band [clip.Y, H] (e.g. a 56 DIP strip) and left the rest of the snapshot transparent — lenses below it
            // then refracted nothing but empty/edge pixels.
            Rect targetClip = new(clipRect.Size);
            using (context.PushClip(targetClip))
            using (context.PushTransform(Matrix.CreateTranslation(-clipRect.Position.X, -clipRect.Position.Y)))
            {
                Render(context, visual, new Rect(visual.Bounds.Size), Matrix.Identity, clipRect, excludedRoots);
            }
        }

        private static void Render(
            DrawingContext context,
            Visual visual,
            Rect bounds,
            Matrix parentTransform,
            Rect clipRect,
            ISet<Visual>? excludedRoots)
        {
            if (excludedRoots is not null && excludedRoots.Contains(visual))
                return;

            if (LiquidGlassBackdrop.GetIsExcludedFromCapture(visual))
                return;

            if (!visual.IsVisible || visual.Opacity <= 0)
                return;

            Rect rect = new(bounds.Size);
            Matrix transform;

            if (visual.RenderTransform?.Value is { } rt)
            {
                Point origin = visual.RenderTransformOrigin.ToPixels(visual.Bounds.Size);
                Matrix offset = Matrix.CreateTranslation(origin);
                transform = -offset * rt * offset * Matrix.CreateTranslation(bounds.Position);
            }
            else
            {
                transform = Matrix.CreateTranslation(bounds.Position);
            }

            using (context.PushTransform(transform))
            using (visual.HasMirrorTransform
                ? context.PushTransform(new Matrix(-1.0, 0.0, 0.0, 1.0, visual.Bounds.Width, 0))
                : default(DrawingContext.PushedState?))
            using (context.PushOpacity(visual.Opacity))
            using (PushClipToBounds(context, visual, rect))
            using (visual.Clip is { } clip ? context.PushGeometryClip(clip) : default(DrawingContext.PushedState?))
            using (visual.OpacityMask is { } opacityMask ? context.PushOpacityMask(opacityMask, rect) : default(DrawingContext.PushedState?))
            {
                Matrix totalTransform = transform * parentTransform;
                Rect visualBounds = rect.TransformToAABB(totalTransform);

                if (visualBounds.Intersects(clipRect))
                    RenderSelf(context, visual);

                IReadOnlyList<Visual> children = GetOrderedChildren(visual);

                if (visual.ClipToBounds)
                {
                    totalTransform = Matrix.Identity;
                    clipRect = rect;
                }

                foreach (Visual? child in children)
                {
                    Render(context, child, child.Bounds, totalTransform, clipRect, excludedRoots);
                }
            }
        }

        // Some controls' own Render leaves the immediate drawing context's transform changed. Under Avalonia 12 a Border
        // with BoxShadows (every launcher card) returns with the transform switched to DEVICE pixels (scale = render
        // scaling), so every child rendered after it was scaled twice: the card's content landed ~1.25x too far right /
        // down in the snapshot (a slider at x 785 DIP ended up beyond the capture), and every lens over a card sampled
        // the wrong place. Remember the transform around each Render call and put it back if it changed.
        private static FieldInfo? s_implField;
        private static PropertyInfo? s_implTransform;
        private static Type? s_contextType;

        private static void RenderSelf(DrawingContext context, Visual visual)
        {
            object? impl = ResolveImpl(context);
            Matrix before = impl is null ? default : (Matrix)s_implTransform!.GetValue(impl)!;

            visual.Render(context);

            if (impl is not null)
            {
                Matrix after = (Matrix)s_implTransform!.GetValue(impl)!;
                if (after != before)
                    s_implTransform.SetValue(impl, before);
            }
        }

        private static object? ResolveImpl(DrawingContext context)
        {
            Type type = context.GetType();
            if (!ReferenceEquals(type, s_contextType))
            {
                s_contextType = type;
                s_implField = null;
                s_implTransform = null;
                foreach (FieldInfo f in type.GetFields(BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public))
                {
                    PropertyInfo? prop = f.FieldType.GetProperty("Transform", BindingFlags.Instance | BindingFlags.Public);
                    if (prop is not null && prop.PropertyType == typeof(Matrix) && prop.CanRead && prop.CanWrite)
                    {
                        s_implField = f;
                        s_implTransform = prop;
                        break;
                    }
                }
            }

            if (s_implField is null || s_implTransform is null)
                return null;

            try { return s_implField.GetValue(context); }
            catch { return null; }
        }

        private static DrawingContext.PushedState? PushClipToBounds(DrawingContext context, Visual visual, Rect rect)
        {
            if (!visual.ClipToBounds)
                return default;

            if (TryGetClipToBoundsRadius(visual, out CornerRadius radius))
                return context.PushClip(new RoundedRect(rect, radius));

            return context.PushClip(rect);
        }

        private static bool TryGetClipToBoundsRadius(Visual visual, out CornerRadius radius)
        {
            radius = default;

            Type type = visual.GetType();
            PropertyInfo? prop = type.GetProperty("ClipToBoundsRadius", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic);

            if (prop is null)
            {
                prop = type
                    .GetProperties(System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic)
                    .FirstOrDefault(p => p.Name.EndsWith(".ClipToBoundsRadius", StringComparison.Ordinal));
            }

            if (prop?.PropertyType != typeof(CornerRadius))
                return false;

            if (prop.GetValue(visual) is not CornerRadius value)
                return false;

            if (value == default)
                return false;

            radius = value;
            return true;
        }

        private static IReadOnlyList<Visual> GetOrderedChildren(Visual visual)
        {
            IEnumerable<Visual> children = visual.GetVisualChildren();

            List<Visual>? list = null;
            int? firstZIndex = null;
            bool hasNonUniformZIndex = false;

            foreach (Visual? child in children)
            {
                list ??= new List<Visual>();
                list.Add(child);

                if (firstZIndex is null)
                    firstZIndex = child.ZIndex;
                else if (child.ZIndex != firstZIndex.Value)
                    hasNonUniformZIndex = true;
            }

            if (list is null || list.Count == 0)
                return Array.Empty<Visual>();

            if (!hasNonUniformZIndex)
                return list;

            return list.OrderBy(x => x.ZIndex).ToArray();
        }
    }
}
