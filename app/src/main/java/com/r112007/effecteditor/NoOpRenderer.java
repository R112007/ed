package com.r112007.effecteditor;

import mindustry.core.Renderer;

/**
 * Minimal Renderer stub that disables screen shake in the effect preview.
 * Mindustry's Effect.shake() accesses Vars.renderer, which is normally null
 * outside of a full game client; this stub prevents the resulting NPE.
 */
public class NoOpRenderer extends Renderer {

    @Override
    public void shake(float intensity, float duration) {
        // No screen shake in the editor preview.
    }
}
