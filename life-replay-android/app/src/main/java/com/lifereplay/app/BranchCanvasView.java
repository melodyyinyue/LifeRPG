package com.lifereplay.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class BranchCanvasView extends View {
    public interface Listener {
        void onNodeSelected(String id);
        void onExplore(String parentId, int option);
    }

    private static final float CARD_W = 220f, CARD_H = 165f, COLUMN = 316f, ROW = 207f;
    private static final float MIN_ZOOM = .18f, MAX_ZOOM = 2.5f;
    private static final int BG = 0xff090e1e, SURFACE = 0xff141d32;
    private static final int MINT = 0xff8cf4cb, TEXT = 0xffedf3ff, MUTED = 0xff91a1bd;
    private final Listener listener;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF(), viewport = new RectF(), bounds = new RectF();
    private final Rect sourceRect = new Rect();
    private final DashPathEffect dash = new DashPathEffect(new float[]{6, 6}, 0);
    private final List<Node> nodes = new ArrayList<>();
    private final List<Node> roots = new ArrayList<>();
    private final List<Exit> exits = new ArrayList<>();
    private final Map<String, Node> byId = new HashMap<>();
    private final Set<String> selectedPath = new HashSet<>();
    private final Set<String> pendingImages = new HashSet<>(), failedImages = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };
    private final ScaleGestureDetector scaleDetector;
    private final int touchSlop;
    private ThreadPoolExecutor decoder;
    private volatile boolean detached;
    private int imageGeneration;
    private boolean imagesScheduled, positioned, fitOnSize;
    private String selectedId = "", currentId = "";
    private float zoom = 1f, panX, panY;
    private ValueAnimator panAnimator;
    private float downX, downY, lastX, lastY, scaleFocusX, scaleFocusY;
    private int pointerId = -1;
    private boolean moved, multiTouch;

    private static final class Node {
        String id, parentId, title, imageKey, imageStatus;
        int option, depth, level;
        float x, y;
        Node parent;
        final List<Node> children = new ArrayList<>();
        final List<String> options = new ArrayList<>();
    }

    private static final class Exit {
        Node parent;
        int option;
        String title;
        float x, y;
    }

    public BranchCanvasView(Context context, JSONObject story, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                        cancelPanAnimation();
                        multiTouch = true;
                        scaleFocusX = detector.getFocusX();
                        scaleFocusY = detector.getFocusY();
                        return true;
                    }
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float oldScale = scale();
                        float worldX = (scaleFocusX - panX) / oldScale;
                        float worldY = (scaleFocusY - panY) / oldScale;
                        zoom = clampZoom(zoom * detector.getScaleFactor());
                        scaleFocusX = detector.getFocusX();
                        scaleFocusY = detector.getFocusY();
                        panX = scaleFocusX - worldX * scale();
                        panY = scaleFocusY - worldY * scale();
                        invalidate();
                        return true;
                    }
                });
        scaleDetector.setQuickScaleEnabled(false);
        startDecoder();
        setStory(story);
    }

    public void setStory(JSONObject story) {
        cancelPanAnimation();
        imageGeneration++;
        if (decoder != null) decoder.getQueue().clear();
        pendingImages.clear();
        failedImages.clear();
        nodes.clear();
        roots.clear();
        byId.clear();
        currentId = story == null ? "" : story.optString("current", "");
        JSONArray data = story == null ? null : story.optJSONArray("nodes");
        boolean demo = story != null && story.optBoolean("demo", false);
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject value = data.optJSONObject(i);
                if (value == null) continue;
                String id = value.optString("id", "");
                if (id.isEmpty() || byId.containsKey(id)) continue;
                Node node = new Node();
                node.id = id;
                node.parentId = value.optString("parent", "");
                node.title = value.optString("title", "未命名场景");
                node.option = value.optInt("option", -1);
                node.depth = Math.max(0, value.optInt("depth", 0));
                node.imageStatus = value.optString("imageStatus", "pending");
                String filename = value.optString("imagePath", "");
                String art = value.optString("demoArt", "");
                node.imageKey = "";
                if (!filename.isEmpty()) {
                    node.imageKey = filename.matches("[a-fA-F0-9-]+\\.img")
                            ? "file:" + filename : "invalid:" + id;
                } else if (demo && (art.equals("rain_city") || art.equals("rain_archive")
                        || art.equals("rain_rooftop") || art.equals("rain_station"))) {
                    node.imageKey = "asset:scenes/" + art + ".jpg";
                }
                JSONArray options = value.optJSONArray("options");
                if (options != null) {
                    for (int j = 0; j < Math.min(3, options.length()); j++) {
                        node.options.add(options.optString(j, "继续探索"));
                    }
                }
                nodes.add(node);
                byId.put(id, node);
            }
        }
        // Resolve the parent graph without recursive calls, including malformed cyclic saves.
        Set<String> resolved = new HashSet<>();
        for (Node start : nodes) {
            if (resolved.contains(start.id)) continue;
            List<Node> chain = new ArrayList<>();
            Set<String> visiting = new HashSet<>();
            Node cursor = start;
            while (cursor != null && !resolved.contains(cursor.id)) {
                visiting.add(cursor.id);
                chain.add(cursor);
                Node parent = byId.get(cursor.parentId);
                if (parent != null && visiting.contains(parent.id)) parent = null;
                cursor.parent = parent;
                cursor = parent;
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                Node node = chain.get(i);
                node.level = node.parent == null ? 0 : node.parent.level + 1;
                resolved.add(node.id);
            }
        }
        for (Node node : nodes) {
            if (node.parent == null) roots.add(node);
            else node.parent.children.add(node);
        }
        for (Node node : nodes) {
            Collections.sort(node.children, Comparator.comparingInt(child -> child.option));
        }
        if (!byId.containsKey(selectedId)) {
            selectedId = byId.containsKey(currentId) ? currentId : nodes.isEmpty() ? "" : nodes.get(0).id;
        }
        layoutTree();
        updateDescription();
        if (!positioned && getWidth() > 0 && !nodes.isEmpty()) focusCurrent();
        scheduleImages();
        invalidate();
    }

    private void layoutTree() {
        exits.clear();
        selectedPath.clear();
        Node selection = byId.get(selectedId);
        for (Node node = selection; node != null && selectedPath.add(node.id); node = node.parent) { }
        if (selection != null) {
            for (int option = 0; option < selection.options.size(); option++) {
                boolean explored = false;
                for (Node child : selection.children) {
                    if (child.option == option) { explored = true; break; }
                }
                if (!explored) {
                    Exit exit = new Exit();
                    exit.parent = selection;
                    exit.option = option;
                    exit.title = selection.options.get(option);
                    exits.add(exit);
                }
            }
        }
        // Iterative post-order gives each leaf a lane and centers parents over their children.
        ArrayDeque<Object> stack = new ArrayDeque<>();
        Set<String> entered = new HashSet<>();
        float lane = 0;
        for (Node root : roots) {
            stack.push(root);
            while (!stack.isEmpty()) {
                Object item = stack.peek();
                if (item instanceof Exit) {
                    Exit exit = (Exit) stack.pop();
                    exit.x = (exit.parent.level + 1) * COLUMN;
                    exit.y = lane * ROW + (CARD_H - 72) / 2;
                    lane++;
                    continue;
                }
                Node node = (Node) item;
                if (entered.add(node.id)) {
                    List<Object> branches = new ArrayList<>(node.children);
                    if (node == selection) branches.addAll(exits);
                    Collections.sort(branches, (a, b) -> Integer.compare(
                            a instanceof Node ? ((Node) a).option : ((Exit) a).option,
                            b instanceof Node ? ((Node) b).option : ((Exit) b).option));
                    for (int i = branches.size() - 1; i >= 0; i--) stack.push(branches.get(i));
                } else {
                    stack.pop();
                    float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                    for (Node child : node.children) {
                        min = Math.min(min, child.y + CARD_H / 2);
                        max = Math.max(max, child.y + CARD_H / 2);
                    }
                    if (node == selection) {
                        for (Exit exit : exits) {
                            min = Math.min(min, exit.y + 36);
                            max = Math.max(max, exit.y + 36);
                        }
                    }
                    node.x = node.level * COLUMN;
                    node.y = min == Float.POSITIVE_INFINITY ? lane++ * ROW : (min + max - CARD_H) / 2;
                }
            }
            lane += .35f;
        }
        bounds.setEmpty();
        for (Node node : nodes) bounds.union(node.x, node.y, node.x + CARD_W, node.y + CARD_H);
        for (Exit exit : exits) bounds.union(exit.x, exit.y, exit.x + 186, exit.y + 72);
    }

    public String selectedNodeId() { return selectedId; }

    public void zoomBy(float factor) {
        cancelPanAnimation();
        if (!Float.isFinite(factor) || factor <= 0) return;
        float x = getWidth() / 2f, y = contentCenterY();
        float worldX = (x - panX) / scale(), worldY = (y - panY) / scale();
        zoom = clampZoom(zoom * factor);
        panX = x - worldX * scale();
        panY = y - worldY * scale();
        invalidate();
    }

    public void focusCurrent() {
        Node node = byId.get(currentId);
        if (node == null) node = byId.get(selectedId);
        if (node == null) return;
        if (!node.id.equals(selectedId)) select(node, false, false);
        zoom = 1f;
        centerOn(node);
    }

    public void fitAll() {
        cancelPanAnimation();
        if (nodes.isEmpty()) return;
        if (getWidth() == 0 || getHeight() == 0) { fitOnSize = true; return; }
        float width = Math.max(1, getWidth() - 48 * density);
        float height = Math.max(1, getHeight() - 148 * density);
        zoom = clampZoom(Math.min(width / (bounds.width() * density), height / (bounds.height() * density)));
        panX = getWidth() / 2f - bounds.centerX() * scale();
        panY = contentCenterY() - bounds.centerY() * scale();
        positioned = true;
        invalidate();
    }

    private float scale() { return density * zoom; }
    private float clampZoom(float value) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value)); }
    private float contentCenterY() { return (getHeight() - 24 * density) / 2f; }

    private void cancelPanAnimation() {
        if (panAnimator != null) {
            panAnimator.cancel();
            panAnimator = null;
        }
    }

    private void centerOn(Node node) {
        cancelPanAnimation();
        if (getWidth() == 0 || getHeight() == 0) return;
        float targetX = getWidth() / 2f - (node.x + CARD_W / 2) * scale();
        float targetY = contentCenterY() - (node.y + CARD_H / 2) * scale();
        if (positioned) {
            float startX = panX, startY = panY;
            panAnimator = ValueAnimator.ofFloat(0f, 1f);
            panAnimator.setDuration(180);
            panAnimator.setInterpolator(new DecelerateInterpolator());
            panAnimator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                panX = startX + (targetX - startX) * fraction;
                panY = startY + (targetY - startY) * fraction;
                invalidate();
            });
            panAnimator.start();
        } else {
            panX = targetX;
            panY = targetY;
        }
        positioned = true;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cancelPanAnimation();
        super.onSizeChanged(w, h, oldw, oldh);
        if (fitOnSize) { fitOnSize = false; fitAll(); }
        else if (!positioned) focusCurrent();
        else { panX += (w - oldw) / 2f; panY += (h - oldh) / 2f; }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BG);
        float spacing = 24 * scale();
        while (spacing < 16 * density) spacing *= 2;
        fill(0xff243047);
        for (float x = ((panX % spacing) + spacing) % spacing; x < getWidth(); x += spacing) {
            for (float y = ((panY % spacing) + spacing) % spacing; y < getHeight(); y += spacing) {
                canvas.drawCircle(x, y, .85f * density, paint);
            }
        }
        updateViewport();
        canvas.save();
        canvas.clipRect(0, 44 * density, getWidth(), Math.max(44 * density, getHeight() - 70 * density));
        canvas.translate(panX, panY);
        canvas.scale(scale(), scale());
        for (Node node : nodes) {
            if (node.parent != null) drawEdge(canvas, node.parent, node.x, node.y + CARD_H / 2,
                    selectedPath.contains(node.id), false);
        }
        for (Exit exit : exits) drawEdge(canvas, exit.parent, exit.x, exit.y + 36, false, true);
        for (Node node : nodes) {
            if (visible(node.x, node.y, CARD_W, CARD_H)) drawNode(canvas, node);
        }
        for (Exit exit : exits) {
            if (visible(exit.x, exit.y, 186, 72)) drawExit(canvas, exit);
        }
        canvas.restore();
        drawHud(canvas);
        scheduleImages();
    }

    private void updateViewport() {
        viewport.set(-panX / scale(), (44 * density - panY) / scale(),
                (getWidth() - panX) / scale(), (getHeight() - 70 * density - panY) / scale());
    }

    private boolean visible(float x, float y, float w, float h) {
        return x + w + 12 >= viewport.left && x - 12 <= viewport.right
                && y + h + 12 >= viewport.top && y - 12 <= viewport.bottom;
    }

    private void drawEdge(Canvas canvas, Node parent, float x, float y, boolean active, boolean dashed) {
        float startX = parent.x + CARD_W, startY = parent.y + CARD_H / 2;
        if (!visible(Math.min(startX, x), Math.min(startY, y), Math.abs(x - startX), Math.abs(y - startY))) return;
        path.reset();
        path.moveTo(startX, startY);
        float midpoint = (startX + x) / 2;
        path.cubicTo(midpoint, startY, midpoint, y, x, y);
        stroke(active ? 0x208cf4cb : Color.TRANSPARENT, 8);
        if (active) canvas.drawPath(path, paint);
        stroke(active ? MINT : dashed ? 0xff526c7a : 0xff34435f, active ? 2.2f : 1.5f);
        if (dashed) paint.setPathEffect(dash);
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
        fill(active ? MINT : 0xff53617d);
        canvas.drawCircle(startX, startY, 3, paint);
    }

    private void drawNode(Canvas canvas, Node node) {
        boolean selected = node.id.equals(selectedId);
        rect.set(node.x, node.y, node.x + CARD_W, node.y + CARD_H);
        if (selected) {
            rect.inset(-6, -6);
            fill(0x168cf4cb);
            canvas.drawRoundRect(rect, 21, 21, paint);
            rect.inset(6, 6);
        }
        fill(SURFACE);
        canvas.drawRoundRect(rect, 15, 15, paint);
        path.reset();
        path.addRoundRect(rect, 15, 15, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        Bitmap bitmap = node.imageKey.isEmpty() ? null : images.get(node.imageKey);
        if (bitmap != null) {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            float ratio = CARD_W / 108f;
            int cropW = width, cropH = height;
            if ((float) width / height > ratio) cropW = Math.max(1, (int) (height * ratio));
            else cropH = Math.max(1, (int) (width / ratio));
            sourceRect.set((width - cropW) / 2, (height - cropH) / 2,
                    (width + cropW) / 2, (height + cropH) / 2);
            rect.set(node.x, node.y, node.x + CARD_W, node.y + 108);
            fill(Color.WHITE);
            canvas.drawBitmap(bitmap, sourceRect, rect, paint);
            fill(0x14050917);
            canvas.drawRect(rect, paint);
        } else {
            fill(0xff111a2d);
            canvas.drawRect(node.x, node.y, node.x + CARD_W, node.y + 108, paint);
            stroke(0xff42516d, 1.3f);
            rect.set(node.x + 99, node.y + 23, node.x + 121, node.y + 45);
            canvas.drawRoundRect(rect, 6, 6, paint);
            canvas.drawLine(node.x + 110, node.y + 28, node.x + 110, node.y + 36, paint);
            fill(MUTED);
            canvas.drawCircle(node.x + 110, node.y + 40, 1, paint);
            text(canvas, imageLabel(node), node.x + 110, node.y + 68, 11, MUTED, false, Paint.Align.CENTER, 192);
        }
        fill(0xe6101829);
        rect.set(node.x + 10, node.y + 10, node.x + 66, node.y + 30);
        canvas.drawRoundRect(rect, 10, 10, paint);
        text(canvas, node.id.equals(currentId) ? "当前" : "历史", node.x + 38, node.y + 24,
                10, node.id.equals(currentId) ? MINT : TEXT, true, Paint.Align.CENTER, 48);
        text(canvas, node.title, node.x + 13, node.y + 132, 14, TEXT, true, Paint.Align.LEFT, 193);
        text(canvas, String.format(java.util.Locale.ROOT, "深度 %02d", node.depth), node.x + 13,
                node.y + 152, 10, MUTED, false, Paint.Align.LEFT, 110);
        text(canvas, selected ? "已选中  ↗" : "场景节点", node.x + CARD_W - 13, node.y + 152,
                10, selected ? MINT : MUTED, false, Paint.Align.RIGHT, 80);
        canvas.restore();
        rect.set(node.x, node.y, node.x + CARD_W, node.y + CARD_H);
        stroke(selected ? MINT : 0xff303e59, selected ? 2 : 1);
        canvas.drawRoundRect(rect, 15, 15, paint);
    }

    private String imageLabel(Node node) {
        if (failedImages.contains(node.imageKey) || node.imageKey.startsWith("invalid:")) return "图片加载失败";
        if (!node.imageKey.isEmpty()) return pendingImages.contains(node.imageKey) ? "图片加载中…" : "缩略图待加载";
        if ("failed".equals(node.imageStatus) || "error".equals(node.imageStatus)) return "图片生成失败";
        if ("loading".equals(node.imageStatus) || "generating".equals(node.imageStatus)
                || "processing".equals(node.imageStatus)) return "图片生成中…";
        if ("ready".equals(node.imageStatus)) return "图片文件缺失";
        return "图片未生成";
    }

    private void drawExit(Canvas canvas, Exit exit) {
        rect.set(exit.x, exit.y, exit.x + 186, exit.y + 72);
        fill(0xf0101d2b);
        canvas.drawRoundRect(rect, 13, 13, paint);
        stroke(0xff678d89, 1.2f);
        paint.setPathEffect(dash);
        canvas.drawRoundRect(rect, 13, 13, paint);
        paint.setPathEffect(null);
        text(canvas, "+", exit.x + 23, exit.y + 44, 26, MINT, false, Paint.Align.CENTER, 28);
        text(canvas, exit.title, exit.x + 44, exit.y + 30, 12, TEXT, true, Paint.Align.LEFT, 129);
        text(canvas, "未探索 · 点击确认", exit.x + 44, exit.y + 50, 10, MUTED, false, Paint.Align.LEFT, 129);
    }

    private void drawHud(Canvas canvas) {
        canvas.save();
        canvas.scale(density, density);
        float width = getWidth() / density, height = getHeight() / density;
        fill(0xf5090e1e);
        canvas.drawRect(0, 0, width, 44, paint);
        canvas.drawRect(0, height - 70, width, height, paint);
        text(canvas, "命运分支", 18, 27, 12, TEXT, true, Paint.Align.LEFT, Math.max(1, width - 140));
        text(canvas, nodes.size() + " 个场景  /  " + Math.round(zoom * 100) + "%", width - 18,
                27, 10, MUTED, false, Paint.Align.RIGHT, 132);
        Node selected = byId.get(selectedId);
        if (selected == null) {
            text(canvas, "故事尚未开始", width / 2, height / 2, 18, TEXT, true, Paint.Align.CENTER, width - 36);
            text(canvas, "生成开场后，场景将在这里生长", width / 2, height / 2 + 29,
                    11, MUTED, false, Paint.Align.CENTER, width - 36);
        }
        text(canvas, selected == null ? "仅展示已探索的真实场景" : "已选 · " + selected.title,
                18, height - 45, 12, MINT, true, Paint.Align.LEFT, width - 36);
        text(canvas, "轻点选中 · 再点打开 · 拖动 / 双指缩放", 18, height - 23,
                10, MUTED, false, Paint.Align.LEFT, width - 36);
        canvas.restore();
    }

    private void fill(int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setPathEffect(null);
    }

    private void stroke(int color, float width) {
        fill(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color,
                      boolean bold, Paint.Align align, float maxWidth) {
        fill(color);
        paint.setTextSize(size);
        paint.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT);
        paint.setTextAlign(align);
        String line = value.replace('\n', ' ').replace('\r', ' ');
        if (paint.measureText(line) > maxWidth) {
            int length = paint.breakText(line, true, Math.max(0, maxWidth - paint.measureText("…")), null);
            if (length > 0 && Character.isHighSurrogate(line.charAt(length - 1))) length--;
            line = line.substring(0, length) + "…";
        }
        canvas.drawText(line, x, y, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelPanAnimation();
                requestFocus();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                pointerId = event.getPointerId(0);
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                moved = false;
                multiTouch = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (index < 0) return true;
                float x = event.getX(index), y = event.getY(index);
                if (Math.hypot(x - downX, y - downY) > touchSlop) moved = true;
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1 && moved) {
                    panX += x - lastX;
                    panY += y - lastY;
                    invalidate();
                }
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                pointerId = event.getPointerId(remaining);
                lastX = downX = event.getX(remaining);
                lastY = downY = event.getY(remaining);
                moved = true;
                return true;
            case MotionEvent.ACTION_UP:
                boolean tap = !moved && !multiTouch
                        && Math.hypot(event.getX() - downX, event.getY() - downY) <= touchSlop;
                pointerId = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (tap) tapAt(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                pointerId = -1;
                moved = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    private void tapAt(float screenX, float screenY) {
        if (screenY < 44 * density || screenY > getHeight() - 70 * density) return;
        float x = (screenX - panX) / scale(), y = (screenY - panY) / scale();
        for (Exit exit : exits) {
            if (x >= exit.x && x <= exit.x + 186 && y >= exit.y && y <= exit.y + 72) {
                super.performClick();
                if (listener != null) listener.onExplore(exit.parent.id, exit.option);
                return;
            }
        }
        for (int i = nodes.size() - 1; i >= 0; i--) {
            Node node = nodes.get(i);
            if (x >= node.x && x <= node.x + CARD_W && y >= node.y && y <= node.y + CARD_H) {
                if (node.id.equals(selectedId)) performClick();
                else { super.performClick(); select(node, true, true); }
                return;
            }
        }
    }

    private void select(Node node, boolean announce, boolean preservePosition) {
        cancelPanAnimation();
        float oldX = node.x, oldY = node.y;
        selectedId = node.id;
        layoutTree();
        if (preservePosition) {
            panX += (oldX - node.x) * scale();
            panY += (oldY - node.y) * scale();
        }
        updateDescription();
        if (announce) announceForAccessibility("已选中 " + node.title + "，再次点击打开");
        invalidate();
    }

    public boolean moveSelection(int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
                && keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        Node current = byId.get(selectedId);
        if (current == null) return false;
        Node nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Node node : nodes) {
            if (node == current) continue;
            float dx = node.x - current.x, dy = node.y - current.y;
            float forward = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -dx
                    : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? dx
                    : keyCode == KeyEvent.KEYCODE_DPAD_UP ? -dy : dy;
            if (forward <= .5f) continue;
            double score = dx * (double) dx + dy * (double) dy;
            if (score < best) { best = score; nearest = node; }
        }
        if (nearest == null) return false;
        select(nearest, true, false);
        centerOn(nearest);
        return true;
    }

    public void activateSelection() {
        if (byId.containsKey(selectedId) && listener != null) listener.onNodeSelected(selectedId);
    }

    @Override public boolean performClick() {
        super.performClick();
        activateSelection();
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (moveSelection(keyCode)) return true;
        if (keyCode == KeyEvent.KEYCODE_A || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getRepeatCount() == 0) performClick();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateDescription() {
        Node selected = byId.get(selectedId);
        setContentDescription("分支画布，" + nodes.size() + "个场景。"
                + (selected == null ? "暂无选中场景。" : "已选中" + selected.title + "。")
                + "轻点选中，再点打开；拖动画布，双指缩放。虚线加号探索新分支。方向键选场景，A键打开。");
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(BranchCanvasView.class.getName());
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "打开选中场景"));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.setScrollable(nodes.size() > 1);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            Node selected = byId.get(selectedId);
            int index = nodes.indexOf(selected) + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
            if (index >= 0 && index < nodes.size()) {
                select(nodes.get(index), true, false);
                centerOn(nodes.get(index));
                return true;
            }
            return false;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private void startDecoder() {
        decoder = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "branch-thumbnail");
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    private void scheduleImages() {
        if (detached || imagesScheduled) return;
        imagesScheduled = true;
        handler.post(() -> {
            imagesScheduled = false;
            if (detached || decoder == null || decoder.isShutdown()) return;
            updateViewport();
            List<Node> visibleNodes = new ArrayList<>();
            for (Node node : nodes) {
                if (!node.imageKey.isEmpty() && visible(node.x, node.y, CARD_W, CARD_H)) visibleNodes.add(node);
            }
            visibleNodes.sort(Comparator.comparingDouble(node -> node.id.equals(selectedId) ? -1
                    : Math.hypot(node.x + CARD_W / 2 - viewport.centerX(), node.y + CARD_H / 2 - viewport.centerY())));
            // A stable visible working set avoids cache churn when zoomed out over a large tree.
            Set<String> wanted = new HashSet<>();
            for (Node node : visibleNodes) {
                if (wanted.size() >= 24) break;
                String key = node.imageKey;
                if (!wanted.add(key) || images.get(key) != null || failedImages.contains(key)
                        || pendingImages.contains(key)) continue;
                if (pendingImages.size() >= 32) break;
                pendingImages.add(key);
                final int generation = imageGeneration;
                decoder.execute(() -> {
                    Bitmap bitmap = null;
                    try { bitmap = decodeImage(key); }
                    catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
                    final Bitmap decoded = bitmap;
                    if (detached || Thread.currentThread().isInterrupted()) return;
                    handler.post(() -> {
                        if (detached || generation != imageGeneration) return;
                        pendingImages.remove(key);
                        if (decoded == null) failedImages.add(key);
                        else images.put(key, decoded);
                        invalidate();
                    });
                });
            }
        });
    }

    private InputStream openImage(String key) throws IOException {
        if (key.startsWith("asset:")) return getContext().getAssets().open(key.substring(6));
        if (!key.startsWith("file:")) throw new IOException("Invalid image reference");
        File root = getContext().getFilesDir().getCanonicalFile();
        File file = new File(root, key.substring(5)).getCanonicalFile();
        if (!root.equals(file.getParentFile())) throw new IOException("Invalid image location");
        return new FileInputStream(file);
    }

    private Bitmap decodeImage(String key) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = openImage(key)) { BitmapFactory.decodeStream(input, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 440 || options.outHeight / options.inSampleSize > 256) {
            options.inSampleSize *= 2;
        }
        if (Thread.currentThread().isInterrupted()) return null;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream input = openImage(key)) { return BitmapFactory.decodeStream(input, null, options); }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        detached = false;
        if (decoder == null || decoder.isShutdown()) startDecoder();
        scheduleImages();
    }

    @Override protected void onDetachedFromWindow() {
        cancelPanAnimation();
        detached = true;
        imageGeneration++;
        if (decoder != null) decoder.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        imagesScheduled = false;
        pendingImages.clear();
        failedImages.clear();
        // Never recycle: the hardware renderer may still retain a submitted bitmap.
        images.evictAll();
        super.onDetachedFromWindow();
    }
}
