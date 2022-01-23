package net.momostudios.coldsweat.common.event;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.SleepFinishedTimeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.momostudios.coldsweat.ColdSweat;
import net.momostudios.coldsweat.common.temperature.Temperature;
import net.momostudios.coldsweat.common.temperature.modifier.*;
import net.momostudios.coldsweat.common.world.TempModifierEntries;
import net.momostudios.coldsweat.util.registrylists.ModEffects;
import net.momostudios.coldsweat.util.PlayerTemp;

@Mod.EventBusSubscriber(modid = ColdSweat.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class AddTempModifiers
{
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
        {
            PlayerEntity player = event.player;

            /*
             * Add TempModifiers if not present
             */
            if (player.ticksExisted % 20 == 0)
            {
                PlayerTemp.addModifier(player, new BiomeTempModifier(), PlayerTemp.Types.AMBIENT, false);
                PlayerTemp.addModifier(player, new TimeTempModifier(), PlayerTemp.Types.AMBIENT, false);
                PlayerTemp.addModifier(player, new DepthTempModifier(), PlayerTemp.Types.AMBIENT, false);
                PlayerTemp.addModifier(player, new BlockTempModifier(), PlayerTemp.Types.AMBIENT, false);
                if (ModList.get().isLoaded("sereneseasons"))
                    PlayerTemp.addModifier(player, TempModifierEntries.getEntries().getEntryFor("sereneseasons:season"), PlayerTemp.Types.AMBIENT, false);

                // Hearth
                if (player.isPotionActive(ModEffects.INSULATION))
                {
                    int potionLevel = player.getActivePotionEffect(ModEffects.INSULATION).getAmplifier() + 1;
                    if (PlayerTemp.hasModifier(player, HearthTempModifier.class, PlayerTemp.Types.AMBIENT))
                    {
                        PlayerTemp.forEachModifier(player, PlayerTemp.Types.AMBIENT, (modifier) ->
                        {
                            if (modifier instanceof HearthTempModifier)
                            {
                                modifier.setArgument("strength", potionLevel);
                            }
                        });
                    }
                    else
                        PlayerTemp.addModifier(player, new HearthTempModifier(potionLevel), PlayerTemp.Types.AMBIENT, false);
                }
                else if (PlayerTemp.hasModifier(player, HearthTempModifier.class, PlayerTemp.Types.AMBIENT))
                {
                    PlayerTemp.removeModifiers(player, PlayerTemp.Types.AMBIENT, 1, modifier -> modifier instanceof HearthTempModifier);
                }
            }

            // Water / Rain
            if (player.ticksExisted % 5 == 0)
            {
                if (player.isInWater() || player.world.isRainingAt(player.getPosition()))
                {
                    PlayerTemp.addModifier(player, new WaterTempModifier(0.15), PlayerTemp.Types.AMBIENT, false);
                }
                else if (PlayerTemp.hasModifier(player, WaterTempModifier.class, PlayerTemp.Types.AMBIENT))
                {
                    PlayerTemp.removeModifiers(player, PlayerTemp.Types.AMBIENT, 1, modifier -> modifier instanceof WaterTempModifier && (double) modifier.getArgument("strength") <= 0);
                }
            }

            // Nether Lamp
            if (player.getPersistentData().getInt("soulLampTimeout") <= 0 && PlayerTemp.hasModifier(player, NetherLampTempModifier.class, PlayerTemp.Types.AMBIENT))
            {
                PlayerTemp.removeModifiers(player, PlayerTemp.Types.AMBIENT, 1, modifier -> modifier instanceof NetherLampTempModifier);
            }
            else
            {
                player.getPersistentData().putInt("soulLampTimeout", player.getPersistentData().getInt("soulLampTimeout") - 1);
            }
        }
    }

    @SubscribeEvent
    public static void onSleep(SleepFinishedTimeEvent event)
    {
        event.getWorld().getPlayers().forEach(player ->
        {
            if (player.isSleeping())
            {
                Temperature temp = PlayerTemp.getTemperature(player, PlayerTemp.Types.BODY);
                PlayerTemp.setTemperature(player, new Temperature(temp.get() / 4), PlayerTemp.Types.BODY);
            }
        });
    }
}