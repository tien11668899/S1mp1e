using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.VisualTree;

namespace LiquidGlassAvaloniaUI
{
    public sealed class LiquidGlassBackdrop
    {
        private LiquidGlassBackdrop()
        {
        }

        public static readonly AttachedProperty<bool> IsExcludedFromCaptureProperty =
            AvaloniaProperty.RegisterAttached<LiquidGlassBackdrop, Visual, bool>(
                "IsExcludedFromCapture",
                false);

        public static bool GetIsExcludedFromCapture(Visual visual)
        {
            if (visual is null)
                throw new ArgumentNullException(nameof(visual));

            return visual.GetValue(IsExcludedFromCaptureProperty);
        }

        public static void SetIsExcludedFromCapture(Visual visual, bool value)
        {
            if (visual is null)
                throw new ArgumentNullException(nameof(visual));

            visual.SetValue(IsExcludedFromCaptureProperty, value);
        }

        /// <summary>Hold one backdrop snapshot (whose clip already covers the subscriber's FINAL rect, in TopLevel DIP)
        /// for the duration of a short morph; pair with <see cref="Unfreeze"/>.</summary>
        public static void FreezeForAnimation(Control subscriber, Rect finalRectTopLevelDip)
        {
            if (subscriber is null)
                throw new ArgumentNullException(nameof(subscriber));

            LiquidGlassBackdropProvider.FreezeForAnimation(subscriber, finalRectTopLevelDip);
        }

        public static void Unfreeze(Control subscriber)
        {
            if (subscriber is null)
                throw new ArgumentNullException(nameof(subscriber));

            LiquidGlassBackdropProvider.Unfreeze(subscriber);
        }
    }
}
