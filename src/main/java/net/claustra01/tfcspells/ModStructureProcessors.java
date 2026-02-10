package net.claustra01.tfcspells;

import net.claustra01.tfcspells.world.processor.TfcBlockReplacementProcessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModStructureProcessors {
    public static final DeferredRegister<StructureProcessorType<?>> STRUCTURE_PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, TfcSpells.MOD_ID);

    public static final DeferredHolder<StructureProcessorType<?>, StructureProcessorType<TfcBlockReplacementProcessor>> BLOCK_REPLACEMENT =
            STRUCTURE_PROCESSORS.register("block_replacement", () -> () -> TfcBlockReplacementProcessor.CODEC);

    private ModStructureProcessors() {
    }
}
