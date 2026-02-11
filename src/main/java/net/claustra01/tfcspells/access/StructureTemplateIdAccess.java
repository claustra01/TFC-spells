package net.claustra01.tfcspells.access;

import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * Mixin access interface used to associate a {@link ResourceLocation} id with a {@link net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate}.
 *
 * <p>This avoids relying on processor list JSON overrides. See {@code StructureTemplateManagerMixin} and
 * {@code StructureTemplateMixin}.</p>
 */
public interface StructureTemplateIdAccess {
    @Nullable
    ResourceLocation tfcspells$getTemplateId();

    void tfcspells$setTemplateId(ResourceLocation id);
}
