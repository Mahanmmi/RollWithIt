package com.github.mahanmmi.rollwithit.client.gui;

import iskallia.vault.VaultMod;
import iskallia.vault.client.atlas.TextureAtlasRegion;
import iskallia.vault.client.gui.framework.element.ButtonElement;
import iskallia.vault.init.ModTextureAtlases;

/**
 * RollWithIt button-texture handles, exposed as VH {@link ButtonElement.ButtonTextures} bundles.
 * <p>
 * <h3>Asset placement</h3>
 * The PNGs intentionally live under the VH ({@code the_vault}) namespace at
 * {@code assets/the_vault/textures/gui/screen/button/rwi_*.png}. Doing so piggybacks on VH's
 * existing {@code SCREEN} sprite atlas: Forge merges resource packs by namespace, so VH's atlas
 * stitcher picks our PNGs up automatically and no explicit registration is needed.
 * This is the same technique used by
 * <a href="https://github.com/radimous/VHatCanIRoll">VHatCanIRoll</a>.
 * <p>
 * <h3>Files we ship (two distinct asset sets for hot-swapping)</h3>
 * <pre>
 *   rwi_super_refresh{,_hover,_pressed,_disabled}.png   — idle / "start a Super Refresh" face
 *   rwi_stop_refresh{,_hover,_pressed,_disabled}.png    — running / "cancel the loop"  face
 * </pre>
 * Both files are currently identical copies of VH's vanilla reroll icon (so we can verify the
 * pipeline works); they are exposed as two separate {@link ButtonElement.ButtonTextures} bundles
 * so we can hot-swap the actual artwork later without touching any code.
 */
public final class RWITextures {

    private RWITextures() {}

    // ── Super Refresh (idle) ───────────────────────────────────────────────────────────────────
    public static final TextureAtlasRegion BUTTON_SUPER_REFRESH =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_super_refresh"));
    public static final TextureAtlasRegion BUTTON_SUPER_REFRESH_HOVER =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_super_refresh_hover"));
    public static final TextureAtlasRegion BUTTON_SUPER_REFRESH_PRESSED =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_super_refresh_pressed"));
    public static final TextureAtlasRegion BUTTON_SUPER_REFRESH_DISABLED =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_super_refresh_disabled"));

    public static final ButtonElement.ButtonTextures SUPER_REFRESH_TEXTURES =
            new ButtonElement.ButtonTextures(
                    BUTTON_SUPER_REFRESH,
                    BUTTON_SUPER_REFRESH_HOVER,
                    BUTTON_SUPER_REFRESH_PRESSED,
                    BUTTON_SUPER_REFRESH_DISABLED);

    // ── Stop Refresh (running) ─────────────────────────────────────────────────────────────────
    public static final TextureAtlasRegion BUTTON_STOP_REFRESH =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_stop_refresh"));
    public static final TextureAtlasRegion BUTTON_STOP_REFRESH_HOVER =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_stop_refresh_hover"));
    public static final TextureAtlasRegion BUTTON_STOP_REFRESH_PRESSED =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_stop_refresh_pressed"));
    public static final TextureAtlasRegion BUTTON_STOP_REFRESH_DISABLED =
            TextureAtlasRegion.of(ModTextureAtlases.SCREEN,
                    VaultMod.id("gui/screen/button/rwi_stop_refresh_disabled"));

    public static final ButtonElement.ButtonTextures STOP_REFRESH_TEXTURES =
            new ButtonElement.ButtonTextures(
                    BUTTON_STOP_REFRESH,
                    BUTTON_STOP_REFRESH_HOVER,
                    BUTTON_STOP_REFRESH_PRESSED,
                    BUTTON_STOP_REFRESH_DISABLED);
}
