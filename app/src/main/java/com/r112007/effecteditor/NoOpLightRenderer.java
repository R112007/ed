package com.r112007.effecteditor;

import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import mindustry.graphics.LightRenderer;

/**
 * A completely no-op {@link LightRenderer} used by the effect preview stub renderer.
 * <p>
 * Mindustry's {@code Drawf.light(...)} calls {@code renderer.lights.add(...)}. In the
 * editor we do not run a full client, so we install a stub renderer whose lights field
 * points to this no-op implementation. Overriding all public entry points guarantees
 * that no FrameBuffer, shader or atlas lookups are exercised during preview.
 */
public class NoOpLightRenderer extends LightRenderer {

    @Override
    public void add(Runnable run) {
        // No-op.
    }

    @Override
    public void add(float x, float y, float radius, Color color, float opacity) {
        // No-op.
    }

    @Override
    public void add(float x, float y, TextureRegion region, Color color, float opacity) {
        // No-op.
    }

    @Override
    public void add(float x, float y, TextureRegion region, float rotation, Color color, float opacity) {
        // No-op.
    }

    @Override
    public void line(float x, float y, float x2, float y2, float stroke, Color tint, float alpha) {
        // No-op.
    }

    @Override
    public boolean enabled() {
        // Always disabled so that callers return early and never reach the real draw path.
        return false;
    }

    @Override
    public void draw() {
        // No-op.
    }
}
