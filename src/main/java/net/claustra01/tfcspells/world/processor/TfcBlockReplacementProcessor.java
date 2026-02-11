package net.claustra01.tfcspells.world.processor;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.claustra01.tfcspells.ModStructureProcessors;
import net.claustra01.tfcspells.access.StructureTemplatePalettesAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Replaces certain vanilla blocks in jigsaw structures with TerraFirmaCraft equivalents.
 *
 * <p>This focuses on blocks where TFC differs significantly from vanilla, and on blocks that have TFC variants
 * (stone/wood/metal/soil/plants/decor).</p>
 */
public final class TfcBlockReplacementProcessor extends StructureProcessor {
    public static final TfcBlockReplacementProcessor INSTANCE = new TfcBlockReplacementProcessor();
    public static final MapCodec<TfcBlockReplacementProcessor> CODEC = MapCodec.unit(INSTANCE);

    private static final String NS_MINECRAFT = "minecraft";
    private static final String NS_TFC = "tfc";
    private static final String NS_BENEATH = "beneath";

    private static final String DEFAULT_ROCK_OVERWORLD = "granite";
    private static final String DEFAULT_ROCK_NETHER = "basalt";
    private static final String DEFAULT_ROCK_END = "granite";

    private static final String DEFAULT_SOIL = "mollisol";
    private static final String DEFAULT_WOOD = "oak";

    private static final ResourceLocation TFC_FIREPIT = ResourceLocation.fromNamespaceAndPath(NS_TFC, "firepit");
    private static final ResourceLocation TFC_THATCH_BED = ResourceLocation.fromNamespaceAndPath(NS_TFC, "thatch_bed");
    private static final ResourceLocation TFC_THATCH = ResourceLocation.fromNamespaceAndPath(NS_TFC, "thatch");

    private static final Set<String> VANILLA_WOOD_TYPES =
            Set.of(
                    "oak",
                    "spruce",
                    "birch",
                    "jungle",
                    "acacia",
                    "dark_oak",
                    "mangrove",
                    "cherry",
                    "bamboo");

    private static final ThreadLocal<Long2ObjectOpenHashMap<String>> ROCK_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);
    private static final ThreadLocal<Long2ObjectOpenHashMap<String>> SOIL_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);
    private static final ThreadLocal<Long2ObjectOpenHashMap<String>> WOOD_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);

    private enum ReplacementScope {
        FULL,
        UTILITY_ONLY
    }

    private TfcBlockReplacementProcessor() {}

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructureProcessors.TFC_BLOCK_REPLACEMENT.get();
    }

    @Override
    public @Nullable StructureTemplate.StructureBlockInfo process(
            LevelReader level,
            BlockPos offset,
            BlockPos pos,
            StructureTemplate.StructureBlockInfo rawBlockInfo,
            StructureTemplate.StructureBlockInfo processedBlockInfo,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        // In worldgen, the "level" is usually a WorldGenLevel/WorldGenRegion, not a ServerLevel.
        // We resolve the underlying ServerLevel for dimension-specific defaults.
        var serverLevel = resolveServerLevel(level);
        ReplacementScope scope = ReplacementScope.FULL;
        if (serverLevel != null && serverLevel.dimension() != Level.OVERWORLD) {
            scope = ReplacementScope.UTILITY_ONLY;
        }

        BlockState in = processedBlockInfo.state();
        Block inBlock = in.getBlock();

        // Skip air quickly.
        if (in.isAir()) {
            return processedBlockInfo;
        }

        ResourceLocation inId = BuiltInRegistries.BLOCK.getKey(inBlock);
        if (!NS_MINECRAFT.equals(inId.getNamespace())) {
            return processedBlockInfo;
        }

        String path = inId.getPath();
        if (shouldSkipReplacement(path)) {
            return processedBlockInfo;
        }
        if (path.startsWith("infested_")) {
            path = path.substring("infested_".length());
        }

        // Tall seagrass is a double-block plant. Replacing it with a single-block aquatic plant works best if the upper
        // half becomes water (otherwise the "upper" plant block tends to pop off).
        if ("tall_seagrass".equals(path)
                && in.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && in.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return new StructureTemplate.StructureBlockInfo(
                    processedBlockInfo.pos(), Blocks.WATER.defaultBlockState(), processedBlockInfo.nbt());
        }

        // Cache context once per template placement origin (offset).
        long cacheKey = offset.asLong();
        String rock = DEFAULT_ROCK_OVERWORLD;
        String soil = DEFAULT_SOIL;
        if (scope == ReplacementScope.FULL) {
            String defaultRock = defaultRockFor(serverLevel);

            Long2ObjectOpenHashMap<String> rockCache = ROCK_CACHE.get();
            if (rockCache.size() > 2048) {
                rockCache.clear();
            }
            String cachedRock = rockCache.get(cacheKey);
            if (cachedRock == null) {
                cachedRock = findRockNameBelow(level, offset);
                if (cachedRock == null) {
                    cachedRock = defaultRock;
                }
                rockCache.put(cacheKey, cachedRock);
            }
            rock = cachedRock;

            Long2ObjectOpenHashMap<String> soilCache = SOIL_CACHE.get();
            if (soilCache.size() > 2048) {
                soilCache.clear();
            }
            String cachedSoil = soilCache.get(cacheKey);
            if (cachedSoil == null) {
                cachedSoil = findSoilNameBelow(level, offset);
                if (cachedSoil == null) {
                    cachedSoil = DEFAULT_SOIL;
                }
                soilCache.put(cacheKey, cachedSoil);
            }
            soil = cachedSoil;
        }

        Long2ObjectOpenHashMap<String> woodCache = WOOD_CACHE.get();
        if (woodCache.size() > 2048) {
            woodCache.clear();
        }
        String woodHint = woodCache.get(cacheKey);
        if (woodHint == null) {
            woodHint = resolveWoodHint(path, offset, settings, template);
            woodCache.put(cacheKey, woodHint);
        }

        @Nullable ResourceLocation outId =
                mapVanillaToTfc(path, rock, soil, woodHint, scope);
        if (outId == null) {
            return processedBlockInfo;
        }

        Block outBlock = BuiltInRegistries.BLOCK.getOptional(outId).orElse(null);
        if (outBlock == null || outBlock == Blocks.AIR) {
            return processedBlockInfo;
        }

        BlockState out = copyPropertiesByName(in, outBlock.defaultBlockState());

        CompoundTag outNbt = processedBlockInfo.nbt();
        if (TFC_FIREPIT.equals(outId)) {
            out = applyFirepitAxisFromFacing(in, out);
            // Furnace/campfire block entity tags don't make sense on a firepit and can cause odd behavior.
            outNbt = null;
        }

        return new StructureTemplate.StructureBlockInfo(processedBlockInfo.pos(), out, outNbt);
    }

    private static BlockState applyFirepitAxisFromFacing(BlockState from, BlockState firepit) {
        if (!from.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return firepit;
        }

        Direction facing = from.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Direction.Axis axis = facing.getAxis();
        if (axis != Direction.Axis.X && axis != Direction.Axis.Z) {
            return firepit;
        }

        if (firepit.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return firepit.setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
        }
        if (firepit.hasProperty(BlockStateProperties.AXIS)) {
            return firepit.setValue(BlockStateProperties.AXIS, axis);
        }
        return firepit;
    }

    private static @Nullable ServerLevel resolveServerLevel(LevelReader level) {
        if (level instanceof ServerLevel sl) {
            return sl;
        }
        if (level instanceof WorldGenLevel wgl) {
            return wgl.getLevel();
        }
        return null;
    }

    private static String defaultRockFor(@Nullable ServerLevel level) {
        if (level == null) {
            return DEFAULT_ROCK_OVERWORLD;
        }
        if (level.dimension() == Level.NETHER) {
            return DEFAULT_ROCK_NETHER;
        }
        if (level.dimension() == Level.END) {
            return DEFAULT_ROCK_END;
        }
        return DEFAULT_ROCK_OVERWORLD;
    }

    private static boolean shouldSkipReplacement(String vanillaPath) {
        // Per request: don't attempt to remap blackstone/deepslate families.
        return vanillaPath.contains("blackstone") || vanillaPath.contains("deepslate");
    }

    private static @Nullable ResourceLocation mapVanillaToTfc(
            String vanillaPath,
            String rock,
            String soil,
            String woodHint,
            ReplacementScope scope) {
        @Nullable ResourceLocation beneath = mapBeneathNether(vanillaPath);
        if (beneath != null) {
            return beneath;
        }

        // Always replace vanilla fire/cooking blocks, even in UTILITY_ONLY scope.
        // We only target decorative equivalents here and intentionally drop vanilla furnace-like NBT.
        @Nullable ResourceLocation firepit = mapFirepit(vanillaPath);
        if (firepit != null) {
            return firepit;
        }

        if (scope == ReplacementScope.UTILITY_ONLY) {
            return mapUtilityOnly(vanillaPath, woodHint);
        }

        @Nullable ResourceLocation sandstone = mapSandstone(vanillaPath);
        if (sandstone != null) {
            return sandstone;
        }

        // Stone families (rock-dependent).
        @Nullable ResourceLocation stone = mapStone(vanillaPath, rock);
        if (stone != null) {
            return stone;
        }

        // Soils (soil-dependent).
        @Nullable ResourceLocation soilBlock = mapSoil(vanillaPath, soil);
        if (soilBlock != null) {
            return soilBlock;
        }

        // Wood families.
        @Nullable ResourceLocation wood = mapWood(vanillaPath, woodHint);
        if (wood != null) {
            return wood;
        }

        // Metals.
        @Nullable ResourceLocation metal = mapMetal(vanillaPath);
        if (metal != null) {
            return metal;
        }

        @Nullable ResourceLocation crops = mapCrops(vanillaPath);
        if (crops != null) {
            return crops;
        }

        // Plants + decor.
        @Nullable ResourceLocation plantDecor = mapPlantsAndDecor(vanillaPath);
        if (plantDecor != null) {
            return plantDecor;
        }

        // Misc utilities.
        @Nullable ResourceLocation cauldron = mapCauldron(vanillaPath);
        if (cauldron != null) {
            return cauldron;
        }

        // Lights (TFC has its own torches / lamps).
        @Nullable ResourceLocation lights = mapLights(vanillaPath);
        if (lights != null) {
            return lights;
        }

        return null;
    }

    private static @Nullable ResourceLocation mapFirepit(String vanillaPath) {
        return switch (vanillaPath) {
            case "furnace", "campfire" -> TFC_FIREPIT;
            default -> null;
        };
    }

    private static @Nullable ResourceLocation mapUtilityOnly(String vanillaPath, String woodHint) {
        // Wood utility blocks.
        @Nullable ResourceLocation wood = mapWoodUtilityOnly(vanillaPath, woodHint);
        if (wood != null) {
            return wood;
        }

        @Nullable ResourceLocation metal = mapMetal(vanillaPath);
        if (metal != null) {
            return metal;
        }

        @Nullable ResourceLocation crops = mapCrops(vanillaPath);
        if (crops != null) {
            return crops;
        }

        // Small decor we can safely convert.
        @Nullable ResourceLocation plantDecor = mapPlantsAndDecor(vanillaPath);
        if (plantDecor != null) {
            return plantDecor;
        }

        @Nullable ResourceLocation cauldron = mapCauldron(vanillaPath);
        if (cauldron != null) {
            return cauldron;
        }

        @Nullable ResourceLocation lights = mapLights(vanillaPath);
        if (lights != null) {
            return lights;
        }

        return null;
    }

    private static @Nullable ResourceLocation mapWoodUtilityOnly(String vanillaPath, String woodHint) {
        switch (vanillaPath) {
            case "chest":
                return tfcWood("wood/chest/", woodHint);
            case "trapped_chest":
                return tfcWood("wood/trapped_chest/", woodHint);
            // Notably absent: barrels. TFC barrels are processing block entities (not vanilla barrels) and Iron's
            // structures use barrel loot tables; a naive swap would likely lose loot or change behavior.
            // case "bookshelf":
            //     return tfcWood("wood/bookshelf/", woodHint);
            case "lectern":
                return tfcWood("wood/lectern/", woodHint);
            case "crafting_table":
                return tfcWood("wood/workbench/", woodHint);
            case "fletching_table":
                return tfcWood("wood/scribing_table/", woodHint);
            // case "smithing_table":
            //     return ResourceLocation.fromNamespaceAndPath(NS_TFC, "quern");
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapCauldron(String vanillaPath) {
        // TFC doesn't have a direct cauldron equivalent. We only replace the empty cauldron, because filled cauldrons
        // encode their contents in the block type (water/lava/powder snow) and a naive swap would lose that detail.
        return "cauldron".equals(vanillaPath) ? ResourceLocation.fromNamespaceAndPath(NS_TFC, "ceramic/large_vessel") : null;
    }

    private static @Nullable ResourceLocation mapLights(String vanillaPath) {
        switch (vanillaPath) {
            case "torch":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "torch");
            case "wall_torch":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "wall_torch");
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapSandstone(String vanillaPath) {
        // Vanilla uses two sandstones: yellow sandstone and red sandstone. TFC provides colored sandstone blocks with
        // equivalent shapes; we map to the matching color group.
        String suffix = "";
        String base = vanillaPath;

        if (base.endsWith("_slab")) {
            suffix = "_slab";
            base = base.substring(0, base.length() - "_slab".length());
        } else if (base.endsWith("_stairs")) {
            suffix = "_stairs";
            base = base.substring(0, base.length() - "_stairs".length());
        } else if (base.endsWith("_wall")) {
            suffix = "_wall";
            base = base.substring(0, base.length() - "_wall".length());
        }

        @Nullable String kind = null;
        @Nullable String color = null;
        switch (base) {
            case "sandstone" -> {
                kind = "raw_sandstone";
                color = "yellow";
            }
            case "smooth_sandstone" -> {
                kind = "smooth_sandstone";
                color = "yellow";
            }
            case "cut_sandstone" -> {
                kind = "cut_sandstone";
                color = "yellow";
            }
            case "red_sandstone" -> {
                kind = "raw_sandstone";
                color = "red";
            }
            case "smooth_red_sandstone" -> {
                kind = "smooth_sandstone";
                color = "red";
            }
            case "cut_red_sandstone" -> {
                kind = "cut_sandstone";
                color = "red";
            }
            default -> {}
        }
        if (kind == null || color == null) {
            return null;
        }

        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, kind + "/" + color + suffix);
        return BuiltInRegistries.BLOCK.containsKey(candidate) ? candidate : null;
    }

    private static @Nullable ResourceLocation mapStone(String vanillaPath, String rock) {
        String p = vanillaPath;

        // Amethyst geode blocks.
        switch (p) {
            case "amethyst_block":
            case "budding_amethyst":
            case "amethyst_cluster":
            case "large_amethyst_bud":
            case "medium_amethyst_bud":
            case "small_amethyst_bud":
                return tfcAmethystOre(rock);
            case "calcite":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "calcite");
            default:
                break;
        }

        // Stone bricks
        switch (p) {
            case "stone_bricks":
                return tfcRock("rock/bricks/", rock);
            case "mossy_stone_bricks":
                return tfcRock("rock/mossy_bricks/", rock);
            case "cracked_stone_bricks":
                return tfcRock("rock/cracked_bricks/", rock);
            case "chiseled_stone_bricks":
                return tfcRock("rock/chiseled/", rock);
            case "stone_brick_stairs":
                return tfcRock("rock/bricks/", rock, "_stairs");
            case "stone_brick_slab":
                return tfcRock("rock/bricks/", rock, "_slab");
            case "stone_brick_wall":
                return tfcRock("rock/bricks/", rock, "_wall");
            case "mossy_stone_brick_stairs":
                return tfcRock("rock/mossy_bricks/", rock, "_stairs");
            case "mossy_stone_brick_slab":
                return tfcRock("rock/mossy_bricks/", rock, "_slab");
            case "mossy_stone_brick_wall":
                return tfcRock("rock/mossy_bricks/", rock, "_wall");
            default:
                break;
        }

        // Cobblestone
        switch (p) {
            case "cobblestone":
                return tfcRock("rock/cobble/", rock);
            case "mossy_cobblestone":
                return tfcRock("rock/mossy_cobble/", rock);
            case "cobblestone_stairs":
                return tfcRock("rock/cobble/", rock, "_stairs");
            case "cobblestone_slab":
                return tfcRock("rock/cobble/", rock, "_slab");
            case "cobblestone_wall":
                return tfcRock("rock/cobble/", rock, "_wall");
            case "mossy_cobblestone_stairs":
                return tfcRock("rock/mossy_cobble/", rock, "_stairs");
            case "mossy_cobblestone_slab":
                return tfcRock("rock/mossy_cobble/", rock, "_slab");
            case "mossy_cobblestone_wall":
                return tfcRock("rock/mossy_cobble/", rock, "_wall");
            default:
                break;
        }

        // Generic stone
        switch (p) {
            case "stone":
                return tfcRock("rock/raw/", rock);
            case "stone_stairs":
                return tfcRock("rock/raw/", rock, "_stairs");
            case "stone_slab":
                return tfcRock("rock/raw/", rock, "_slab");
            case "smooth_stone":
                return tfcRock("rock/smooth/", rock);
            case "smooth_stone_slab":
                return tfcRock("rock/smooth/", rock, "_slab");
            default:
                break;
        }

        // Gravel
        if ("gravel".equals(p)) {
            return tfcRock("rock/gravel/", rock);
        }

        if ("tuff".equals(p)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "rock/raw/tuff");
        }

        // Ores (rock-dependent).
        switch (p) {
            case "emerald_ore":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "ore/emerald/" + rock);
            case "gold_ore":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "ore/normal_native_gold/" + rock);
            default:
                break;
        }

        // Andesite / diorite / granite families (structures use these frequently for variation).
        switch (p) {
            case "andesite":
            case "diorite":
            case "granite":
                return tfcRock("rock/raw/", rock);
            case "andesite_stairs":
            case "diorite_stairs":
            case "granite_stairs":
                return tfcRock("rock/raw/", rock, "_stairs");
            case "andesite_slab":
            case "diorite_slab":
            case "granite_slab":
                return tfcRock("rock/raw/", rock, "_slab");
            case "andesite_wall":
            case "diorite_wall":
            case "granite_wall":
                return tfcRock("rock/raw/", rock, "_wall");
            case "polished_andesite":
            case "polished_diorite":
            case "polished_granite":
                return tfcRock("rock/smooth/", rock);
            case "polished_andesite_stairs":
            case "polished_diorite_stairs":
            case "polished_granite_stairs":
                return tfcRock("rock/smooth/", rock, "_stairs");
            case "polished_andesite_slab":
            case "polished_diorite_slab":
            case "polished_granite_slab":
                return tfcRock("rock/smooth/", rock, "_slab");
            default:
                break;
        }

        // Redstone / interaction blocks with rock variants.
        switch (p) {
            case "stone_button":
                return tfcRock("rock/button/", rock);
            case "stone_pressure_plate":
                return tfcRock("rock/pressure_plate/", rock);
            default:
                break;
        }

        return null;
    }

    private static @Nullable ResourceLocation mapSoil(String vanillaPath, String soil) {
        switch (vanillaPath) {
            case "dirt":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "dirt/" + soil);
            case "coarse_dirt":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "coarse_dirt/" + soil);
            case "podzol":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "dirt/podzol");
            case "grass_block":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "grass/" + soil);
            case "dirt_path":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "grass_path/" + soil);
            case "rooted_dirt":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "rooted_dirt/" + soil);
            case "farmland":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "farmland/" + soil);
            case "mud":
            case "packed_mud":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "mud/" + soil);
            case "mud_bricks":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "mud_bricks/" + soil);
            case "mud_brick_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "mud_bricks/" + soil + "_slab");
            case "mud_brick_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "mud_bricks/" + soil + "_stairs");
            case "mud_brick_wall":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "mud_bricks/" + soil + "_wall");
            case "muddy_mangrove_roots":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "muddy_roots/" + soil);
            case "mangrove_roots":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "tree_roots");
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapWood(String vanillaPath, String woodHint) {
        // Functional / decor blocks with TFC variants (no vanilla wood encoded).
        switch (vanillaPath) {
            case "chest":
                return tfcWood("wood/chest/", woodHint);
            case "trapped_chest":
                return tfcWood("wood/trapped_chest/", woodHint);
            // Notably absent: barrels. TFC barrels are processing block entities (not vanilla barrels) and Iron's
            // structures use barrel loot tables; a naive swap would likely lose loot or change behavior.
            // case "bookshelf":
            //     return tfcWood("wood/bookshelf/", woodHint);
            case "lectern":
                return tfcWood("wood/lectern/", woodHint);
            case "crafting_table":
                return tfcWood("wood/workbench/", woodHint);
            case "fletching_table":
                return tfcWood("wood/scribing_table/", woodHint);
            // case "smithing_table":
            //     return ResourceLocation.fromNamespaceAndPath(NS_TFC, "quern");
            default:
                break;
        }

        if ("stripped_bamboo_block".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "wood/stripped_log/palm");
        }

        // Planks family: <wood>_planks, <wood>_stairs, <wood>_slab
        if (vanillaPath.endsWith("_planks")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_planks".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWoodPlanks(wood);
        }
        if (vanillaPath.endsWith("_stairs")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_stairs".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWoodPlanks(wood, "_stairs");
        }
        if (vanillaPath.endsWith("_slab")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_slab".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWoodPlanks(wood, "_slab");
        }

        // Logs/wood
        if (vanillaPath.startsWith("stripped_") && vanillaPath.endsWith("_log")) {
            String wood = vanillaPath.substring("stripped_".length(), vanillaPath.length() - "_log".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/stripped_log/", wood);
        }
        if (vanillaPath.startsWith("stripped_") && vanillaPath.endsWith("_wood")) {
            String wood = vanillaPath.substring("stripped_".length(), vanillaPath.length() - "_wood".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/stripped_wood/", wood);
        }
        if (vanillaPath.endsWith("_log")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_log".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/log/", wood);
        }
        if (vanillaPath.endsWith("_wood")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_wood".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/wood/", wood);
        }
        if (vanillaPath.endsWith("_leaves")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_leaves".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/leaves/", wood);
        }

        // Wood utilities
        if (vanillaPath.endsWith("_fence_gate")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_fence_gate".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/fence_gate/", wood);
        }
        if (vanillaPath.endsWith("_fence")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_fence".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/fence/", wood);
        }
        if (vanillaPath.endsWith("_door")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_door".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/door/", wood);
        }
        if (vanillaPath.endsWith("_trapdoor")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_trapdoor".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/trapdoor/", wood);
        }

        if (vanillaPath.endsWith("_pressure_plate")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_pressure_plate".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/pressure_plate/", wood);
        }

        if (vanillaPath.endsWith("_button")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_button".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/button/", wood);
        }

        if (vanillaPath.endsWith("_wall_sign")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_wall_sign".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/wall_sign/", wood);
        }

        if (vanillaPath.endsWith("_sign")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_sign".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/sign/", wood);
        }

        return null;
    }

    private static boolean isVanillaWood(String wood) {
        return VANILLA_WOOD_TYPES.contains(wood);
    }

    private static @Nullable ResourceLocation mapMetal(String vanillaPath) {
        switch (vanillaPath) {
            case "iron_bars":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/bars/wrought_iron");
            case "chain":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/chain/wrought_iron");
            case "iron_block":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/wrought_iron");
            case "iron_trapdoor":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/trapdoor/wrought_iron");
            case "bell":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "bronze_bell");
            case "gold_block":
            case "raw_gold_block":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/gold");
            case "copper_block":
            case "cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/copper");
            case "cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/copper_slab");
            case "cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/copper_stairs");
            case "exposed_copper":
            case "exposed_cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/exposed_block/copper");
            case "exposed_cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/exposed_block/copper_slab");
            case "exposed_cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/exposed_block/copper_stairs");
            case "weathered_copper":
            case "weathered_cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/weathered_block/copper");
            case "weathered_cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/weathered_block/copper_slab");
            case "weathered_cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/weathered_block/copper_stairs");
            case "oxidized_copper":
            case "oxidized_cut_copper":
            case "waxed_oxidized_cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/oxidized_block/copper");
            case "oxidized_cut_copper_slab":
            case "waxed_oxidized_cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/oxidized_block/copper_slab");
            case "oxidized_cut_copper_stairs":
            case "waxed_oxidized_cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/oxidized_block/copper_stairs");
            case "oxidized_copper_trapdoor":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/trapdoor/copper");
            // case "anvil":
            // case "chipped_anvil":
            // case "damaged_anvil":
            //     return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/anvil/wrought_iron");
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapCrops(String vanillaPath) {
        return switch (vanillaPath) {
            case "potatoes" -> ResourceLocation.fromNamespaceAndPath(NS_TFC, "crop/potato");
            case "pumpkin_stem", "attached_pumpkin_stem" -> ResourceLocation.fromNamespaceAndPath(NS_TFC, "crop/pumpkin");
            case "melon_stem" -> ResourceLocation.fromNamespaceAndPath(NS_TFC, "crop/melon");
            case "pumpkin", "carved_pumpkin" -> ResourceLocation.fromNamespaceAndPath(NS_TFC, "pumpkin");
            case "melon" -> ResourceLocation.fromNamespaceAndPath(NS_TFC, "melon");
            default -> null;
        };
    }

    private static @Nullable ResourceLocation mapPlantsAndDecor(String vanillaPath) {
        // Kelp in ocean monuments can be fragile with TFC water mechanics. Replace it with plain water.
        if ("kelp".equals(vanillaPath) || "kelp_plant".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_MINECRAFT, "water");
        }

        // Simple plants with direct TFC equivalents.
        switch (vanillaPath) {
            case "dead_bush" -> {
                ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/dead_bush");
                if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                    return candidate;
                }
            }
            case "azalea" -> {
                ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/azalea");
                if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                    return candidate;
                }
            }
            default -> {}
        }

        // Seagrass.
        if ("seagrass".equals(vanillaPath) || "tall_seagrass".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/eel_grass");
        }

        if ("sea_pickle".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "sea_pickle");
        }

        // Flower pots.
        if (vanillaPath.startsWith("potted_")) {
            String plant = vanillaPath.substring("potted_".length());

            // Potted saplings: vanilla uses "potted_<wood>_sapling", TFC uses "wood/potted_sapling/<wood>".
            if (plant.endsWith("_sapling")) {
                String wood = plant.substring(0, plant.length() - "_sapling".length());
                if (isVanillaWood(wood)) {
                    ResourceLocation candidate =
                            ResourceLocation.fromNamespaceAndPath(NS_TFC, "wood/potted_sapling/" + normalizeWood(wood));
                    if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                        return candidate;
                    }
                }
            }

            // Vanilla naming differs from TFC for some potted plants.
            if ("azalea_bush".equals(plant)) {
                ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/potted/azalea");
                if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                    return candidate;
                }
            }
            if ("azure_bluet".equals(plant)) {
                ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/potted/houstonia");
                if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                    return candidate;
                }
            }
            if ("red_tulip".equals(plant)) {
                ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/potted/tulip_red");
                if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                    return candidate;
                }
            }

            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/potted/" + plant);
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }

        // Candles.
        if ("candle".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle");
        }
        if (vanillaPath.endsWith("_candle")) {
            String color = vanillaPath.substring(0, vanillaPath.length() - "_candle".length());
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle/" + color);
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }
        if ("candle_cake".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle_cake");
        }
        if (vanillaPath.endsWith("_candle_cake")) {
            String color = vanillaPath.substring(0, vanillaPath.length() - "_candle_cake".length());
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle_cake/" + color);
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }

        // Beds.
        if (vanillaPath.endsWith("_bed")) {
            return TFC_THATCH_BED;
        }

        // Hay.
        if ("hay_block".equals(vanillaPath)) {
            return TFC_THATCH;
        }

        // Coal blocks.
        if ("coal_block".equals(vanillaPath)) {
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "bituminous_coal");
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }

        // Terracotta -> hardened clay.
        if ("terracotta".equals(vanillaPath)) {
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "hardened_clay");
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }

        // Glass. TFC provides poured glass blocks, including colored variants, but does not provide panes.
        if ("glass".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "poured_glass");
        }
        if (vanillaPath.endsWith("_stained_glass")) {
            String color = vanillaPath.substring(0, vanillaPath.length() - "_stained_glass".length());
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, color + "_poured_glass");
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private static @Nullable ResourceLocation mapBeneathNether(String vanillaPath) {
        // Only returns blocks that exist in the registry. If Beneath isn't installed, this returns null.
        return switch (vanillaPath) {
            case "crimson_planks" -> beneath("wood/planks/crimson");
            case "crimson_slab" -> beneath("wood/planks/crimson_slab");
            case "crimson_stairs" -> beneath("wood/planks/crimson_stairs");
            case "crimson_door" -> beneath("wood/door/crimson");
            case "crimson_trapdoor" -> beneath("wood/trapdoor/crimson");
            case "crimson_button" -> beneath("wood/button/crimson");
            case "crimson_pressure_plate" -> beneath("wood/pressure_plate/crimson");
            case "crimson_fence" -> beneath("wood/fence/crimson");
            case "crimson_fence_gate" -> beneath("wood/fence_gate/crimson");
            case "crimson_sign" -> beneath("wood/sign/crimson");
            case "crimson_wall_sign" -> beneath("wood/wall_sign/crimson");
            case "crimson_stem" -> beneath("wood/log/crimson");
            case "crimson_hyphae" -> beneath("wood/wood/crimson");
            case "stripped_crimson_stem" -> beneath("wood/stripped_log/crimson");
            case "stripped_crimson_hyphae" -> beneath("wood/stripped_wood/crimson");
            case "crimson_roots" -> beneath("crop/crimson_roots");
            case "warped_planks" -> beneath("wood/planks/warped");
            case "warped_slab" -> beneath("wood/planks/warped_slab");
            case "warped_stairs" -> beneath("wood/planks/warped_stairs");
            case "warped_door" -> beneath("wood/door/warped");
            case "warped_trapdoor" -> beneath("wood/trapdoor/warped");
            case "warped_button" -> beneath("wood/button/warped");
            case "warped_pressure_plate" -> beneath("wood/pressure_plate/warped");
            case "warped_fence" -> beneath("wood/fence/warped");
            case "warped_fence_gate" -> beneath("wood/fence_gate/warped");
            case "warped_sign" -> beneath("wood/sign/warped");
            case "warped_wall_sign" -> beneath("wood/wall_sign/warped");
            case "warped_stem" -> beneath("wood/log/warped");
            case "warped_hyphae" -> beneath("wood/wood/warped");
            case "stripped_warped_stem" -> beneath("wood/stripped_log/warped");
            case "stripped_warped_hyphae" -> beneath("wood/stripped_wood/warped");
            case "warped_roots" -> beneath("crop/warped_roots");
            case "nether_wart" -> beneath("crop/nether_wart");
            case "nether_gold_ore" -> beneath("ore/normal_nether_gold");
            default -> null;
        };
    }

    private static @Nullable ResourceLocation beneath(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(NS_BENEATH, path);
        return BuiltInRegistries.BLOCK.containsKey(id) ? id : null;
    }

    private static @Nullable String detectVanillaWoodType(String path) {
        // Strip common prefixes first.
        String p = path;
        if (p.startsWith("stripped_")) {
            p = p.substring("stripped_".length());
        }

        for (String wood : VANILLA_WOOD_TYPES) {
            if (p.startsWith(wood + "_")) {
                return wood;
            }
        }
        return null;
    }

    private static String resolveWoodHint(
            String currentPath,
            BlockPos offset,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        // Fast path: if the current block encodes a wood type, we can use it immediately.
        @Nullable String detected = detectVanillaWoodType(currentPath);
        if (detected != null) {
            return detected;
        }

        // Otherwise, derive a stable hint from the template palette (independent of placement order).
        if (template instanceof StructureTemplatePalettesAccess palettesAccess) {
            try {
                List<StructureTemplate.Palette> palettes = palettesAccess.tfcspells$getPalettes();
                StructureTemplate.Palette palette = settings.getRandomPalette(palettes, offset);
                @Nullable String dominant = dominantVanillaWoodType(palette.blocks());
                if (dominant != null) {
                    return dominant;
                }
            } catch (Exception ignored) {
                // Defensive: fall back to default.
            }
        }

        return DEFAULT_WOOD;
    }

    private static @Nullable String dominantVanillaWoodType(List<StructureTemplate.StructureBlockInfo> blocks) {
        Map<String, Integer> counts = new HashMap<>();
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            BlockState state = info.state();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (!NS_MINECRAFT.equals(id.getNamespace())) {
                continue;
            }

            @Nullable String wood = detectVanillaWoodType(id.getPath());
            if (wood == null) {
                continue;
            }

            counts.put(wood, counts.getOrDefault(wood, 0) + 1);
        }

        @Nullable String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count > bestCount) {
                bestCount = count;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static ResourceLocation tfcRock(String prefix, String rock) {
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + rock);
    }

    private static ResourceLocation tfcRock(String prefix, String rock, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + rock + suffix);
    }

    private static ResourceLocation tfcAmethystOre(String rock) {
        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "ore/amethyst/" + rock);
        if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
            return candidate;
        }
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, "ore/amethyst/" + DEFAULT_ROCK_OVERWORLD);
    }

    private static ResourceLocation tfcWoodPlanks(String wood) {
        return tfcWoodPlanks(wood, "");
    }

    private static ResourceLocation tfcWoodPlanks(String wood, String suffix) {
        String w = normalizeWood(wood);
        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "wood/planks/" + w + suffix);
        if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
            return candidate;
        }
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, "wood/planks/" + DEFAULT_WOOD + suffix);
    }

    private static ResourceLocation tfcWood(String prefix, String wood) {
        String w = normalizeWood(wood);
        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + w);
        if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
            return candidate;
        }
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + DEFAULT_WOOD);
    }

    private static String normalizeWood(String wood) {
        // Map vanilla wood types to TFC equivalents where they exist.
        return switch (wood) {
            case "jungle" -> "kapok";
            case "dark_oak" -> "blackwood";
            case "cherry" -> "rosewood";
            case "bamboo" -> "palm";
            default -> wood;
        };
    }

    private static @Nullable String findRockNameBelow(LevelReader level, BlockPos start) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(start.getX(), start.getY(), start.getZ());
        int minY = level.getMinBuildHeight();

        for (int i = 0; i < 64 && cursor.getY() >= minY; i++) {
            BlockState state = level.getBlockState(cursor);
            @Nullable String rock = rockNameFromTfcBlock(state);
            if (rock != null) {
                return rock;
            }
            cursor.move(0, -1, 0);
        }
        return null;
    }

    private static @Nullable String findSoilNameBelow(LevelReader level, BlockPos start) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(start.getX(), start.getY(), start.getZ());
        int minY = level.getMinBuildHeight();

        for (int i = 0; i < 64 && cursor.getY() >= minY; i++) {
            BlockState state = level.getBlockState(cursor);
            @Nullable String soil = soilNameFromTfcBlock(state);
            if (soil != null) {
                return soil;
            }
            cursor.move(0, -1, 0);
        }
        return null;
    }

    private static @Nullable String rockNameFromTfcBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!NS_TFC.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        if (!path.startsWith("rock/")) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        String tail = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        tail = stripSuffix(tail, "_stairs");
        tail = stripSuffix(tail, "_slab");
        tail = stripSuffix(tail, "_wall");
        return tail.isEmpty() ? null : tail;
    }

    private static @Nullable String soilNameFromTfcBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!NS_TFC.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        // Soil-like blocks have the soil type as the last path segment.
        if (!(path.startsWith("dirt/")
                || path.startsWith("coarse_dirt/")
                || path.startsWith("grass/")
                || path.startsWith("grass_path/")
                || path.startsWith("rooted_dirt/")
                || path.startsWith("farmland/")
                || path.startsWith("clay_grass/")
                || path.startsWith("mud/")
                || path.startsWith("mud_bricks/")
                || path.startsWith("muddy_roots/"))) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        String tail = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        tail = stripSuffix(tail, "_stairs");
        tail = stripSuffix(tail, "_slab");
        tail = stripSuffix(tail, "_wall");
        return tail.isEmpty() ? null : tail;
    }

    private static String stripSuffix(String s, String suffix) {
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyPropertiesByName(BlockState from, BlockState to) {
        StateDefinition<Block, BlockState> def = to.getBlock().getStateDefinition();
        for (Property<?> fromProp : from.getProperties()) {
            Property<?> toProp = def.getProperty(fromProp.getName());
            if (toProp == null) {
                continue;
            }

            Comparable value = from.getValue((Property) fromProp);
            if (!((Property) toProp).getPossibleValues().contains(value)) {
                continue;
            }

            try {
                to = to.setValue((Property) toProp, value);
            } catch (Exception ignored) {
                // Defensive: if a property value can't be applied, just skip it.
            }
        }
        return to;
    }
}
