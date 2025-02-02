package com.momosoftworks.coldsweat.common.container;

import com.mojang.datafixers.util.Pair;
import com.momosoftworks.coldsweat.common.capability.IInsulatableCap;
import com.momosoftworks.coldsweat.common.capability.ItemInsulationCap;
import com.momosoftworks.coldsweat.common.capability.ItemInsulationManager;
import com.momosoftworks.coldsweat.core.advancement.trigger.ModAdvancementTriggers;
import com.momosoftworks.coldsweat.core.event.TaskScheduler;
import com.momosoftworks.coldsweat.core.init.MenuInit;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.util.math.CSMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SewingContainer extends AbstractContainerMenu
{
    BlockPos pos;
    Inventory playerInventory;
    SewingInventory sewingInventory;

    public static class SewingInventory implements Container
    {
        private final NonNullList<ItemStack> stackList;
        private final AbstractContainerMenu menu;

        public SewingInventory(AbstractContainerMenu menu)
        {
            this.stackList = NonNullList.withSize(3, ItemStack.EMPTY);
            this.menu = menu;
        }

        @Override
        public int getContainerSize()
        {
            return 3;
        }

        @Override
        public boolean isEmpty()
        {
            return !stackList.stream().anyMatch(stack -> !stack.isEmpty());
        }

        @Nonnull
        @Override
        public ItemStack getItem(int index)
        {
            return stackList.get(index);
        }

        @Nonnull
        @Override
        public ItemStack removeItem(int index, int count)
        {
            ItemStack itemstack = ContainerHelper.removeItem(this.stackList, index, count);
            if (!itemstack.isEmpty()) {
                this.menu.slotsChanged(this);
            }

            return itemstack;
        }

        @Nonnull
        @Override
        public ItemStack removeItemNoUpdate(int index)
        {
            return ContainerHelper.takeItem(this.stackList, index);
        }

        @Override
        public void setItem(int index, ItemStack stack)
        {
            this.stackList.set(index, stack);
            this.menu.slotsChanged(this);
        }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player player)
        {
            return true;
        }

        @Override
        public void clearContent()
        {
            stackList.clear();
        }
    }

    public SewingContainer(final int windowId, final Inventory playerInv)
    {
        super(MenuInit.SEWING_CONTAINER_TYPE.get(), windowId);
        this.pos = playerInv.player.blockPosition();
        this.playerInventory = playerInv;
        sewingInventory = new SewingInventory(this);

        // Input 1
        this.addSlot(new Slot(sewingInventory, 0, 43, 26)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                Pair<Double, Double> insulation = ItemInsulationManager.getItemInsulation(stack);
                return stack.getItem() instanceof Equipable && !ConfigSettings.INSULATION_BLACKLIST.get().contains(ForgeRegistries.ITEMS.getKey(stack.getItem()))
                    && insulation.getFirst() == 0 && insulation.getSecond() == 0;
            }
            @Override
            public void onTake(Player player, ItemStack stack)
            {
                super.onTake(player, stack);
                SewingContainer.this.takeInput();
            }
            @Override
            public void setChanged()
            {
                super.setChanged();
                SewingContainer.this.testForRecipe();
            }
        });

        // Input 2
        this.addSlot(new Slot(sewingInventory, 1, 43, 53)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                Pair<Double, Double> insulation = ItemInsulationManager.getItemInsulation(stack);
                return insulation.getFirst() != 0 || insulation.getSecond() != 0 || stack.getItem() instanceof ShearsItem;
            }
            @Override
            public void onTake(Player player, ItemStack stack)
            {
                super.onTake(player, stack);
                SewingContainer.this.takeInput();
            }
            @Override
            public void setChanged()
            {
                super.setChanged();
                SewingContainer.this.testForRecipe();
            }
        });

        // Output
        this.addSlot(new Slot(sewingInventory, 2, 121, 39)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                return false;
            }

            @Override
            public void onTake(Player player, ItemStack stack)
            {
                super.onTake(player, stack);
                SewingContainer.this.takeOutput(stack);

                if (!SewingContainer.this.playerInventory.player.level().isClientSide)
                    TaskScheduler.scheduleServer(() -> testForRecipe(), 1);
            }
        });

        // Main player inventory
        for (int row = 0; row < 3; row++)
        {
            for (int col = 0; col < 9; col++)
            {
                this.addSlot(new Slot(playerInv, col + (9 * row) + 9, 8 + col * 18, 166 - (4 - row) * 18 - 10));
            }
        }

        // Player Hotbar
        for (int col = 0; col < 9; col++)
        {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
    }

    public SewingContainer(int i, Inventory inventory, FriendlyByteBuf friendlyByteBuf)
    {
        this(i, inventory);
        try {
            this.pos = BlockPos.of(friendlyByteBuf.readLong());
        } catch (Exception ignored) {}
    }

    public void setItem(int index, ItemStack stack)
    {
        this.sewingInventory.setItem(index, stack);
        this.setRemoteSlot(index, stack);
    }

    public void growItem(int index, int amount)
    {
        ItemStack stack = this.sewingInventory.getItem(index);
        stack.grow(amount);
        this.sewingInventory.setItem(index, stack);
        this.setRemoteSlot(index, stack);
    }

    public ItemStack getItem(int index)
    {
        return this.sewingInventory.getItem(index);
    }

    private void takeInput()
    {
        this.sewingInventory.setItem(2, ItemStack.EMPTY);
    }
    private void takeOutput(ItemStack stack)
    {
        Player player = this.playerInventory.player;
        ItemStack input1 = this.getItem(0);
        ItemStack input2 = this.getItem(1);

        ItemInsulationManager.getInsulationCap(input1).ifPresent(cap ->
        {
            // If insulation is being removed
            if (this.getItem(1).getItem() instanceof ShearsItem)
            {
                if (!cap.getInsulation().isEmpty())
                {   // Damage shears
                    if (!player.isCreative())
                    {   input2.hurt(1, player.getRandom(), null);
                    }

                    // Remove the last insulation item added
                    cap.removeInsulationItem(cap.getInsulationItem(cap.getInsulation().size() - 1));
                    // Play shear sound
                    player.level().playSound(null, player.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 0.8F, 1.0F);

                    serializeInsulation(input1, cap);
                }
            }
            // If insulation is being added
            else
            {   // Remove input items
                this.growItem(0, -1);
                this.growItem(1, -1);
                player.level().playSound(null, player.blockPosition(), SoundEvents.LLAMA_SWAG, SoundSource.BLOCKS, 0.5f, 1f);

                // Trigger advancement criteria
                if (player instanceof ServerPlayer serverPlayer)
                    ModAdvancementTriggers.ARMOR_INSULATED.trigger(serverPlayer, input1, input2);
            }
        });

        // Get equip sound for the armor item
        if (stack.getItem() instanceof ArmorItem armor)
        {   SoundEvent equipSound = armor.getMaterial().getEquipSound();
            player.level().playSound(null, player.blockPosition(), equipSound, SoundSource.BLOCKS, 1f, 1f);
        }
    }

    static void serializeInsulation(ItemStack stack, IInsulatableCap iCap)
    {
        if (iCap instanceof ItemInsulationCap cap)
        {
            CompoundTag tag = cap.serializeSimple(stack);

            // Remove "Insulation" tag if armor has no insulation left
            if (iCap.getInsulation().isEmpty())
            {   tag.remove("Insulation");
            }
        }
    }

    private void testForRecipe()
    {
        ItemStack wearableItem = this.getItem(0);
        ItemStack insulatorItem = this.getItem(1);

        // Is the first item armor, and the second item an insulator
        if (wearableItem.getItem() instanceof Equipable)
        {
            // Shears are used to remove insulation
            if (insulatorItem.getItem() instanceof ShearsItem)
            {
                ItemInsulationManager.getInsulationCap(wearableItem).ifPresent(cap ->
                {
                    if (cap.getInsulation().size() > 0)
                    {   this.setItem(2, cap.getInsulationItem(cap.getInsulation().size() - 1).copy());
                    }
                });
            }
            // Item is for insulation
            else if ((ConfigSettings.INSULATION_ITEMS.get().containsKey(insulatorItem.getItem()) || ConfigSettings.ADAPTIVE_INSULATION_ITEMS.get().containsKey(insulatorItem.getItem()))
            && (!(insulatorItem.getItem() instanceof Equipable)
            || LivingEntity.getEquipmentSlotForItem(wearableItem) == LivingEntity.getEquipmentSlotForItem(insulatorItem)))
            {
                ItemStack processed = wearableItem.copy();
                IInsulatableCap insulCap = ItemInsulationManager.getInsulationCap(processed).orElseThrow(() -> new IllegalStateException("Item does not have insulation capability"));
                ItemStack insulator = insulatorItem.copy();
                insulator.setCount(1);
                insulCap.addInsulationItem(insulator);

                // Cancel crafting if the insulation provided by the insulator is too much
                AtomicInteger posInsul = new AtomicInteger();
                AtomicInteger negInsul = new AtomicInteger();
                // Get the total positive/negative insulation of the armor
                insulCap.getInsulation().stream().map(Pair::getSecond).flatMap(Collection::stream).forEach(pair ->
                {
                    if (pair instanceof ItemInsulationCap.Insulation insul)
                    {   if (insul.getHot() > 0 || insul.getCold() > 0) posInsul.getAndIncrement();
                        else negInsul.getAndIncrement();
                    }
                    if (pair instanceof ItemInsulationCap.AdaptiveInsulation insul)
                    {   if (insul.getInsulation() > 0) posInsul.getAndIncrement();
                        else negInsul.getAndIncrement();
                    }
                });
                if (posInsul.get() > ItemInsulationManager.getInsulationSlots(wearableItem) || negInsul.get() > ItemInsulationManager.getInsulationSlots(wearableItem))
                {   return;
                }

                // Transfer enchantments
                Map<Enchantment, Integer> armorEnch = EnchantmentHelper.getEnchantments(processed);
                insulator.getEnchantmentTags().removeIf(nbt ->
                {
                    CompoundTag enchantTag = ((CompoundTag) nbt);
                    Enchantment ench = ForgeRegistries.ENCHANTMENTS.getValue(new ResourceLocation(enchantTag.getString("id")));
                    if (ench == null) return false;

                    if (ench.canApplyAtEnchantingTable(wearableItem) && armorEnch.keySet().stream().allMatch(ench2 -> ench2.isCompatibleWith(ench)))
                    {   processed.enchant(ench, enchantTag.getInt("lvl"));
                        return true;
                    }
                    return false;
                });

                serializeInsulation(processed, insulCap);

                this.setItem(2, processed);
            }
        }
    }

    public SewingContainer(final int windowId, final Player playerInv, BlockPos pos)
    {
        this(windowId, playerInv.getInventory());
        this.pos = pos;
    }

    @Override
    public void removed(Player player)
    {
        super.removed(player);

        // Drop the contents of the input slots
        if (player instanceof ServerPlayer)
        {
            for (int i = 0; i < sewingInventory.getContainerSize(); i++)
            {
                ItemStack itemStack = this.getSlot(i).getItem();
                if (!itemStack.isEmpty() && i != 2)
                {
                    if (player.isAlive() && !((ServerPlayer) player).hasDisconnected())
                    {
                        player.getInventory().placeItemBackInInventory(itemStack);
                    }
                    else player.drop(itemStack, false, true);
                }
            }
        }
    }

    @Override
    public boolean stillValid(Player playerIn)
    {
        if (this.pos != null)
            return playerIn.distanceToSqr(Vec3.atCenterOf(this.pos)) <= 64.0D;
        else return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index)
    {
        ItemStack newStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (slot.hasItem())
        {
            ItemStack slotItem = slot.getItem();
            newStack = slotItem.copy();
            if (CSMath.isWithin(index, 0, 2))
            {
                if (this.moveItemStackTo(slotItem, 3, 39, true))
                {
                    slot.onTake(player, newStack);
                }
                else return ItemStack.EMPTY;
            }
            else
            {
                // Item is valid insulation
                if (this.slots.get(1).mayPlace(slotItem))
                {
                    if (this.moveItemStackTo(slotItem, 1, 2, false))
                    {   slot.onQuickCraft(slotItem, newStack);
                    }
                    else return ItemStack.EMPTY;
                }
                // Item is valid wearable
                else if (this.slots.get(0).mayPlace(slotItem))
                {
                    if (!this.moveItemStackTo(slotItem, 0, 1, false))
                    {   slot.onQuickCraft(slotItem, newStack);
                    }
                    else return ItemStack.EMPTY;
                }
                // Item is in hotbar
                else if (CSMath.isWithin(index, slots.size() - 9, slots.size()))
                {
                    if (!this.moveItemStackTo(slotItem, 3, 29, false))
                    {   slot.onQuickCraft(slotItem, newStack);
                    }
                    else return ItemStack.EMPTY;
                }
                // Item is in inventory
                else if (CSMath.isWithin(index, 3, slots.size() - 9))
                {
                    if (!this.moveItemStackTo(slotItem, slots.size() - 9, slots.size(), false))
                    {   slot.onQuickCraft(slotItem, newStack);
                    }
                    else return ItemStack.EMPTY;
                }
                return ItemStack.EMPTY;
            }

            if (slotItem.isEmpty())
            {   slot.set(ItemStack.EMPTY);
            }
            else slot.setChanged();
        }

        return newStack;
    }
}
