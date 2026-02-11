package net.claustra01.tfcspells;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(TfcSpells.MOD_ID)
public final class TfcSpells {
    public static final String MOD_ID = "tfcspells";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TfcSpells(IEventBus modEventBus) {
        ModStructureProcessors.register(modEventBus);
    }
}
