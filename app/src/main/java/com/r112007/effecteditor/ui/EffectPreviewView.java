package com.r112007.effecteditor.ui;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.Gravity;

import arc.ApplicationListener;
import arc.backend.android.AndroidApplication;

import com.r112007.effecteditor.NoOpLightRenderer;
import com.r112007.effecteditor.NoOpRenderer;
import arc.backend.android.AndroidApplicationConfiguration;
import arc.graphics.Camera;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.SpriteBatch;
import arc.graphics.g2d.TextureAtlas;
import mindustry.core.FileTree;
import mindustry.core.GameState;
import mindustry.entities.Effect;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Preview pane that embeds an Arc GLSurfaceView and renders a compiled
 * {@link Effect} in a loop.
 * The effect is replayed continuously so the user sees the animation.
 * <p>
 * Built-in sprites are loaded from APK assets ({@code sprites/effects/}) and
 * custom sprites
 * can be placed in the app's external files directory
 * ({@code Android/data/.../files/sprites/}).
 * The preview also supports pinch-to-zoom and drag-to-pan.
 */
public class EffectPreviewView extends FrameLayout {

    private static final String TAG = "EffectPreviewView";
    private static final String ASSET_SPRITES_DIR = "sprites/effects";
    private static final String CUSTOM_SPRITES_DIR = "sprites";
    private TextView hudView;
    private volatile boolean showHud = true;

    private View arcView;
    private AndroidApplication hostActivity;
    private AndroidApplicationConfiguration hostConfig;
    private volatile Effect currentEffect;
    private volatile boolean paused = true;
    private volatile boolean restartPreview;
    /**
     * The atlas is owned by the GL thread but its reference lives here so the
     * UI thread can request a reload when custom sprites are added while the app is
     * in the background.
     */
    private TextureAtlas previewAtlas;

    // Zoom / pan state (touched on UI thread, read on GL thread).
    private volatile float previewScale = 3.0f;
    private volatile float panX = 0f;
    private volatile float panY = 0f;
    private ScaleGestureDetector scaleDetector;
    private float lastTouchX, lastTouchY;
    private boolean isPanning;
    private static final float MIN_SCALE = 0.3f;
    private static final float MAX_SCALE = 6f;

    private final ApplicationListener previewListener = new ApplicationListener() {
        private float fpsAcc = 0;
        private float lastFps = 0;
        private float time;
        private static final float loopInterval = 1.5f; // seconds between replays
        private SpriteBatch batch;
        private Camera camera;
        private int viewWidth, viewHeight;

        @Override
        public void init() {
            arc.Core.graphics.setContinuousRendering(true);

            // Arc's Android backend does not create the global rendering state that
            // Mindustry's Effect rendering expects. Initialize the fields that are
            // known to be accessed by Draw / Lines / Fill / Effect.render.
            if (batch == null) {
                batch = new SpriteBatch();
            }
            arc.Core.batch = batch;

            if (previewAtlas == null) {
                previewAtlas = createPreviewAtlas();
            }
            arc.Core.atlas = previewAtlas;

            if (camera == null) {
                camera = new Camera();
            }
            arc.Core.camera = camera;

            // Mindustry's shader loading and file lookups use Vars.tree. The preview
            // does not run Vars.init(), so initialize the tree before constructing the
            // Renderer stub (Renderer() -> Shaders.init() -> tree.get()).
            if (mindustry.Vars.tree == null) {
                mindustry.Vars.tree = new FileTree();
            }

            // LightRenderer.enabled() reads Vars.state.rules.lighting. Make sure state
            // exists so that light rendering can early-out safely in the editor preview.
            if (mindustry.Vars.state == null) {
                mindustry.Vars.state = new GameState();
            }

            // Effect.shake() calls Vars.renderer.shake(). In the editor we are not
            // running a full Mindustry client, so Vars.renderer is null. Install a
            // no-op renderer to prevent NPEs when user effects use shake() or light().
            ensureRendererStub();
        }

        private void ensureRendererStub() {
            if (mindustry.Vars.renderer != null && mindustry.Vars.renderer.lights != null) {
                return;
            }

            try {
                // First attempt: construct normally. Field initializers in Renderer will
                // create a LightRenderer, but Renderer() may fail inside Shaders.init()
                // because the editor does not bundle all client shaders.
                mindustry.core.Renderer renderer = new NoOpRenderer();
                installNoOpLights(renderer);
                mindustry.Vars.renderer = renderer;
                Log.i(TAG, "Installed NoOpRenderer directly");
            } catch (Throwable t) {
                Log.w(TAG, "Could not install NoOpRenderer directly, trying reflection", t);
                installRendererViaReflection();
            }

            if (mindustry.Vars.renderer == null || mindustry.Vars.renderer.lights == null) {
                Log.e(TAG, "Renderer stub installation failed; Drawf.light may crash");
            }
        }

        private void installRendererViaReflection() {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                Object unsafe = theUnsafe.get(null);

                java.lang.reflect.Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
                // Allocate a NoOpRenderer without running its constructor. This avoids
                // Shaders.init() failures, while still giving us a real Renderer subtype.
                Object stub = allocate.invoke(unsafe, NoOpRenderer.class);
                mindustry.core.Renderer renderer = (mindustry.core.Renderer) stub;

                installNoOpLights(renderer);

                mindustry.Vars.renderer = renderer;
                Log.i(TAG, "Installed Renderer stub via Unsafe");
            } catch (Throwable t2) {
                Log.e(TAG, "Could not install Renderer stub", t2);
            }
        }

        /**
         * Replaces the final {@code lights} field of a Renderer with a non-null
         * implementation. This is the field that Drawf.light() dereferences.
         */
        private void installNoOpLights(mindustry.core.Renderer renderer) {
            Throwable last = null;

            // Prefer the no-op subclass so no FrameBuffer/shader/atlas code runs.
            // If it cannot be constructed, fall back to a plain LightRenderer.
            mindustry.graphics.LightRenderer lights;
            try {
                lights = new NoOpLightRenderer();
            } catch (Throwable t) {
                Log.w(TAG, "NoOpLightRenderer failed, using plain LightRenderer", t);
                try {
                    lights = new mindustry.graphics.LightRenderer();
                } catch (Throwable t2) {
                    Log.e(TAG, "Could not create any LightRenderer", t2);
                    return;
                }
            }

            // Method 1: reflection on Field.modifiers to clear FINAL, then set().
            try {
                Field lightsField = mindustry.core.Renderer.class.getDeclaredField("lights");
                lightsField.setAccessible(true);

                Field modifiersField = Field.class.getDeclaredField("modifiers");
                modifiersField.setAccessible(true);
                modifiersField.setInt(lightsField, lightsField.getModifiers() & ~Modifier.FINAL);

                lightsField.set(renderer, lights);
                return;
            } catch (Throwable t) {
                last = t;
            }

            // Method 2: Unsafe.putObject on the field offset.
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                Object unsafe = theUnsafe.get(null);

                Field lightsField = mindustry.core.Renderer.class.getDeclaredField("lights");
                long lightsOffset = (Long) unsafeClass
                        .getMethod("objectFieldOffset", Field.class)
                        .invoke(unsafe, lightsField);
                unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                        .invoke(unsafe, renderer, lightsOffset, lights);
                return;
            } catch (Throwable t) {
                last = t;
            }

            Log.e(TAG, "Could not install lights field", last);
        }

        @Override
        public void update() {
            if (restartPreview) {
                restartPreview = false;
                time = 0f;
                // Throw away any geometry that belonged to the previous effect so it
                // cannot be drawn on top of the newly compiled one.
                discardDrawState();
            }

            if (viewWidth <= 0 || viewHeight <= 0) {
                return;
            }

            // Defensive: the GL thread may call update() before init() completes,
            // or something may have cleared Vars.renderer. Re-install the stub if
            // needed so that Drawf.light() never dereferences a null lights field.
            ensureRendererStub();

            // Snapshot the current effect on the GL thread so a UI-thread reset cannot
            // clear it between the null check and the field access.
            Effect effect = currentEffect;
            if (paused || effect == null) {
                discardDrawState();
                arc.Core.graphics.clear(Color.black);
                return;
            }

            float delta = arc.Core.graphics.getDeltaTime();
            time += delta;

            float duration = effect.lifetime / 60f;
            if (time >= duration + loopInterval) {
                time = 0f;
            }

            // Reset and discard stale batched geometry *before* clearing the screen.
            // If we clear first and flush afterwards, leftover sprites from the
            // previous effect/replay would be drawn on the newly cleared surface.
            discardDrawState();
            arc.Core.graphics.clear(Color.black);

            float cx = viewWidth / 2f;
            float cy = viewHeight / 2f;

            applyViewTransform(cx, cy);

            // Default blend mode; must be set after reset() because reset() only
            // touches color/mixcol/scale/stroke, not blending.
            Draw.blend();

            if (time <= duration) {
                // Directly render the effect with a synthetic EffectContainer.
                effect.render(
                        1,
                        Color.white,
                        time * 60f,
                        effect.lifetime,
                        0f,
                        cx,
                        cy,
                        null);
            }

            // Flush any pending draw commands and reset global state so the next
            // effect/replay starts from a known default.
            Draw.flush();
            safeDrawReset();

            fpsAcc += delta;
            if (fpsAcc >= 0.5f) {
                lastFps = 1f / Math.max(delta, 0.0001f);
                fpsAcc = 0;
            }
            float displayTime = Math.min(time * 60f, effect.lifetime);
            if (showHud && hudView != null) {
                final String txt = String.format("FPS:%.1f\nTime:%.1f/%.0f",
                        lastFps, displayTime, effect.lifetime);
                post(() -> {
                    if (hudView != null)
                        hudView.setText(txt);
                });
            }

        }

        private void safeDrawReset() {
            if (arc.Core.batch != null) {
                Draw.flush();
                Draw.reset();
            }
        }

        private void discardDrawState() {
            if (arc.Core.batch != null) {
                try {
                    Draw.discard();
                } catch (Throwable t) {
                    // Some batch implementations may not support discard; fall back
                    // to flush so geometry is at least submitted instead of leaking.
                    Draw.flush();
                }
                Draw.reset();
            }
        }

        private void applyViewTransform(float cx, float cy) {
            float scale = previewScale;
            float px = panX;
            float py = panY;

            if (scale == 1f && px == 0f && py == 0f) {
                Draw.proj().setOrtho(0, 0, viewWidth, viewHeight);
                return;
            }

            // Ortho projection centered on the effect, scaled and panned.
            float w = viewWidth / scale;
            float h = viewHeight / scale;
            float left = cx - w / 2f - px / scale;
            float bottom = cy - h / 2f - py / scale;
            Draw.proj().setOrtho(left, bottom, w, h);
        }

        @Override
        public void resize(int width, int height) {
            if (width <= 0 || height <= 0)
                return;
            viewWidth = width;
            viewHeight = height;

            // Match Mindustry's ClientLauncher.resize(): ortho projection over the
            // whole view so that screen-pixel coordinates map 1:1.
            Draw.proj().setOrtho(0, 0, width, height);

            if (camera != null) {
                camera.resize(width, height);
                camera.position.set(width / 2f, height / 2f);
            }
        }

        @Override
        public void resume() {
            paused = false;
        }

        @Override
        public void pause() {
            paused = true;
        }

        @Override
        public void dispose() {
            // Flush any pending commands before disposing the batch/atlas,
            // otherwise stale geometry from the previous effect may survive.
            safeDrawReset();

            if (previewAtlas != null) {
                previewAtlas.dispose();
                previewAtlas = null;
            }
            if (batch != null) {
                batch.dispose();
                batch = null;
            }
            // camera is a plain data object, no disposal needed.
            camera = null;

            // Drop Fill's cached circle region so the next atlas creation cannot
            // accidentally reuse a disposed texture.
            clearFillCircleCache();
        }

        @Override
        public void exit() {
        }

        @Override
        public void fileDropped(arc.files.Fi file) {
        }
    };

    /**
     * Creates a TextureAtlas that contains:
     * <ul>
     * <li>The regions Arc's Fill / Lines / Draw primitives need.</li>
     * <li>All Mindustry effect sprites bundled in
     * {@code assets/sprites/effects/}.</li>
     * <li>All custom sprites placed in
     * {@code Android/data/.../files/sprites/}.</li>
     * </ul>
     */
    private TextureAtlas createPreviewAtlas() {
        Context context = getContext();
        TextureAtlas atlas = TextureAtlas.blankAtlas();

        // Load built-in sprites from assets first. Custom sprites may override them.
        loadSpritesFromAssets(context, atlas, ASSET_SPRITES_DIR);

        // Load custom sprites from external storage, allowing them to override
        // built-ins.
        File customDir = new File(context.getExternalFilesDir(null), CUSTOM_SPRITES_DIR);
        if (customDir.exists() && customDir.isDirectory()) {
            loadSpritesFromDirectory(atlas, customDir);
        }

        // Add the regions that Arc's drawing primitives need LAST, so user-supplied
        // sprites can never accidentally replace the circle/white placeholder with a
        // square texture and cause circles to render as rectangles.
        final int circleSize = 256;
        Pixmap circle = new Pixmap(circleSize, circleSize);
        circle.fill(Color.clearRgba);
        circle.fillCircle(circleSize / 2, circleSize / 2, circleSize / 2, Color.whiteRgba);
        // Request mipmaps: without them, the circle texture can alias and look like a
        // square when it is shrunk to a small on-screen size.
        Texture circleTex = new Texture(circle, true);
        circle.dispose();
        circleTex.setFilter(Texture.TextureFilter.mipMapLinearLinear, Texture.TextureFilter.linear);
        circleTex.setWrap(Texture.TextureWrap.clampToEdge, Texture.TextureWrap.clampToEdge);
        atlas.addRegion("circle", circleTex, 0, 0, circleSize, circleSize);

        // Also add a white pixel region used by some draw routines.
        Pixmap white = new Pixmap(1, 1);
        white.fill(Color.whiteRgba);
        Texture whiteTex = new Texture(white);
        white.dispose();
        atlas.addRegion("white", whiteTex, 0, 0, 1, 1);

        // Force Fill's cached circleRegion to come from this atlas. A stale region
        // from a previous (disposed) atlas is a common cause of squares.
        primeFillCircleCache(atlas);

        return atlas;
    }

    private void primeFillCircleCache(TextureAtlas atlas) {
        try {
            java.lang.reflect.Field circleField = arc.graphics.g2d.Fill.class.getDeclaredField("circleRegion");
            circleField.setAccessible(true);
            circleField.set(null, atlas.find("circle"));
        } catch (Throwable t) {
            Log.w(TAG, "Could not prime Fill.circleRegion", t);
        }
    }

    private void clearFillCircleCache() {
        try {
            java.lang.reflect.Field circleField = arc.graphics.g2d.Fill.class.getDeclaredField("circleRegion");
            circleField.setAccessible(true);
            circleField.set(null, null);
        } catch (Throwable ignored) {
        }
    }

    private void loadSpritesFromAssets(Context context, TextureAtlas atlas, String dir) {
        try {
            String[] names = context.getAssets().list(dir);
            if (names == null)
                return;
            for (String name : names) {
                if (!name.toLowerCase().endsWith(".png"))
                    continue;
                String assetPath = dir + "/" + name;
                String regionName = name.substring(0, name.length() - 4);
                try (InputStream in = context.getAssets().open(assetPath)) {
                    loadSpriteStreamIntoAtlas(in, atlas, regionName);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to load built-in sprite: " + assetPath, e);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to list asset sprites", e);
        }
    }

    private void loadSpritesFromDirectory(TextureAtlas atlas, File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File file : files) {
            if (!file.isFile())
                continue;
            String name = file.getName();
            if (!name.toLowerCase().endsWith(".png"))
                continue;
            String regionName = name.substring(0, name.length() - 4);
            try (InputStream in = new java.io.FileInputStream(file)) {
                loadSpriteStreamIntoAtlas(in, atlas, regionName);
            } catch (IOException e) {
                Log.w(TAG, "Failed to load custom sprite: " + file, e);
            }
        }
    }

    private void loadSpriteStreamIntoAtlas(InputStream in, TextureAtlas atlas, String regionName) {
        try {
            byte[] bytes = readAllBytes(in);
            Pixmap pixmap = new Pixmap(bytes);
            Texture texture = new Texture(pixmap);
            pixmap.dispose();
            atlas.addRegion(regionName, texture, 0, 0, texture.width, texture.height);
        } catch (IOException e) {
            Log.w(TAG, "Could not read sprite: " + regionName, e);
        } catch (Throwable t) {
            Log.w(TAG, "Could not decode sprite: " + regionName, t);
        }
    }

    private byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(8192, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public EffectPreviewView(Context context) {
        super(context);
    }

    public EffectPreviewView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
    }

    public EffectPreviewView(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void initialize(AndroidApplication activity, AndroidApplicationConfiguration config) {
        this.hostActivity = activity;
        this.hostConfig = config;
        recreateSurface();
    }

    /**
     * Disposes the old Arc surface and creates a fresh one. This is the same
     * recovery
     * path used on orientation changes and is the most reliable way to bring the
     * preview back after the GL context was lost while the app was in the
     * background.
     */
    public void reinitialize() {
        if (hostActivity == null || hostConfig == null)
            return;
        Effect saved = currentEffect;
        clearEffect();
        recreateSurface();
        if (saved != null) {
            setEffect(saved);
        }
    }

    private void recreateSurface() {
        AndroidApplication activity = hostActivity;
        AndroidApplicationConfiguration config = hostConfig;
        if (activity == null || config == null)
            return;

        if (arcView != null) {
            // Clean up GL resources from the previous surface before the listener is
            // removed (and thus misses the backend's dispose callback).
            previewListener.dispose();
            removeView(arcView);
            arcView = null;
            // Remove the previously added listener to avoid duplicates on rotation.
            activity.getListeners().remove(previewListener);
        }

        // Obtain the Arc GLSurfaceView from the host AndroidApplication.
        arcView = activity.initializeForView(previewListener, config);
        addView(arcView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        paused = false;

        setupTouchHandling();
        initHud();
    }

    private void initHud() {
        if (hudView != null)
            return;
        Context ctx = getContext();
        hudView = new TextView(ctx);
        hudView.setTextColor(0xFFFFFFFF);
        hudView.setBackgroundColor(0x80000000);
        hudView.setTextSize(10);
        hudView.setTypeface(android.graphics.Typeface.MONOSPACE);
        hudView.setPadding(8, 4, 8, 4);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(8, 8, 0, 0);
        addView(hudView, lp);
    }

    public void setShowHud(boolean show) {
        showHud = show;
        post(() -> {
            if (hudView != null)
                hudView.setVisibility(show ? VISIBLE : GONE);
        });
    }

    private void setupTouchHandling() {
        Context context = getContext();
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                previewScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, previewScale * factor));
                return true;
            }
        });

        arcView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    isPanning = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isPanning && event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        panX += dx;
                        panY -= dy; // screen Y is inverted relative to world Y
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isPanning = false;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    isPanning = false;
                    break;
            }
            return true;
        });
    }

    public void resetView() {
        previewScale = 1f;
        panX = 0f;
        panY = 0f;
    }

    public void setEffect(Effect effect) {
        this.currentEffect = effect;
        this.paused = false;
        this.restartPreview = true;
        refreshPreviewInternal();
    }

    /**
     * Forces the preview to render immediately. Useful when the GL surface has
     * stopped updating or after switching back from another app.
     */
    public void refreshPreview() {
        this.paused = false;
        this.restartPreview = true;
        refreshPreviewInternal();
    }

    private void refreshPreviewInternal() {
        if (arcView instanceof android.opengl.GLSurfaceView) {
            android.opengl.GLSurfaceView gl = (android.opengl.GLSurfaceView) arcView;
            // Ensure the GL thread is running; this is a no-op if it already is.
            gl.onResume();
            gl.requestRender();
        }
    }

    public void clearEffect() {
        this.currentEffect = null;
    }

    public void onResume() {
        paused = false;
    }

    public void onPause() {
        paused = true;
    }

    public void onDestroy() {
        paused = true;
        previewListener.dispose();
    }

    /**
     * Recreates the preview atlas on the GL thread so that sprites copied into the
     * custom sprites folder while the app was in the background are picked up
     * immediately. This method blocks the caller until the GL thread has finished.
     */
    public void reloadAtlas() {
        if (!(arcView instanceof android.opengl.GLSurfaceView)) {
            return;
        }
        // The atlas is created by the preview listener's init() on the GL thread.
        // If reloadAtlas() is called before that (e.g. early onResume), the GL
        // interface is not ready yet and creating textures would crash.
        if (previewAtlas == null || arc.Core.graphics == null || arc.Core.graphics.getGL20() == null) {
            return;
        }
        final android.opengl.GLSurfaceView gl = (android.opengl.GLSurfaceView) arcView;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        gl.queueEvent(() -> {
            try {
                // Make sure no geometry still references the old atlas before disposing it.
                if (arc.Core.batch != null) {
                    Draw.flush();
                    Draw.reset();
                }
                if (previewAtlas != null) {
                    previewAtlas.dispose();
                    previewAtlas = null;
                }
                previewAtlas = createPreviewAtlas();
                arc.Core.atlas = previewAtlas;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
