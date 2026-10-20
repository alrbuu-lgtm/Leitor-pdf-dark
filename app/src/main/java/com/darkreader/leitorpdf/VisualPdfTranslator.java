package com.darkreader.leitorpdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import com.google.mlkit.nl.translate.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Tradução local visual (inglês -> português), sem modificar o documento PDF.
 *
 * O reconhecimento usa cópia da imagem para não acessar Bitmaps descartados ao
 * mudar de página. A tradução ocorre por blocos, desenhados nas coordenadas
 * originais. PDF complexo, colunas e fórmulas não têm garantia de fidelidade.
 */
public final class VisualPdfTranslator implements AutoCloseable {
    public interface Callback {
        void onStatus(String status);
        void onTranslated(Bitmap translated, int blocks);
        void onError(String message);
    }

    private static final int OCR_MAX_EDGE = 1800;
    private static final int MAX_BLOCKS = 130;
    private final TextRecognizer recognizer;
    private final Translator translator;
    private int generation;
    private boolean closed;

    private static final class Area {
        final Rect rect;
        final String original;
        final int lines;

        Area(Rect rect, String original, int lines) {
            this.rect = rect;
            this.original = original;
            this.lines = lines;
        }
    }

    public VisualPdfTranslator() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build();
        translator = Translation.getClient(options);
    }

    public void cancel() {
        generation++;
    }

    private boolean active(int request) {
        return !closed && request == generation;
    }

    public void translatePage(Bitmap page, Callback callback) {
        if (closed || page == null || page.isRecycled()) {
            callback.onError("Página indisponível.");
            return;
        }
        final int request = ++generation;
        callback.onStatus("Preparando modelo EN → PT…");
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    if (active(request)) recognizePage(page, request, callback);
                })
                .addOnFailureListener(error -> {
                    if (active(request)) {
                        callback.onError("Modelo EN–PT indisponível. Conecte-se à internet para o primeiro download.");
                    }
                });
    }

    private void recognizePage(Bitmap original, int request, Callback callback) {
        if (!active(request) || original.isRecycled()) return;
        callback.onStatus("Reconhecendo o texto da página…");

        float factor = Math.min(1f, (float) OCR_MAX_EDGE /
                Math.max(original.getWidth(), original.getHeight()));
        int scanWidth = Math.max(1, Math.round(original.getWidth() * factor));
        int scanHeight = Math.max(1, Math.round(original.getHeight() * factor));
        final Bitmap scanned;
        try {
            scanned = factor >= 1f
                    ? original.copy(Bitmap.Config.ARGB_8888, false)
                    : Bitmap.createScaledBitmap(original, scanWidth, scanHeight, true);
        } catch (Exception e) {
            callback.onError("Memória insuficiente para reconhecer a página.");
            return;
        }
        if (scanned == null) {
            callback.onError("Não foi possível preparar o reconhecimento.");
            return;
        }

        recognizer.process(InputImage.fromBitmap(scanned, 0))
                .addOnCompleteListener(task -> {
                    if (!active(request)) {
                        scanned.recycle();
                        return;
                    }
                    if (!task.isSuccessful() || task.getResult() == null) {
                        scanned.recycle();
                        callback.onError("O reconhecimento do texto falhou nesta página.");
                        return;
                    }
                    Text result = task.getResult();
                    List<Area> areas = collectAreas(result, original.getWidth(), original.getHeight(),
                            scanned.getWidth(), scanned.getHeight());
                    scanned.recycle();

                    if (areas.isEmpty()) {
                        callback.onError("Não encontrei texto reconhecível para traduzir nesta página.");
                        return;
                    }
                    if (!active(request) || original.isRecycled()) return;
                    final Bitmap translated;
                    try {
                        translated = original.copy(Bitmap.Config.ARGB_8888, true);
                    } catch (Exception e) {
                        callback.onError("Memória insuficiente para montar a tradução.");
                        return;
                    }
                    if (translated == null) {
                        callback.onError("Não foi possível preparar a página traduzida.");
                        return;
                    }
                    callback.onStatus("Traduzindo " + areas.size() + " blocos…");
                    translateNext(areas, 0, 0, translated, request, callback);
                });
    }

    private List<Area> collectAreas(Text recognized, int outWidth, int outHeight,
                                    int ocrWidth, int ocrHeight) {
        List<Area> areas = new ArrayList<>();
        final float scaleX = outWidth / (float) ocrWidth;
        final float scaleY = outHeight / (float) ocrHeight;
        for (Text.TextBlock block : recognized.getTextBlocks()) {
            if (areas.size() >= MAX_BLOCKS) break;
            String original = block.getText() == null ? "" : block.getText().trim();
            Rect r = block.getBoundingBox();
            if (r == null || original.length() < 2 || !original.matches("(?s).*\\p{L}.*")) continue;
            // Não passar blocos longuíssimos/ilegíveis ao tradutor.
            if (original.length() > 1800) continue;
            String language = block.getRecognizedLanguage();
            if ("pt".equalsIgnoreCase(language)) continue;
            Rect mapped = new Rect(
                    Math.max(0, Math.round(r.left * scaleX)),
                    Math.max(0, Math.round(r.top * scaleY)),
                    Math.min(outWidth, Math.round(r.right * scaleX)),
                    Math.min(outHeight, Math.round(r.bottom * scaleY)));
            if (mapped.width() < 5 || mapped.height() < 5) continue;
            areas.add(new Area(mapped, original, Math.max(1, block.getLines().size())));
        }
        return areas;
    }

    private void translateNext(List<Area> areas, int position, int count,
                               Bitmap output, int request, Callback callback) {
        if (!active(request)) {
            if (!output.isRecycled()) output.recycle();
            return;
        }
        if (position >= areas.size()) {
            if (count == 0) {
                output.recycle();
                callback.onError("Não foi possível traduzir os blocos reconhecidos.");
            } else {
                callback.onTranslated(output, count);
            }
            return;
        }
        Area area = areas.get(position);
        translator.translate(area.original)
                .addOnCompleteListener(task -> {
                    if (!active(request)) {
                        if (!output.isRecycled()) output.recycle();
                        return;
                    }
                    int done = count;
                    if (task.isSuccessful() && task.getResult() != null
                            && !task.getResult().trim().isEmpty()) {
                        paintTranslation(output, area, task.getResult());
                        done++;
                    }
                    if ((position + 1) % 12 == 0) {
                        callback.onStatus("Traduzindo blocos " + (position + 1) + "/" + areas.size());
                    }
                    translateNext(areas, position + 1, done, output, request, callback);
                });
    }

    private void paintTranslation(Bitmap bitmap, Area area, String text) {
        Rect r = area.rect;
        if (r.width() < 8 || r.height() < 8) return;
        Canvas canvas = new Canvas(bitmap);
        float pad = Math.min(5f, Math.max(1f, r.width() / 55f));
        int maxW = Math.max(1, Math.round(r.width() - 2f * pad));
        int maxH = Math.max(1, Math.round(r.height() - 2f * pad));
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        float textSize = Math.min(48f, Math.max(9f, r.height() / (float) area.lines * 0.85f));
        StaticLayout layout = null;
        for (float size = textSize; size >= 7f; size -= 1f) {
            textPaint.setTextSize(size);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, maxW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .build();
            if (layout.getHeight() <= maxH) break;
        }
        if (layout == null) return;

        // A camada do texto pode ocultar textura ou cor da área original, mas
        // evita deslocar ilustrações/tabelas fora da caixa reconhecida.
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        int sample = bitmap.getPixel(Math.min(bitmap.getWidth()-1, Math.max(0, r.left + 2)),
                Math.min(bitmap.getHeight()-1, Math.max(0, r.top + 2)));
        int luminance = (Color.red(sample)*299 + Color.green(sample)*587
                + Color.blue(sample)*114)/1000;
        boolean light = luminance >= 125;
        if (light) bg.setColor(Color.rgb(250,250,250));
        else bg.setColor(Color.rgb(28,30,36));

        canvas.drawRect(r, bg);
        textPaint.setColor(light ? Color.BLACK : Color.WHITE);
        canvas.save();
        canvas.clipRect(r);
        canvas.translate(r.left + pad, r.top + pad);
        layout.draw(canvas);
        canvas.restore();
    }

    @Override public void close() {
        cancel();
        closed = true;
        recognizer.close();
        translator.close();
    }
}
