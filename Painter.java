package com.roohu.clipforge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/** Draws every overlay (animated captions, the RoohuXKhan badge, progress bar, vignette) as bitmaps. */
final class Painter {
    static final int ACCENT = 0xFFFFC83D;
    static final int ORANGE = 0xFFFF8F3F;
    static final int PINK = 0xFFFF3D81;
    static final int CYAN = 0xFF5FE3F0;

    /** One spoken word with its start/end time in seconds (source video time). */
    static final class W {
        final String w; final double a, b;
        W(String w, double a, double b) { this.w = w; this.a = a; this.b = b; }
    }

    /** A caption page: a few words shown together. */
    static final class Cue {
        final double a, b; final List<W> words = new ArrayList<>();
        Cue(double a, double b) { this.a = a; this.b = b; }
    }

    static Typeface face(String font) {
        if (font == null) font = "";
        switch (font) {
            case "Anton": case "Bebas Neue": case "Oswald":
                return Typeface.create("sans-serif-condensed", Typeface.BOLD);
            case "Playfair Display":
                return Typeface.create(Typeface.SERIF, Typeface.BOLD);
            case "Bangers":
                return Typeface.create("casual", Typeface.BOLD);
            default:
                return Typeface.create("sans-serif-black", Typeface.NORMAL);
        }
    }

    static Bitmap transparent() {
        return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    }

    /** Caption bitmap: styles pop / kara / bar / line. `active` is the spoken word, `pop` is 0..1 bounce progress. */
    static Bitmap caption(int canvasW, List<String> words, int active, float pop, String style, Typeface tf) {
        float fs = canvasW * 0.085f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(fs);
        float maxW = canvasW * 0.88f, space = p.measureText(" ");
        float[] ww = new float[words.size()];
        List<List<Integer>> lines = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        float curW = 0;
        for (int i = 0; i < ww.length; i++) {
            ww[i] = p.measureText(words.get(i));
            float add = (cur.isEmpty() ? 0 : space) + ww[i];
            if (!cur.isEmpty() && curW + add > maxW) {
                lines.add(cur); cur = new ArrayList<>(); curW = 0; add = ww[i];
            }
            cur.add(i); curW += add;
        }
        if (!cur.isEmpty()) lines.add(cur);
        float lh = fs * 1.3f;
        int h = (int) Math.ceil(lines.size() * lh + fs * 0.9f);
        Bitmap bmp = Bitmap.createBitmap(canvasW, Math.max(h, 8), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setTypeface(tf); stroke.setTextSize(fs);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(fs * 0.2f); stroke.setColor(Color.BLACK);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setTypeface(tf); fill.setTextSize(fs);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xB3000000);

        float y = fs * 1.05f;
        for (List<Integer> line : lines) {
            float lw = 0;
            for (int k = 0; k < line.size(); k++) lw += ww[line.get(k)] + (k > 0 ? space : 0);
            float x = (canvasW - lw) / 2f;
            if ("bar".equals(style)) {
                c.drawRoundRect(new RectF(x - fs * 0.4f, y - fs * 0.95f, x + lw + fs * 0.4f, y + fs * 0.35f), fs * 0.3f, fs * 0.3f, bg);
            }
            for (int k = 0; k < line.size(); k++) {
                int i = line.get(k);
                boolean act = i == active;
                float sc = act ? 1f + 0.28f * (float) Math.sin(Math.min(1f, pop) * Math.PI) : 1f;
                int color = Color.WHITE;
                if ("kara".equals(style)) color = i <= active ? ACCENT : Color.WHITE;
                else if (act) color = ACCENT;
                c.save();
                c.scale(sc, sc, x + ww[i] / 2f, y - fs * 0.3f);
                if (!"bar".equals(style)) c.drawText(words.get(i), x, y, stroke);
                fill.setColor(color);
                c.drawText(words.get(i), x, y, fill);
                c.restore();
                x += ww[i] + space;
            }
            y += lh;
        }
        return bmp;
    }

    /** Styled RoohuXKhan badge. shimmer: 0..1 sweep position, or -1 for none. */
    static Bitmap watermark(int canvasW, float sizePct, float shimmer) {
        float fs = canvasW * 0.052f * sizePct / 100f;
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
        t.setTextSize(fs);
        String a = "Roohu", x = "X", k = "Khan";
        float wa = t.measureText(a), wx = t.measureText(x), wk = t.measureText(k);
        float pad = fs * 0.55f, icon = fs, gap = fs * 0.35f;
        int bw = (int) Math.ceil(pad + icon + gap + wa + wx + wk + pad);
        int bh = (int) Math.ceil(fs * 1.75f);
        Bitmap bmp = Bitmap.createBitmap(bw + 8, bh + 8, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.translate(4, 4);
        RectF r = new RectF(0, 0, bw, bh);
        float rad = bh / 2f;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xB30B1A1F);
        c.drawRoundRect(r, rad, rad, bg);

        Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG);
        bd.setStyle(Paint.Style.STROKE);
        bd.setStrokeWidth(Math.max(2f, fs * 0.07f));
        bd.setShader(new LinearGradient(0, 0, bw, bh, ORANGE, PINK, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, bd);

        float cx = pad + icon / 2f, cy = bh / 2f;
        Paint ic = new Paint(Paint.ANTI_ALIAS_FLAG);
        ic.setShader(new LinearGradient(cx - icon / 2, cy - icon / 2, cx + icon / 2, cy + icon / 2, ORANGE, PINK, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, icon / 2f, ic);
        Path tri = new Path();
        float ts = icon * 0.22f;
        tri.moveTo(cx - ts * 0.7f, cy - ts * 1.2f);
        tri.lineTo(cx + ts * 1.3f, cy);
        tri.lineTo(cx - ts * 0.7f, cy + ts * 1.2f);
        tri.close();
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        c.drawPath(tri, tp);

        float tx = pad + icon + gap, ty = bh / 2f + fs * 0.34f;
        t.setShadowLayer(fs * 0.15f, 0, fs * 0.05f, 0x99000000);
        t.setColor(Color.WHITE);
        c.drawText(a, tx, ty, t);
        Paint tx2 = new Paint(t);
        tx2.setShader(new LinearGradient(tx + wa, 0, tx + wa + wx, 0, ORANGE, PINK, Shader.TileMode.CLAMP));
        c.drawText(x, tx + wa, ty, tx2);
        t.setColor(CYAN);
        c.drawText(k, tx + wa + wx, ty, t);

        if (shimmer >= 0f) {
            c.save();
            Path clip = new Path();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            float sx = -bh + (bw + bh * 2f) * shimmer;
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setShader(new LinearGradient(sx, 0, sx + bh, bh, new int[]{0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRect(r, sp);
            c.restore();
        }
        return bmp;
    }

    static Bitmap progress(int w, int frameH, float p) {
        int h = Math.max(6, (int) (frameH * 0.006f));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint(); bg.setColor(0x40FFFFFF);
        c.drawRect(0, 0, w, h, bg);
        Paint fg = new Paint(); fg.setColor(ORANGE);
        c.drawRect(0, 0, w * Math.max(0f, Math.min(1f, p)), h, fg);
        return b;
    }

    static Bitmap vignette(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(w / 2f, h / 2f, h * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x59000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    // ------------------------------------------------------------------ reaction look (Phase 1)

    /** Opaque black bars top and bottom with a crisp glowing divider on each inner edge. Full-frame bitmap. */
    static Bitmap reactFrame(int W, int H, float topFrac, float botFrac) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int top = Math.round(H * topFrac), bot = Math.round(H * botFrac);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        c.drawRect(0, 0, W, top, p);
        c.drawRect(0, H - bot, W, H, p);
        glowLine(c, W, top, W * 0.012f, 0xB0FFFFFF);
        glowLine(c, W, H - bot, W * 0.012f, 0xB0FFFFFF);
        float core = Math.max(3f, W * 0.006f);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.WHITE);
        c.drawRect(0, top - core / 2f, W, top + core / 2f, line);
        c.drawRect(0, H - bot - core / 2f, W, H - bot + core / 2f, line);
        return bmp;
    }

    /** Wide soft bloom around both dividers (transparent elsewhere). Its opacity is pulsed on loud moments. */
    static Bitmap reactGlow(int W, int H, float topFrac, float botFrac) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        glowLine(c, W, Math.round(H * topFrac), W * 0.06f, 0x90BFE9FF);
        glowLine(c, W, H - Math.round(H * botFrac), W * 0.06f, 0x90BFE9FF);
        return bmp;
    }

    private static void glowLine(Canvas c, int W, float y, float half, int peak) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, y - half, 0, y + half,
                new int[]{peak & 0x00FFFFFF, peak, peak & 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, y - half, W, y + half, p);
    }

    /** Splits a hook into at most two balanced lines. */
    private static String[] splitTwo(String s) {
        String[] w = s.split("\\s+");
        if (w.length < 3 || s.length() <= 16) return new String[]{s};
        int best = 1, bestDiff = Integer.MAX_VALUE;
        for (int i = 1; i < w.length; i++) {
            int a = 0, b = 0;
            for (int k = 0; k < i; k++) a += w[k].length() + 1;
            for (int k = i; k < w.length; k++) b += w[k].length() + 1;
            if (Math.abs(a - b) < bestDiff) { bestDiff = Math.abs(a - b); best = i; }
        }
        StringBuilder l1 = new StringBuilder(), l2 = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            StringBuilder t = i < best ? l1 : l2;
            if (t.length() > 0) t.append(' ');
            t.append(w[i]);
        }
        return new String[]{l1.toString(), l2.toString()};
    }

    /** Bold 3D hook title (red first line, yellow second) shown in the top bar. maxH keeps it inside the bar. */
    static Bitmap hookTitle(int W, float maxH, String text) {
        String up = text.trim().toUpperCase(java.util.Locale.ROOT);
        if (up.length() > 44) up = up.substring(0, 43).trim() + "\u2026";
        String[] lines = splitTwo(up);
        Typeface tf = face("Anton");
        float fs = W * 0.088f, maxW = W * 0.92f;
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setTypeface(tf);
        for (int i = 0; i < 30; i++) {
            m.setTextSize(fs);
            float wmax = 0;
            for (String l : lines) wmax = Math.max(wmax, m.measureText(l));
            if (wmax <= maxW && lines.length * fs * 1.28f + fs * 0.3f <= maxH) break;
            fs *= 0.93f;
        }
        int depth = Math.max(3, Math.round(fs * 0.10f));
        int h = (int) Math.ceil(lines.length * fs * 1.28f + fs * 0.3f + depth);
        Bitmap bmp = Bitmap.createBitmap(W, Math.max(h, 8), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        m.setTextSize(fs);
        for (int i = 0; i < lines.length; i++) {
            float x = (W - m.measureText(lines[i])) / 2f, y = fs * 1.05f + i * fs * 1.28f;
            if (i == 0 && lines.length > 1) text3d(c, lines[i], x, y, tf, fs, 0xFFFF6A4D, 0xFFD3162B, 0xFF5B0A12, depth);
            else text3d(c, lines[i], x, y, tf, fs, 0xFFFFEE70, 0xFFFFB300, 0xFF6A4300, depth);
        }
        return bmp;
    }

    /** 3D-look text: stacked extrusion, black outline, gradient face and a top specular highlight. */
    static void text3d(Canvas c, String s, float x, float y, Typeface tf, float fs, int top, int bottom, int side, int depth) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf); p.setTextSize(fs);
        p.setStyle(Paint.Style.FILL_AND_STROKE); p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(fs * 0.06f); p.setColor(side);
        for (int i = depth; i >= 1; i--) c.drawText(s, x + i * 0.55f, y + i * 0.95f, p);
        Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
        o.setTypeface(tf); o.setTextSize(fs);
        o.setStyle(Paint.Style.STROKE); o.setStrokeJoin(Paint.Join.ROUND);
        o.setStrokeWidth(fs * 0.17f); o.setColor(Color.BLACK);
        c.drawText(s, x, y, o);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setTypeface(tf); f.setTextSize(fs);
        f.setShader(new LinearGradient(0, y - fs * 0.85f, 0, y + fs * 0.1f, top, bottom, Shader.TileMode.CLAMP));
        c.drawText(s, x, y, f);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        g.setTypeface(tf); g.setTextSize(fs);
        g.setShader(new LinearGradient(0, y - fs * 0.85f, 0, y - fs * 0.38f, 0x80FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawText(s, x, y, g);
    }
}
