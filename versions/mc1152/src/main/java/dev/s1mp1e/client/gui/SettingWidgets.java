package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.widget.ColorWidget;
import dev.s1mp1e.client.gui.widget.ModeWidget;
import dev.s1mp1e.client.gui.widget.SliderWidget;
import dev.s1mp1e.client.gui.widget.ToggleWidget;
import dev.s1mp1e.client.gui.widget.Widget;

/** Maps a {@link Setting} to the control widget that edits it. */
public final class SettingWidgets {

    private SettingWidgets() {}

    public static Widget forSetting(Setting s) {
        switch (s.type) {
            case BOOL:   return ToggleWidget.forSetting(s);
            case INT:
            case DOUBLE: return new SliderWidget(s);
            case COLOR:  return new ColorWidget(s);
            case MODE:   return new ModeWidget(s);
            default:     return null;
        }
    }
}
