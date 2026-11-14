package com.empire.folkways.ornamentum.content;

import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import com.ldtteam.domumornamentum.recipe.ModRecipeTypes;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipe;
import com.ldtteam.domumornamentum.recipe.architectscutter.ArchitectsCutterRecipeInput;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

/** 把一个「殖民地下单想要的精确 Do 物品」翻译成切割机配方与材料。 */
public final class CutterPlans {
    private CutterPlans() {
    }

    public record Plan(ResourceLocation recipe, Block block,
                       List<IMateriallyTexturedBlockComponent> components,
                       List<Block> chosen, ItemStack preview, long perRun) {
    }

    @SuppressWarnings("unchecked")
    public static List<RecipeHolder<ArchitectsCutterRecipe>> recipes(ServerLevel level) {
        RecipeType<ArchitectsCutterRecipe> type = ModRecipeTypes.ARCHITECTS_CUTTER.get();
        return level.getRecipeManager().getAllRecipesFor(type).stream()
                .sorted(Comparator.comparing(holder -> holder.id().toString()))
                .toList();
    }

    /** 只返回能精确满足 {@code wanted} 的方案；plain 需求接受任意材质/变体。 */
    public static List<Plan> plansFor(ServerLevel level, ItemSpec wanted) {
        ItemStack target = Goods.display(wanted);
        boolean trace = System.getProperty("folkways_ornamentum.debug") != null
                && wanted.toString().contains("slab");
        if (target.isEmpty() || !(target.getItem() instanceof BlockItem targetItem)) {
            if (trace) {
                com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                        "[CUTPLAN] no target wanted={} display={}", wanted, target);
            }
            return List.of();
        }
        if (trace) {
            com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                    "[CUTPLAN] target wanted={} stack={} block={}", wanted, target, targetItem.getBlock());
        }
        Map<ResourceLocation, Block> wantedTexture =
                MaterialTextureData.readFromItemStack(target).getTexturedComponents();

        List<Plan> plans = new ArrayList<>();
        for (RecipeHolder<ArchitectsCutterRecipe> holder : recipes(level)) {
            ArchitectsCutterRecipe recipe = holder.value();
            Block block = recipe.getBlock();
            if (!(block instanceof IMateriallyTexturedBlock textured)) {
                if (trace) {
                    com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                            "[CUTPLAN] not textured recipe={} block={}", holder.id(), block);
                }
                continue;
            }
            if (targetItem.getBlock() != block) {
                if (trace) {
                    com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                            "[CUTPLAN] block mismatch recipe={} block={} target={}",
                            holder.id(), block, targetItem.getBlock());
                }
                continue;
            }

            List<IMateriallyTexturedBlockComponent> components = List.copyOf(textured.getComponents());
            List<Block> chosen = new ArrayList<>(components.size());
            boolean viable = true;
            for (IMateriallyTexturedBlockComponent component : components) {
                Block material = wantedTexture.get(component.getId());
                if (!usable(material, component)) {
                    material = component.getDefault();
                }
                if (!usable(material, component)) {
                    viable = false;
                    break;
                }
                chosen.add(material);
            }
            if (!viable) {
                if (trace) {
                    com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                            "[CUTPLAN] not viable recipe={} components={} texture={}",
                            holder.id(), components, wantedTexture);
                }
                continue;
            }

            SimpleContainer input = new SimpleContainer(components.size());
            for (int index = 0; index < components.size(); index++) {
                input.setItem(index, new ItemStack(chosen.get(index).asItem()));
            }
            ArchitectsCutterRecipeInput recipeInput = new ArchitectsCutterRecipeInput(input);
            if (!recipe.matches(recipeInput, level)) {
                if (trace) {
                    com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                            "[CUTPLAN] recipe no match recipe={} chosen={}", holder.id(), chosen);
                }
                continue;
            }
            ItemStack assembled = recipe.assemble(recipeInput, level.registryAccess());
            if (assembled.isEmpty()) {
                if (trace) {
                    com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                            "[CUTPLAN] empty output recipe={}", holder.id());
                }
                continue;
            }
            // The cutter recipe is a validation gate; its output may omit the
            // domum_ornamentum:texture_data and block_state components that make
            // a Do item exact. Keep the wanted stack as the real output.
            ItemStack exact = target.copyWithCount(Math.max(1, assembled.getCount()));
            plans.add(new Plan(holder.id(), block, components, List.copyOf(chosen),
                    exact, exact.getCount()));
        }
        if (trace) {
            com.empire.folkways.ornamentum.FolkwaysOrnamentum.LOG.info(
                    "[CUTPLAN] result wanted={} plans={}", wanted, plans.size());
        }
        return List.copyOf(plans);
    }

    private static boolean usable(Block material, IMateriallyTexturedBlockComponent component) {
        return material != null
                && material.asItem() != Items.AIR
                && material.defaultBlockState().is(component.getValidSkins());
    }

    /** 每次切割消耗的材料（按组件顺序，一次一件）。 */
    public static List<ItemSpec> needs(Plan plan) {
        List<ItemSpec> needs = new ArrayList<>(plan.components().size());
        for (int index = 0; index < plan.components().size(); index++) {
            Block material = plan.chosen().get(index);
            needs.add(ItemSpec.of(BuiltInRegistries.ITEM.getKey(material.asItem())));
        }
        return List.copyOf(needs);
    }

    public static List<io.github.izakyl.folkways.core.work.api.Need> perRunNeeds(Plan plan) {
        List<io.github.izakyl.folkways.core.work.api.Need> needs =
                new ArrayList<>(plan.components().size());
        for (ItemSpec material : needs(plan)) {
            needs.add(new io.github.izakyl.folkways.core.work.api.Need(material, 1L));
        }
        return List.copyOf(needs);
    }
}
