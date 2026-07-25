package com.leshao.v3.theme;

import android.graphics.Color;

public class MonetColorEngine {

    public enum Style {
        TONAL_SPOT,
        NEUTRAL,
        VIBRANT,
        EXPRESSIVE,
        RAINBOW,
        FRUIT_SALAD,
        MONOCHROME,
        FIDELITY;

        public static Style fromIndex(int idx) {
            Style[] vals = values();
            return vals[Math.max(0, Math.min(idx, vals.length - 1))];
        }

        public String displayName() {
            switch (this) {
                case TONAL_SPOT:   return "TonalSpot";
                case NEUTRAL:      return "Neutral";
                case VIBRANT:      return "Vibrant";
                case EXPRESSIVE:   return "Expressive";
                case RAINBOW:      return "Rainbow";
                case FRUIT_SALAD:  return "FruitSalad";
                case MONOCHROME:   return "Monochrome";
                case FIDELITY:     return "Fidelity";
            }
            return "TonalSpot";
        }
    }

    public static final int PALETTE_SIZE = 15;

    public static final int IDX_PRIMARY          = 0;
    public static final int IDX_PRIMARY_CONT     = 1;
    public static final int IDX_ON_PRIMARY_CONT  = 2;
    public static final int IDX_SECONDARY        = 3;
    public static final int IDX_SECONDARY_CONT   = 4;
    public static final int IDX_BACKGROUND       = 5;
    public static final int IDX_SURFACE          = 6;
    public static final int IDX_SURFACE_VARIANT  = 7;
    public static final int IDX_ON_SURFACE       = 8;
    public static final int IDX_ON_SURFACE_VAR   = 9;
    public static final int IDX_DARK_PRIMARY     = 10;
    public static final int IDX_WHITE            = 11;
    public static final int IDX_DARK_TEXT        = 12;
    public static final int IDX_MED_TEXT         = 13;
    public static final int IDX_PURE_WHITE       = 14;

    public static int[] generate(int seedColor, Style style) {
        float[] seedHsv = new float[3];
        Color.colorToHSV(seedColor, seedHsv);
        float seedH = seedHsv[0];
        float seedS = seedHsv[1];

        float[] hs = calcHS(seedH, seedS, style);
        return buildPalette(hs[0], hs[1], hs[2], hs[3], hs[4], hs[5], false);
    }

    public static int[] generateDark(int seedColor, Style style) {
        float[] seedHsv = new float[3];
        Color.colorToHSV(seedColor, seedHsv);
        float seedH = seedHsv[0];
        float seedS = seedHsv[1];

        float[] hs = calcHS(seedH, seedS, style);
        return buildPalette(hs[0], hs[1], hs[2], hs[3], hs[4], hs[5], true);
    }

    private static float[] calcHS(float seedH, float seedS, Style style) {
        float pH, pS, sH, sS, nH, nS;

        switch (style) {
            case NEUTRAL:
                pH = seedH; pS = 0.15f;
                sH = seedH; sS = 0.10f;
                nH = seedH; nS = 0.05f;
                break;
            case VIBRANT:
                pH = seedH; pS = Math.min(seedS * 1.3f, 1f);
                sH = seedH + 45f; sS = 0.7f;
                nH = seedH; nS = 0.06f;
                break;
            case EXPRESSIVE:
                pH = seedH + 30f; pS = 0.85f;
                sH = seedH + 60f; sS = 0.7f;
                nH = seedH + 15f; nS = 0.1f;
                break;
            case RAINBOW:
                pH = seedH; pS = 0.9f;
                sH = seedH + 60f; sS = 0.85f;
                nH = seedH + 120f; nS = 0.05f;
                break;
            case FRUIT_SALAD:
                pH = 20f; pS = 0.75f;
                sH = 160f; sS = 0.55f;
                nH = 40f; nS = 0.08f;
                break;
            case MONOCHROME:
                pH = seedH; pS = 0f;
                sH = seedH; sS = 0f;
                nH = seedH; nS = 0f;
                break;
            case FIDELITY:
                pH = seedH; pS = seedS;
                sH = seedH + 15f; sS = Math.min(seedS, 0.5f);
                nH = seedH; nS = Math.min(seedS * 0.3f, 0.15f);
                break;
            case TONAL_SPOT:
            default:
                pH = seedH; pS = clamp(seedS * 0.7f, 0.35f, 0.7f);
                sH = seedH + 30f; sS = clamp(seedS * 0.5f, 0.2f, 0.55f);
                nH = seedH; nS = Math.min(seedS * 0.15f, 0.1f);
                break;
        }

        return new float[]{hueNormalize(pH), pS, hueNormalize(sH), sS, hueNormalize(nH), nS};
    }

    private static int[] buildPalette(float pH, float pS, float sH, float sS, float nH, float nS, boolean dark) {
        int[] p = new int[PALETTE_SIZE];
        if (dark) {
            p[IDX_PRIMARY]          = hsvColor(pH, pS, 0.65f);
            p[IDX_PRIMARY_CONT]     = hsvColor(pH, pS * 0.5f, 0.20f);
            p[IDX_ON_PRIMARY_CONT]  = hsvColor(pH, pS * 0.35f, 0.90f);
            p[IDX_SECONDARY]        = hsvColor(sH, sS, 0.60f);
            p[IDX_SECONDARY_CONT]   = hsvColor(sH, sS * 0.45f, 0.18f);
            p[IDX_BACKGROUND]       = hsvColor(nH, nS, 0.10f);
            p[IDX_SURFACE]          = hsvColor(nH, nS * 1.5f, 0.15f);
            p[IDX_SURFACE_VARIANT]  = hsvColor(nH, nS * 2f, 0.20f);
            p[IDX_ON_SURFACE]       = hsvColor(nH, nS * 2f, 0.88f);
            p[IDX_ON_SURFACE_VAR]   = hsvColor(nH, nS * 2f, 0.70f);
            p[IDX_DARK_PRIMARY]     = hsvColor(pH, pS, 0.55f);
            p[IDX_WHITE]            = hsvColor(pH, pS * 0.15f, 0.92f);
            p[IDX_DARK_TEXT]        = hsvColor(nH, nS * 2f, 0.93f);
            p[IDX_MED_TEXT]         = hsvColor(nH, nS * 2f, 0.75f);
            p[IDX_PURE_WHITE]       = 0xFFE8E8F0;
        } else {
            p[IDX_PRIMARY]          = hsvColor(pH, pS, 0.50f);
            p[IDX_PRIMARY_CONT]     = hsvColor(pH, pS * 0.5f, 0.95f);
            p[IDX_ON_PRIMARY_CONT]  = hsvColor(pH, pS * 0.35f, 0.10f);
            p[IDX_SECONDARY]        = hsvColor(sH, sS, 0.55f);
            p[IDX_SECONDARY_CONT]   = hsvColor(sH, sS * 0.45f, 0.92f);
            p[IDX_BACKGROUND]       = hsvColor(nH, nS, 0.98f);
            p[IDX_SURFACE]          = hsvColor(nH, nS * 1.5f, 0.96f);
            p[IDX_SURFACE_VARIANT]  = hsvColor(nH, nS * 2f, 0.91f);
            p[IDX_ON_SURFACE]       = hsvColor(nH, nS * 2f, 0.10f);
            p[IDX_ON_SURFACE_VAR]   = hsvColor(nH, nS * 2f, 0.38f);
            p[IDX_DARK_PRIMARY]     = hsvColor(pH, pS, 0.40f);
            p[IDX_WHITE]            = hsvColor(pH, pS * 0.15f, 0.98f);
            p[IDX_DARK_TEXT]        = hsvColor(nH, nS * 2f, 0.07f);
            p[IDX_MED_TEXT]         = hsvColor(nH, nS * 2f, 0.45f);
            p[IDX_PURE_WHITE]       = 0xFFFFFFFF;
        }
        return p;
    }

    public static void applyPalette(int[] lightPalette, int[] darkPalette) {
        if (lightPalette == null || lightPalette.length < PALETTE_SIZE) return;
        if (darkPalette == null || darkPalette.length < PALETTE_SIZE) return;
        com.leshao.v3.hook.ThemeHook.applyMonetPalette(lightPalette, darkPalette);
    }

    private static int hsvColor(float h, float s, float v) {
        float[] hsv = {hueNormalize(h), clamp(s, 0f, 1f), clamp(v, 0f, 1f)};
        return Color.HSVToColor(hsv);
    }

    private static float hueNormalize(float h) {
        return ((h % 360f) + 360f) % 360f;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(v, max));
    }
}
