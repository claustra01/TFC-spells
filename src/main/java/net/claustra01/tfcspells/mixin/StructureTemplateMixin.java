package net.claustra01.tfcspells.mixin;

import java.util.List;
import java.util.Set;
import net.claustra01.tfcspells.access.StructureTemplateIdAccess;
import net.claustra01.tfcspells.access.StructureTemplatePalettesAccess;
import net.claustra01.tfcspells.world.processor.TfcBlockReplacementProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin implements StructureTemplateIdAccess, StructureTemplatePalettesAccess {
    @Unique
    private static final Set<String> TFC_SPELLS_STRUCTURE_NAMESPACES = Set.of("irons_spellbooks");

    @Shadow @Final private List<StructureTemplate.Palette> palettes;

    @Unique private ResourceLocation tfcspells$templateId;

    @Override
    public ResourceLocation tfcspells$getTemplateId() {
        return tfcspells$templateId;
    }

    @Override
    public void tfcspells$setTemplateId(ResourceLocation id) {
        this.tfcspells$templateId = id;
    }

    @Override
    public List<StructureTemplate.Palette> tfcspells$getPalettes() {
        return palettes;
    }

    // NeoForge runtime uses official names; we don't generate a refmap, so disable remapping.
    @Inject(method = "placeInWorld", at = @At("HEAD"), remap = false)
    private void tfcspells$addProcessor(
            ServerLevelAccessor serverLevel,
            BlockPos offset,
            BlockPos pos,
            StructurePlaceSettings settings,
            RandomSource random,
            int flags,
            CallbackInfoReturnable<Boolean> cir) {
        ResourceLocation id = this.tfcspells$templateId;
        if (id == null || !TFC_SPELLS_STRUCTURE_NAMESPACES.contains(id.getNamespace())) {
            return;
        }

        // Ensure we run after the structure's own processors (we append to the end).
        if (!settings.getProcessors().contains(TfcBlockReplacementProcessor.INSTANCE)) {
            settings.addProcessor(TfcBlockReplacementProcessor.INSTANCE);
        }
    }
}
