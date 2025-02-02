package com.momosoftworks.coldsweat.common.capability;

import com.google.common.collect.ImmutableSet;
import com.momosoftworks.coldsweat.ColdSweat;
import com.momosoftworks.coldsweat.api.registry.TempModifierRegistry;
import com.momosoftworks.coldsweat.api.temperature.modifier.*;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.api.util.Temperature.Addition;
import com.momosoftworks.coldsweat.api.util.Temperature.Addition.Mode;
import com.momosoftworks.coldsweat.api.util.Temperature.Addition.Order;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.config.EntitySettingsConfig;
import com.momosoftworks.coldsweat.util.compat.CompatManager;
import com.momosoftworks.coldsweat.util.math.CSMath;
import com.momosoftworks.coldsweat.util.registries.*;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.SleepFinishedTimeEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Mod.EventBusSubscriber
public class EntityTempManager
{
    public static final Temperature.Type[] VALID_TEMPERATURE_TYPES = {Temperature.Type.CORE, Temperature.Type.BASE, Temperature.Type.FREEZING_POINT, Temperature.Type.BURNING_POINT, Temperature.Type.WORLD};
    public static final Temperature.Type[] VALID_MODIFIER_TYPES    = {Temperature.Type.CORE, Temperature.Type.BASE, Temperature.Type.RATE, Temperature.Type.FREEZING_POINT, Temperature.Type.BURNING_POINT, Temperature.Type.WORLD};
    public static final Set<EntityType<?>> TEMPERATURE_ENABLED_ENTITIES = new HashSet<>(ImmutableSet.<EntityType<?>>builder().add(EntityType.PLAYER).build());

    public static final Map<Entity, LazyOptional<ITemperatureCap>> SERVER_CAP_CACHE = new HashMap<>();
    public static final Map<Entity, LazyOptional<ITemperatureCap>> CLIENT_CAP_CACHE = new HashMap<>();

    /**
     * Attach temperature capability to entities
     */
    @SubscribeEvent
    public static void attachCapabilityToEntityHandler(AttachCapabilitiesEvent<Entity> event)
    {
        if (event.getObject() instanceof LivingEntity entity)
        {
            if (!TEMPERATURE_ENABLED_ENTITIES.contains(entity.getType())) return;

            // Make a new capability instance to attach to the entity
            ITemperatureCap tempCap = entity instanceof Player ? new PlayerTempCap() : new EntityTempCap();
            // Optional that holds the capability instance
            LazyOptional<ITemperatureCap> capOptional = LazyOptional.of(() -> tempCap);

            // Capability provider
            ICapabilityProvider provider = new ICapabilitySerializable<CompoundTag>()
            {
                @Nonnull
                @Override
                public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction direction)
                {
                    // If the requested cap is the temperature cap, return the temperature cap
                    if (cap == ModCapabilities.PLAYER_TEMPERATURE)
                    {
                        return capOptional.cast();
                    }
                    return LazyOptional.empty();
                }

                @Override
                public CompoundTag serializeNBT()
                {
                    return tempCap.serializeNBT();
                }

                @Override
                public void deserializeNBT(CompoundTag nbt)
                {
                    tempCap.deserializeNBT(nbt);
                }
            };

            // Attach the capability to the entity
            event.addCapability(new ResourceLocation(ColdSweat.MOD_ID, "temperature"), provider);
        }
    }

    public static LazyOptional<ITemperatureCap> getTemperatureCap(Entity entity)
    {
        Map<Entity, LazyOptional<ITemperatureCap>> cache = entity.level().isClientSide ? CLIENT_CAP_CACHE : SERVER_CAP_CACHE;
        return cache.computeIfAbsent(entity, e ->
        {   LazyOptional<ITemperatureCap> cap = e.getCapability(entity instanceof Player ? ModCapabilities.PLAYER_TEMPERATURE : ModCapabilities.ENTITY_TEMPERATURE);
            cap.addListener((opt) -> cache.remove(e));
            return cap;
        });
    }

    /**
     * Tick TempModifiers & update temperature for living entities
     */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event)
    {
        LivingEntity entity = event.getEntity();
        if (!TEMPERATURE_ENABLED_ENTITIES.contains(entity.getType())) return;

        getTemperatureCap(entity).ifPresent(cap ->
        {
            if (!entity.level().isClientSide)
            {   // Tick modifiers serverside
                cap.tick(entity);
            }
            else
            {   // Tick modifiers clientside
                cap.tickDummy(entity);
            }

            // Remove expired modifiers
            for (Temperature.Type type : VALID_MODIFIER_TYPES)
            {
                cap.getModifiers(type).removeIf(modifier ->
                {
                    int expireTime = modifier.getExpireTime();
                    return (modifier.setTicksExisted(modifier.getTicksExisted() + 1) > expireTime && expireTime != -1);
                });
            }

            if (entity instanceof Player && entity.tickCount % 60 == 0)
            {   Temperature.updateModifiers(entity, cap);
            }
        });
    }

    /**
     * Transfer the player's capability when traveling from the End
     */
    @SubscribeEvent
    public static void returnFromEnd(PlayerEvent.Clone event)
    {
        if (!event.isWasDeath() && !event.getEntity().level().isClientSide)
        {
            // Get the old player's capability
            Player oldPlayer = event.getOriginal();
            oldPlayer.reviveCaps();

            // Copy the capability to the new player
            getTemperatureCap(event.getEntity()).ifPresent(cap ->
            {
                getTemperatureCap(oldPlayer).ifPresent(cap::copy);
            });

            oldPlayer.invalidateCaps();
        }
    }

    /**
     * Add modifiers to the player & valid entities when they join the world
     */
    @SubscribeEvent
    public static void initModifiersOnEntity(EntityJoinLevelEvent event)
    {
        // Add basic TempModifiers to player
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide)
        {
            // Sometimes the entity isn't fully initialized, so wait until next tick
            if (player.getServer() != null)
            player.getServer().execute(() ->
            {
                // Add modifiers separately to ensure order
                Temperature.addModifier(player, new BiomeTempModifier(25).tickRate(10), Temperature.Type.WORLD, false, Addition.AT_START);
                Temperature.addModifier(player, new UndergroundTempModifier().tickRate(10), Temperature.Type.WORLD, false, Addition.of(Mode.AFTER, Order.FIRST, mod -> mod instanceof BiomeTempModifier));
                Temperature.addModifier(player, new BlockTempModifier().tickRate(4), Temperature.Type.WORLD, false, Addition.of(Mode.AFTER, Order.FIRST, mod -> mod instanceof UndergroundTempModifier));

                // Serene Seasons compat
                if (CompatManager.isSereneSeasonsLoaded())
                {
                    TempModifierRegistry.getEntryFor("sereneseasons:season").ifPresent(mod -> Temperature.addModifier(player, mod.tickRate(60), Temperature.Type.WORLD, false,
                                            Addition.of(Mode.BEFORE, Order.FIRST, mod2 -> mod2 instanceof UndergroundTempModifier)));
                }
                // Weather2 Compat
                if (CompatManager.isWeather2Loaded())
                {
                    TempModifierRegistry.getEntryFor("weather2:storm").ifPresent(mod -> Temperature.addModifier(player, mod.tickRate(60), Temperature.Type.WORLD, false,
                                            Addition.of(Mode.BEFORE, Order.FIRST, mod2 -> mod2 instanceof UndergroundTempModifier)));
                }

                Temperature.set(player, Temperature.Type.WORLD, Temperature.apply(0, player, Temperature.Type.WORLD, Temperature.getModifiers(player, Temperature.Type.WORLD)));
            });

            // Add listener for granting the sewing table recipe when the player gets an insulation item
            player.containerMenu.addSlotListener(new ContainerListener()
            {
                public void slotChanged(AbstractContainerMenu menu, int slotIndex, ItemStack stack)
                {   Slot slot = menu.getSlot(slotIndex);
                    if (!(slot instanceof ResultSlot))
                    {
                        if (slot.container == player.getInventory()
                        && (ConfigSettings.INSULATION_ITEMS.get().containsKey(stack.getItem())
                        || ConfigSettings.ADAPTIVE_INSULATION_ITEMS.get().containsKey(stack.getItem())))
                        {   player.awardRecipesByKey(new ResourceLocation[]{new ResourceLocation(ColdSweat.MOD_ID, "sewing_table")});
                        }
                    }
                }
                public void dataChanged(AbstractContainerMenu p_143462_, int p_143463_, int p_143464_) {}
            });
        }
        // Add basic TempModifiers to chameleons
        else if (event.getEntity() instanceof LivingEntity entity && TEMPERATURE_ENABLED_ENTITIES.contains(entity.getType()))
        {
            // Sometimes the entity isn't fully initialized, so wait until next tick
            if (entity.getServer() != null)
            entity.getServer().execute(() ->
            {
                // Basic modifiers
                Temperature.addModifiers(entity, List.of(new BiomeTempModifier(9).tickRate(40),
                                                                                    new UndergroundTempModifier().tickRate(40),
                                                                                    new BlockTempModifier(4).tickRate(20)), Temperature.Type.WORLD, false);
                // Serene Seasons compat
                if (CompatManager.isSereneSeasonsLoaded())
                {
                    TempModifierRegistry.getEntryFor("sereneseasons:season").ifPresent(mod -> Temperature.addModifier(entity, mod.tickRate(60), Temperature.Type.WORLD, false,
                                                                                                                      Addition.of(Mode.BEFORE, Order.FIRST, mod2 -> mod2 instanceof UndergroundTempModifier)));
                }
                // Weather2 Compat
                if (CompatManager.isWeather2Loaded())
                {
                    TempModifierRegistry.getEntryFor("weather2:storm").ifPresent(mod -> Temperature.addModifier(entity, mod.tickRate(60), Temperature.Type.WORLD, false,
                                                                                                                Addition.of(Mode.BEFORE, Order.FIRST, mod2 -> mod2 instanceof UndergroundTempModifier)));
                }

                Temperature.set(entity, Temperature.Type.WORLD, Temperature.apply(0, entity, Temperature.Type.WORLD, Temperature.getModifiers(entity, Temperature.Type.WORLD)));
            });
        }
    }

    /**
     * Handle modifiers for freezing, burning, and being wet
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        Player player = event.player;

        // Water / Rain
        if (!player.level().isClientSide && event.phase == TickEvent.Phase.START)
        {
            if (player.tickCount % 5 == 0)
            {
                if (WorldHelper.isInWater(player) || player.tickCount % 40 == 0 && WorldHelper.isRainingAt(player.level(), player.blockPosition()))
                    Temperature.addModifier(player, new WaterTempModifier(0.01f).tickRate(5), Temperature.Type.WORLD, false);

                if (player.isFreezing())
                    Temperature.addOrReplaceModifier(player, new FreezingTempModifier(player.getTicksFrozen() / 13.5f).expires(5), Temperature.Type.BASE);

                if (player.isOnFire())
                    Temperature.addOrReplaceModifier(player, new FireTempModifier().expires(5), Temperature.Type.BASE);
            }

            if (player.isFreezing() && player.getTicksFrozen() > 0)
            {
                AtomicReference<Double> insulation = new AtomicReference<>((double) 0);
                boolean hasIcePotion = player.hasEffect(ModEffects.ICE_RESISTANCE) && ConfigSettings.ICE_RESISTANCE_ENABLED.get();

                if (!hasIcePotion)
                {
                    Temperature.getModifier(player, Temperature.Type.RATE, InsulationTempModifier.class).ifPresent(insulModifier ->
                    {
                        insulation.updateAndGet(v -> (v + insulModifier.getNBT().getDouble("Hot") + insulModifier.getNBT().getDouble("Cold")));
                    });
                }

                if (!(hasIcePotion || insulation.get() > 0) && (player.tickCount % Math.max(1, 37 - insulation.get())) == 0)
                {   player.setTicksFrozen(player.getTicksFrozen() - 1);
                }
            }
        }
    }

    /**
     * Cancel freezing damage when the player has the Ice Resistance effect
     */
    @SubscribeEvent
    public static void cancelFreezingDamage(LivingAttackEvent event)
    {
        if (event.getSource().equals(event.getEntity().level().damageSources().freeze()) && event.getEntity().hasEffect(ModEffects.ICE_RESISTANCE) && ConfigSettings.ICE_RESISTANCE_ENABLED.get())
        {   event.setCanceled(true);
        }
    }

    /**
     * Handle HearthTempModifier when the player has the Insulation effect
     */
    @SubscribeEvent
    public static void onInsulationUpdate(MobEffectEvent event)
    {
        if (!event.getEntity().level().isClientSide && event.getEntity() instanceof Player player && event.getEffectInstance() != null
        && event.getEffectInstance().getEffect() == ModEffects.INSULATION)
        {
            // Add TempModifier on potion effect added
            if (event instanceof MobEffectEvent.Added)
            {   MobEffectInstance effect = event.getEffectInstance();
                // New HearthTempModifier
                TempModifier newMod = new HearthTempModifier(effect.getAmplifier() + 1).expires(effect.getDuration());
                Temperature.addOrReplaceModifier(player, newMod, Temperature.Type.WORLD);
            }
            // Remove TempModifier on potion effect removed
            else if (event instanceof MobEffectEvent.Remove)
            {   Temperature.removeModifiers(player, Temperature.Type.WORLD, mod -> mod instanceof HearthTempModifier);
            }
        }
    }

    /**
     * Improve the player's temperature when they sleep
     */
    @SubscribeEvent
    public static void onSleep(SleepFinishedTimeEvent event)
    {
        if (!event.getLevel().isClientSide())
        {
            event.getLevel().players().forEach(player ->
            {
                if (player.isSleeping())
                {
                    // Divide the player's current temperature by 4
                    getTemperatureCap(player).ifPresent(cap ->
                    {
                        double temp = cap.getTemp(Temperature.Type.CORE);
                        cap.setTemp(Temperature.Type.CORE, temp / 4f);
                        Temperature.updateTemperature(player, cap, true);
                    });
                }
            });
        }
    }

    /**
     * Handle insulation on mounted entity
     */
    @SubscribeEvent
    public static void playerRiding(TickEvent.PlayerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END && !event.player.level().isClientSide() && event.player.tickCount % 5 == 0)
        {
            Player player = event.player;
            if (player.getVehicle() != null)
            {
                Entity mount = player.getVehicle();
                // If insulated minecart
                if (mount instanceof Minecart minecart && minecart.getDisplayBlockState().getBlock() == ModBlocks.MINECART_INSULATION)
                {   Temperature.addModifier(player, new MountTempModifier(20, 20).expires(1), Temperature.Type.RATE, false);
                }
                // If insulated entity (defined in config)
                else
                {
                    EntitySettingsConfig.getInstance().getInsulatedEntities().stream().filter(entry ->
                    entry.get(0).equals(ForgeRegistries.ENTITY_TYPES.getKey(mount.getType()).toString())).findFirst()
                    .ifPresent(entry ->
                    {   int warming = ((Number) entry.get(1)).intValue();
                        int cooling = entry.size() < 3
                                    ? warming
                                    : ((Number) entry.get(2)).intValue();
                        Temperature.addModifier(player, new MountTempModifier(warming, cooling).expires(5), Temperature.Type.RATE, false);
                    });
                }
            }
        }
    }

    /**
     * Handle TempModifiers for consumables
     */
    @SubscribeEvent
    public static void onEatFood(LivingEntityUseItemEvent.Finish event)
    {
        if (event.getEntity() instanceof Player player
        && (event.getItem().getUseAnimation() == UseAnim.DRINK || event.getItem().getUseAnimation() == UseAnim.EAT)
        && !event.getEntity().level().isClientSide)
        {
            // If food item defined in config
            float foodTemp = ConfigSettings.FOOD_TEMPERATURES.get().getOrDefault(event.getItem().getItem(), 0d).floatValue();
            if (foodTemp != 0)
            {   Temperature.addModifier(player, new FoodTempModifier(foodTemp).expires(0), Temperature.Type.CORE, true);
            }
            // Soul sprout
            else if (event.getItem().getItem() == ModItems.SOUL_SPROUT)
            {   Temperature.addOrReplaceModifier(player, new SoulSproutTempModifier().expires(900), Temperature.Type.BASE);
            }
        }
    }

    @SubscribeEvent
    public static void resetTempOnRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        if (!event.isEndConquered())
        getTemperatureCap(event.getEntity()).ifPresent(cap ->
        {
            cap.copy(new PlayerTempCap());
            if (!event.getEntity().level().isClientSide)
            {   Temperature.updateTemperature(event.getEntity(), cap, true);
            }
        });
    }

    public static Set<EntityType<?>> getEntitiesWithTemperature()
    {   return ImmutableSet.copyOf(TEMPERATURE_ENABLED_ENTITIES);
    }

    public static Set<AttributeInstance> getModifiableTempAttributes(LivingEntity entity)
    {
        return ImmutableSet.<AttributeInstance>builder()
                .add(entity.getAttribute(ModAttributes.WORLD_TEMPERATURE_OFFSET))
                .add(entity.getAttribute(ModAttributes.CORE_BODY_TEMPERATURE_OFFSET))
                .add(entity.getAttribute(ModAttributes.BASE_BODY_TEMPERATURE_OFFSET))
                .add(entity.getAttribute(ModAttributes.BURNING_POINT_OFFSET))
                .add(entity.getAttribute(ModAttributes.FREEZING_POINT_OFFSET))
                .add(entity.getAttribute(ModAttributes.COLD_DAMPENING))
                .add(entity.getAttribute(ModAttributes.HEAT_DAMPENING))
                .add(entity.getAttribute(ModAttributes.COLD_RESISTANCE))
                .add(entity.getAttribute(ModAttributes.HEAT_RESISTANCE)).build();
    }

    public static Double[] applyAttributesPre(LivingEntity entity)
    {   return getModifiableTempAttributes(entity).stream().map(attribute -> attribute == null ? 0 : attribute.getValue()).toArray(Double[]::new);
    }

    public static double[] applyAttributesPost(LivingEntity entity, double[] temps)
    {
        double[] tempsCopy = temps.clone();
        int i = 0;
        for (AttributeInstance attribute : getModifiableTempAttributes(entity))
        {
            if (attribute == null) continue;
            for (AttributeModifier modifier : attribute.getModifiers())
            {
                switch (modifier.getOperation())
                {
                    case ADDITION -> tempsCopy[i] += modifier.getAmount();
                    case MULTIPLY_BASE -> tempsCopy[i] *= 1 + modifier.getAmount();
                    case MULTIPLY_TOTAL -> tempsCopy[i] *= modifier.getAmount();
                    default -> {}
                }
            }
            i++;
        }

        CSMath.doIfNotNull(entity.getAttribute(ModAttributes.WORLD_TEMPERATURE), attribute -> attribute.setBaseValue(tempsCopy[0]));
        CSMath.doIfNotNull(entity.getAttribute(ModAttributes.CORE_BODY_TEMPERATURE), attribute -> attribute.setBaseValue(tempsCopy[1]));
        CSMath.doIfNotNull(entity.getAttribute(ModAttributes.BASE_BODY_TEMPERATURE), attribute -> attribute.setBaseValue(tempsCopy[2]));
        CSMath.doIfNotNull(entity.getAttribute(ModAttributes.BURNING_POINT), attribute -> attribute.setBaseValue(tempsCopy[3] + ConfigSettings.MAX_TEMP.get()));
        CSMath.doIfNotNull(entity.getAttribute(ModAttributes.FREEZING_POINT), attribute -> attribute.setBaseValue(tempsCopy[4] + ConfigSettings.MIN_TEMP.get()));

        return tempsCopy;
    }
}