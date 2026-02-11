package net.claustra01.tfcspells.access;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Mixin access interface used to read the palettes of a {@link StructureTemplate}.
 *
 * <p>This allows processors to derive stable, per-structure hints (ex: dominant wood type) without relying on
 * placement order.</p>
 */
public interface StructureTemplatePalettesAccess {
    List<StructureTemplate.Palette> tfcspells$getPalettes();
}

