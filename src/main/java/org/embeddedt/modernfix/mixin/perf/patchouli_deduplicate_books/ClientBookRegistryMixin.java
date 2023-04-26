package org.embeddedt.modernfix.mixin.perf.patchouli_deduplicate_books;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import org.embeddedt.modernfix.ModernFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.BookPage;
import vazkii.patchouli.client.book.ClientBookRegistry;
import vazkii.patchouli.client.book.page.PageTemplate;
import vazkii.patchouli.client.book.template.BookTemplate;
import vazkii.patchouli.client.book.template.TemplateComponent;
import vazkii.patchouli.client.book.template.component.ComponentItemStack;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.common.book.BookRegistry;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(ClientBookRegistry.class)
public class ClientBookRegistryMixin {
    @Inject(method = "reload", at = @At("RETURN"), remap = false)
    private void performDeduplication(CallbackInfo ci) {
        Field templateField = ObfuscationReflectionHelper.findField(PageTemplate.class, "template");
        Field componentsField = ObfuscationReflectionHelper.findField(BookTemplate.class, "components");
        Field itemsField = ObfuscationReflectionHelper.findField(ComponentItemStack.class, "items");
        int numItemsCleared = 0;
        for(Book book : BookRegistry.INSTANCE.books.values()) {
            for(BookEntry entry : book.contents.entries.values()) {
                for(BookPage page : entry.getPages()) {
                    if(page instanceof PageTemplate) {
                        List<TemplateComponent> components;
                        try {
                            BookTemplate template = (BookTemplate)templateField.get(page);
                            components = (List<TemplateComponent>)componentsField.get(template);
                            for(TemplateComponent component : components) {
                                if(component instanceof ComponentItemStack) {
                                    ItemStack[] items = (ItemStack[])itemsField.get(component);
                                    for(int i = 0; i < items.length; i++) {
                                        if(items[i] != null && items[i].getItem() == Items.AIR) {
                                            items[i] = ItemStack.EMPTY;
                                        }
                                    }
                                }
                            }
                        } catch(ReflectiveOperationException e) {
                            continue;
                        }
                    }
                }
            }
        }
        ModernFix.LOGGER.info("Cleared {} unneeded book NBT tags", numItemsCleared);
    }
}
