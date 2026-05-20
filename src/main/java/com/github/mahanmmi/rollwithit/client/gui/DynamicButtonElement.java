package com.github.mahanmmi.rollwithit.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import iskallia.vault.client.atlas.TextureAtlasRegion;
import iskallia.vault.client.gui.framework.element.ButtonElement;
import iskallia.vault.client.gui.framework.render.spi.IElementRenderer;
import iskallia.vault.client.gui.framework.spatial.spi.IPosition;

import java.util.function.Supplier;

/**
 * VH {@link ButtonElement} variant whose texture set is read from a {@link Supplier} on every
 * frame, so the same button slot can swap its art at runtime (Super Refresh ↔ Stop Refresh).
 * <p>
 * Why a subclass instead of two stacked buttons: the upstream {@code textures} field is
 * {@code protected final}, so we can't reassign it; but {@link #render} is overridable and only
 * needs {@link ButtonElement.ButtonTextures#selectTexture(boolean, boolean, boolean)} plus the
 * inherited {@code worldSpatial} field — both reachable through normal Java inheritance.
 * <p>
 * The supplier is called once per render tick. Keep it cheap (it just reads a flag like
 * {@code SuperRefreshController.isRunning()} and picks one of two static constants).
 */
public class DynamicButtonElement extends ButtonElement<DynamicButtonElement> {

    private final Supplier<ButtonTextures> texturesSupplier;

    public DynamicButtonElement(IPosition position, Supplier<ButtonTextures> texturesSupplier, Runnable onClick) {
        // The constant we pass to super() is never read again — we override render() — but it must
        // be non-null so the parent constructor's initializer is happy.
        super(position, texturesSupplier.get(), onClick);
        this.texturesSupplier = texturesSupplier;
    }

    @Override
    public void render(IElementRenderer renderer, PoseStack ps, int mouseX, int mouseY, float partialTicks) {
        ButtonTextures dyn = texturesSupplier.get();
        TextureAtlasRegion region = dyn.selectTexture(
                isDisabled(),
                containsMouse(mouseX, mouseY),
                this.clickHeld);
        renderer.render(region, ps, this.worldSpatial);
    }
}
