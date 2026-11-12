package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        DEFAULT("DefaultIcon", R.drawable.icon_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconDefault),
        VINTAGE("VintageIcon", R.drawable.icon_6_background_sa, R.mipmap.icon_6_foreground_sa, R.string.AppIconVintage),
        AQUA("AquaIcon", R.drawable.icon_4_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconAqua),
        PREMIUM("PremiumIcon", R.drawable.icon_3_background_sa, R.mipmap.icon_3_foreground_sa, R.string.AppIconPremium, true),
        TURBO("TurboIcon", R.drawable.icon_5_background_sa, R.mipmap.icon_5_foreground_sa, R.string.AppIconTurbo, true),
        NOX("NoxIcon", R.mipmap.icon_2_background_sa, R.mipmap.icon_foreground_sa, R.string.AppIconNox, true),

        // ParadoksGram: ghost icon pack (18 designs x adaptive/Windows-style = 36 options)
        CLASSIC1("Classic1Icon", R.mipmap.icon_classic1_bg, R.mipmap.icon_blank_fg, R.string.AppIconClassic1),
        CLASSIC1WIN("Classic1WinIcon", R.mipmap.icon_classic1_bg, R.mipmap.icon_blank_fg, R.string.AppIconClassic1Win),
        CLASSIC2("Classic2Icon", R.mipmap.icon_classic2_bg, R.mipmap.icon_blank_fg, R.string.AppIconClassic2),
        CLASSIC2WIN("Classic2WinIcon", R.mipmap.icon_classic2_bg, R.mipmap.icon_blank_fg, R.string.AppIconClassic2Win),
        PHANTOM("PhantomIcon", R.mipmap.icon_phantom_bg, R.mipmap.icon_blank_fg, R.string.AppIconPhantom),
        PHANTOMWIN("PhantomWinIcon", R.mipmap.icon_phantom_bg, R.mipmap.icon_blank_fg, R.string.AppIconPhantomWin),
        MIDNIGHT("MidnightIcon", R.mipmap.icon_midnight_bg, R.mipmap.icon_blank_fg, R.string.AppIconMidnight),
        MIDNIGHTWIN("MidnightWinIcon", R.mipmap.icon_midnight_bg, R.mipmap.icon_blank_fg, R.string.AppIconMidnightWin),
        CRIMSON("CrimsonIcon", R.mipmap.icon_crimson_bg, R.mipmap.icon_blank_fg, R.string.AppIconCrimson),
        CRIMSONWIN("CrimsonWinIcon", R.mipmap.icon_crimson_bg, R.mipmap.icon_blank_fg, R.string.AppIconCrimsonWin),
        ARCTIC("ArcticIcon", R.mipmap.icon_arctic_bg, R.mipmap.icon_blank_fg, R.string.AppIconArctic),
        ARCTICWIN("ArcticWinIcon", R.mipmap.icon_arctic_bg, R.mipmap.icon_blank_fg, R.string.AppIconArcticWin),
        EMERALD("EmeraldIcon", R.mipmap.icon_emerald_bg, R.mipmap.icon_blank_fg, R.string.AppIconEmerald),
        EMERALDWIN("EmeraldWinIcon", R.mipmap.icon_emerald_bg, R.mipmap.icon_blank_fg, R.string.AppIconEmeraldWin),
        GHOST_OUTLINE1("GhostOutline1Icon", R.mipmap.icon_ghost_outline1_bg, R.mipmap.icon_blank_fg, R.string.AppIconGhostOutline1),
        GHOST_OUTLINE1WIN("GhostOutline1WinIcon", R.mipmap.icon_ghost_outline1_bg, R.mipmap.icon_blank_fg, R.string.AppIconGhostOutline1Win),
        GHOST_OUTLINE2("GhostOutline2Icon", R.mipmap.icon_ghost_outline2_bg, R.mipmap.icon_blank_fg, R.string.AppIconGhostOutline2),
        GHOST_OUTLINE2WIN("GhostOutline2WinIcon", R.mipmap.icon_ghost_outline2_bg, R.mipmap.icon_blank_fg, R.string.AppIconGhostOutline2Win),
        GOLD1("Gold1Icon", R.mipmap.icon_gold1_bg, R.mipmap.icon_blank_fg, R.string.AppIconGold1),
        GOLD1WIN("Gold1WinIcon", R.mipmap.icon_gold1_bg, R.mipmap.icon_blank_fg, R.string.AppIconGold1Win),
        GOLD2("Gold2Icon", R.mipmap.icon_gold2_bg, R.mipmap.icon_blank_fg, R.string.AppIconGold2),
        GOLD2WIN("Gold2WinIcon", R.mipmap.icon_gold2_bg, R.mipmap.icon_blank_fg, R.string.AppIconGold2Win),
        GLASS("GlassIcon", R.mipmap.icon_glass_bg, R.mipmap.icon_blank_fg, R.string.AppIconGlass),
        GLASSWIN("GlassWinIcon", R.mipmap.icon_glass_bg, R.mipmap.icon_blank_fg, R.string.AppIconGlassWin),
        NEON("NeonIcon", R.mipmap.icon_neon_bg, R.mipmap.icon_blank_fg, R.string.AppIconNeon),
        NEONWIN("NeonWinIcon", R.mipmap.icon_neon_bg, R.mipmap.icon_blank_fg, R.string.AppIconNeonWin),
        MATTE_BLACK("MatteBlackIcon", R.mipmap.icon_matte_black_bg, R.mipmap.icon_blank_fg, R.string.AppIconMatteBlack),
        MATTE_BLACKWIN("MatteBlackWinIcon", R.mipmap.icon_matte_black_bg, R.mipmap.icon_blank_fg, R.string.AppIconMatteBlackWin),
        GLACIER("GlacierIcon", R.mipmap.icon_glacier_bg, R.mipmap.icon_blank_fg, R.string.AppIconGlacier),
        GLACIERWIN("GlacierWinIcon", R.mipmap.icon_glacier_bg, R.mipmap.icon_blank_fg, R.string.AppIconGlacierWin),
        LAVA("LavaIcon", R.mipmap.icon_lava_bg, R.mipmap.icon_blank_fg, R.string.AppIconLava),
        LAVAWIN("LavaWinIcon", R.mipmap.icon_lava_bg, R.mipmap.icon_blank_fg, R.string.AppIconLavaWin),
        PIXEL("PixelIcon", R.mipmap.icon_pixel_bg, R.mipmap.icon_blank_fg, R.string.AppIconPixel),
        PIXELWIN("PixelWinIcon", R.mipmap.icon_pixel_bg, R.mipmap.icon_blank_fg, R.string.AppIconPixelWin),
        ORIGAMI("OrigamiIcon", R.mipmap.icon_origami_bg, R.mipmap.icon_blank_fg, R.string.AppIconOrigami),
        ORIGAMIWIN("OrigamiWinIcon", R.mipmap.icon_origami_bg, R.mipmap.icon_blank_fg, R.string.AppIconOrigamiWin),

        // ParadoksGram: second ghost pack (9 designs x adaptive/Windows-style)
        WIRE_WHITE("WireWhiteIcon", R.mipmap.icon_wire_white_bg, R.mipmap.icon_blank_fg, R.string.AppIconWireWhite),
        WIRE_WHITEWIN("WireWhiteWinIcon", R.mipmap.icon_wire_white_bg, R.mipmap.icon_blank_fg, R.string.AppIconWireWhiteWin),
        WIRE_GOLD("WireGoldIcon", R.mipmap.icon_wire_gold_bg, R.mipmap.icon_blank_fg, R.string.AppIconWireGold),
        WIRE_GOLDWIN("WireGoldWinIcon", R.mipmap.icon_wire_gold_bg, R.mipmap.icon_blank_fg, R.string.AppIconWireGoldWin),
        GOLD_GLOW("GoldGlowIcon", R.mipmap.icon_gold_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconGoldGlow),
        GOLD_GLOWWIN("GoldGlowWinIcon", R.mipmap.icon_gold_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconGoldGlowWin),
        EMERALD_GLOW("EmeraldGlowIcon", R.mipmap.icon_emerald_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconEmeraldGlow),
        EMERALD_GLOWWIN("EmeraldGlowWinIcon", R.mipmap.icon_emerald_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconEmeraldGlowWin),
        VIOLET_GLOW("VioletGlowIcon", R.mipmap.icon_violet_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconVioletGlow),
        VIOLET_GLOWWIN("VioletGlowWinIcon", R.mipmap.icon_violet_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconVioletGlowWin),
        FROST_GLOW("FrostGlowIcon", R.mipmap.icon_frost_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconFrostGlow),
        FROST_GLOWWIN("FrostGlowWinIcon", R.mipmap.icon_frost_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconFrostGlowWin),
        SCARLET_GLOW("ScarletGlowIcon", R.mipmap.icon_scarlet_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconScarletGlow),
        SCARLET_GLOWWIN("ScarletGlowWinIcon", R.mipmap.icon_scarlet_glow_bg, R.mipmap.icon_blank_fg, R.string.AppIconScarletGlowWin),
        OBSIDIAN("ObsidianIcon", R.mipmap.icon_obsidian_bg, R.mipmap.icon_blank_fg, R.string.AppIconObsidian),
        OBSIDIANWIN("ObsidianWinIcon", R.mipmap.icon_obsidian_bg, R.mipmap.icon_blank_fg, R.string.AppIconObsidianWin),
        CLASSIC3("Classic3Icon", R.mipmap.icon_classic3_bg, R.mipmap.icon_blank_fg, R.string.AppIconClassic3),
        CLASSIC3WIN("Classic3WinIcon", R.mipmap.icon_classic3_bg, R.mipmap.icon_blank_fg, R.string.AppIconClassic3Win);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
