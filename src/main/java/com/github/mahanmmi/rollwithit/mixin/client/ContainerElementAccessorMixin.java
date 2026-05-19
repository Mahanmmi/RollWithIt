package com.github.mahanmmi.rollwithit.mixin.client;

import iskallia.vault.client.gui.framework.element.ContainerElement;
import iskallia.vault.client.gui.framework.element.spi.IElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected {@code addElement(...)} of VH's {@link ContainerElement} so we can attach
 * our own buttons from inside {@link BountyTableContainerElementMixin}.
 */
@Mixin(ContainerElement.class)
public interface ContainerElementAccessorMixin {

    @Invoker("addElement")
    <T extends IElement> T rwi$addElement(T element);
}
