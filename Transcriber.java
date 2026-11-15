package com.roohu.clipforge;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Decodes a clip's audio to 16 kHz mono and runs offline recognition, returning caption pages with per-word timing. */
final class Transcriber {
    interface Prog { void on(int pct); }

    private static Model model;
    private static String modelKey;

    private static synchronized Model model(Context ctx, String key) throws IOException {
        if (model != null && key.equals(modelKey)) return model;
        if (model != null) { model.close(); model = null; }
        File dir = new File(ctx.getFilesDir(), "vosk/model-" + key);
        File ready = new File(dir, ".ready");
        if (!ready.exists()) {
            AssetManager am = ctx.getAssets();
            String src = "model-" + key;
            String[] top = am.list(src);
            if (top == null || top.length == 0)
                throw new IOException("The offline speech model (" + key + ") is not inside this APK. Build it with the GitHub workflow, which downloads the model.");
            deleteRec(dir);
            copyAssets(am, src, dir);
            if (!ready.createNewFile()) throw new IOException("Could not prepare the speech model.");
        }
        model = new Model(dir.getAbsolutePath());
        modelKey = key;
        return model;
    }

    private static void copyAssets(AssetManager am, String path, File dst) throws IOException {
        String[] kids = am.list(path);
        if (kids != null && kids.length > 0) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create " + dst);
            for (String k : kids) copyAssets(am, path + "/" + k, new File(dst, k));
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRec(File f) {
        if (!f.exists()) return;
        File[] k = f.listFiles();
        if (k != null) for (File c : k) deleteRec(c);
        f.delete();
    }

    /** Returns a JSON array: [{a,b,t,w:[[word,start,end],...]}] in source-video seconds. */
    static String run(Context ctx, Uri uri, double startS, double endS, String lang, Prog prog) throws Exception {
        String key = lang.startsWith("en") ? "en" : (lang.startsWith("hi") || lang.startsWith("ur")) ? "hi" : null;
        if (key == null)
            throw new IOException("Offline captions support English and Hindi/Urdu speech. For this language, import an .srt file.");
        Model m = model(ctx, key);
        List<Painter.W> words = new ArrayList<>();
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        Recognizer rec = null;
        try {
            rec = new Recognizer(m, 16000f);
            rec.setWords(true);
            ex.setDataSource(ctx, uri, null);
            int ai = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { ai = i; break; }
            }
            if (ai < 0) throw new IOException("This video has no audio track to caption.");
            ex.selectTrack(ai);
            MediaFormat f = ex.getTrackFormat(ai);
            long startUs = (long) (startS * 1e6), endUs = (long) (endS * 1e6);
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            int sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
            dec.configure(f, null, null, 0);
            dec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            double base = -1;
            double step = sr / 16000.0, nextOut = step, acc = 0;
            long idx = 0; int cnt = 0;
            byte[] chunk = new byte[8000];
            int cl = 0;
            int lastPct = -1;
            while (!outDone) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ex.readSampleData(ib, 0);
                        long st = ex.getSampleTime();
                        if (n < 0 || st > endUs) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, st, 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    step = sr / 16000.0;
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        if (base < 0) base = info.presentationTimeUs / 1e6;
                        ByteBuffer ob = dec.getOutputBuffer(oi);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        short[] pcm = new short[info.size / 2];
                        ob.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm);
                        for (int p = 0; p + ch <= pcm.length; p += ch) {
                            int s = 0;
                            for (int c = 0; c < ch; c++) s += pcm[p + c];
                            acc += s / (double) ch; cnt++; idx++;
                            if (idx >= nextOut) {
                                short o = (short) (acc / Math.max(1, cnt));
                                acc = 0; cnt = 0; nextOut += step;
                                chunk[cl++] = (byte) (o & 0xFF);
                                chunk[cl++] = (byte) ((o >> 8) & 0xFF);
                                if (cl == chunk.length) {
                                    if (rec.acceptWaveForm(chunk, cl)) collect(rec.getResult(), base, words);
                                    cl = 0;
                                }
                            }
                        }
                        int pct = (int) Math.max(0, Math.min(99, (info.presentationTimeUs - startUs) * 100.0 / Math.max(1, endUs - startUs)));
                        if (pct != lastPct) { lastPct = pct; prog.on(pct); }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    dec.releaseOutputBuffer(oi, false);
                }
            }
            if (cl > 0) {
                if (rec.acceptWaveForm(chunk, cl)) collect(rec.getResult(), base, words);
            }
            collect(rec.getFinalResult(), Math.max(0, base), words);
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) { }
            try { ex.release(); } catch (Exception ignored) { }
            try { if (rec != null) rec.close(); } catch (Exception ignored) { }
        }
        // keep only words inside the clip window
        List<Painter.W> inside = new ArrayList<>();
        for (Painter.W w : words) if (w.a >= startS - 0.15 && w.a <= endS) inside.add(w);
        return group(inside).toString();
    }

    private static void collect(String json, double base, List<Painter.W> out) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray r = o.optJSONArray("result");
        if (r == null) return;
        for (int i = 0; i < r.length(); i++) {
            JSONObject w = r.getJSONObject(i);
            out.add(new Painter.W(w.getString("word"), base + w.getDouble("start"), base + w.getDouble("end")));
        }
    }

    /** Pages of up to 4 words; a new page also starts after a pause or when a page runs long. */
    private static JSONArray group(List<Painter.W> ws) throws Exception {
        JSONArray out = new JSONArray();
        List<Painter.W> cur = new ArrayList<>();
        for (int i = 0; i <= ws.size(); i++) {
            boolean flush = i == ws.size();
            if (!flush && !cur.isEmpty()) {
                Painter.W prev = cur.get(cur.size() - 1), w = ws.get(i);
                if (cur.size() >= 4 || w.a - prev.b > 0.7 || w.b - cur.get(0).a > 2.4) flush = true;
            }
            if (flush && !cur.isEmpty()) {
                JSONObject c = new JSONObject();
                JSONArray wa = new JSONArray();
                StringBuilder t = new StringBuilder();
                for (Painter.W w : cur) {
                    JSONArray one = new JSONArray();
                    one.put(w.w).put(w.a).put(w.b);
                    wa.put(one);
                    if (t.length() > 0) t.append(' ');
                    t.append(w.w);
                }
                c.put("a", cur.get(0).a);
                c.put("b", cur.get(cur.size() - 1).b);
                c.put("t", t.toString());
                c.put("w", wa);
                out.put(c);
                cur = new ArrayList<>();
            }
            if (i < ws.size()) cur.add(ws.get(i));
        }
        return out;
    }
}
