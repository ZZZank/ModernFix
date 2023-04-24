package org.embeddedt.modernfix.core;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import cpw.mods.modlauncher.*;
import cpw.mods.modlauncher.api.INameMappingService;
import cpw.mods.modlauncher.api.LamdbaExceptionUtils;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.embeddedt.modernfix.classloading.FastAccessTransformerList;
import org.embeddedt.modernfix.classloading.ModernFixResourceFinder;
import org.embeddedt.modernfix.core.config.ModernFixEarlyConfig;
import org.embeddedt.modernfix.core.config.Option;
import org.embeddedt.modernfix.dfu.DFUBlaster;
import org.embeddedt.modernfix.load.ModWorkManagerQueue;
import org.embeddedt.modernfix.util.DummyList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectorGroupInfo;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ModernFixMixinPlugin implements IMixinConfigPlugin {
    private static final String MIXIN_PACKAGE_ROOT = "org.embeddedt.modernfix.mixin.";

    private final Logger logger = LogManager.getLogger("ModernFix");
    public static ModernFixEarlyConfig config = null;
    public static ModernFixMixinPlugin instance;

    public ModernFixMixinPlugin() {
        instance = this;
        try {
            config = ModernFixEarlyConfig.load(new File("./config/modernfix-mixins.properties"));
        } catch (Exception e) {
            throw new RuntimeException("Could not load configuration file for ModernFix", e);
        }

        this.logger.info("Loaded configuration file for ModernFix: {} options available, {} override(s) found",
                config.getOptionCount(), config.getOptionOverrideCount());

        try {
            Class.forName("sun.misc.Unsafe").getDeclaredMethod("defineAnonymousClass", Class.class, byte[].class, Object[].class);
        } catch(ReflectiveOperationException | NullPointerException e) {
            this.logger.info("Applying Nashorn fix");
            Properties properties = System.getProperties();
            properties.setProperty("nashorn.args", properties.getProperty("nashorn.args", "") + " --anonymous-classes=false");
        }

        /* We abuse the constructor of a mixin plugin as a safe location to start modifying the classloader */
        /* Swap the transformer for ours */
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if(!(loader instanceof TransformingClassLoader)) {
            throw new IllegalStateException("Expected a TransformingClassLoader");
        }
        try {
            if(isOptionEnabled("launch.class_search_cache.ModernFixResourceFinder")) {
                Field resourceFinderField = TransformingClassLoader.class.getDeclaredField("resourceFinder");
                /* Construct a new list of resource finders, using similar logic to ML */
                resourceFinderField.setAccessible(true);
                Function<String, Enumeration<URL>> resourceFinder = constructResourceFinder();
                /* Merge with the findResources implementation provided by the DelegatedClassLoader */
                Field dclField = TransformingClassLoader.class.getDeclaredField("delegatedClassLoader");
                dclField.setAccessible(true);
                URLClassLoader dcl = (URLClassLoader)dclField.get(loader);
                resourceFinder = EnumerationHelper.mergeFunctors(resourceFinder, LamdbaExceptionUtils.rethrowFunction(dcl::findResources));
                resourceFinderField.set(loader, resourceFinder);
            }
        } catch(RuntimeException | ReflectiveOperationException e) {
            logger.error("Failed to make classloading changes", e);
        }

        FastAccessTransformerList.attemptReplace();
        DFUBlaster.blastMaps();

        /* https://github.com/FabricMC/Mixin/pull/99 */
        try {
            Field groupMembersField = InjectorGroupInfo.class.getDeclaredField("members");
            groupMembersField.setAccessible(true);
            Field noGroupField = InjectorGroupInfo.Map.class.getDeclaredField("NO_GROUP");
            noGroupField.setAccessible(true);
            InjectorGroupInfo noGroup = (InjectorGroupInfo)noGroupField.get(null);
            groupMembersField.set(noGroup, new DummyList<>());
        } catch(RuntimeException | ReflectiveOperationException e) {
            logger.error("Failed to patch mixin memory leak", e);
        }
    }

    private Method defineClassMethod = null;

    private Class<?> injectClassIntoSystemLoader(String className) throws ReflectiveOperationException, IOException {
        ClassLoader systemLoader = ClassTransformer.class.getClassLoader();
        if(defineClassMethod == null) {
            defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
            defineClassMethod.setAccessible(true);
        }
        byte[] newTransformerBytes = Resources.toByteArray(ModernFixMixinPlugin.class.getResource("/" + className.replace('.', '/') + ".class"));
        return (Class<?>)defineClassMethod.invoke(systemLoader, className, newTransformerBytes, 0, newTransformerBytes.length);
    }

    private Function<String, Enumeration<URL>> constructResourceFinder() throws ReflectiveOperationException {
        ModernFixResourceFinder.init();
        Field servicesHandlerField = Launcher.class.getDeclaredField("transformationServicesHandler");
        servicesHandlerField.setAccessible(true);
        Object servicesHandler = servicesHandlerField.get(Launcher.INSTANCE);
        Field serviceLookupField = servicesHandler.getClass().getDeclaredField("serviceLookup");
        serviceLookupField.setAccessible(true);
        Map<String, TransformationServiceDecorator> serviceLookup = (Map<String, TransformationServiceDecorator>)serviceLookupField.get(servicesHandler);
        Method getClassLoaderMethod = TransformationServiceDecorator.class.getDeclaredMethod("getClassLoader");
        getClassLoaderMethod.setAccessible(true);
        Function<String, Enumeration<URL>> resourceEnumeratorLocator = ModernFixResourceFinder::findAllURLsForResource;
        for(TransformationServiceDecorator decorator : serviceLookup.values()) {
            Function<String, Optional<URL>> func = (Function<String, Optional<URL>>)getClassLoaderMethod.invoke(decorator);
            if(func != null) {
                resourceEnumeratorLocator = EnumerationHelper.mergeFunctors(resourceEnumeratorLocator, EnumerationHelper.fromOptional(func));
            }
        }
        return resourceEnumeratorLocator;
    }

    @Override
    public void onLoad(String mixinPackage) {
        try {
            if(isOptionEnabled("launch.transformer_cache.ModernFixClassTransformer")) {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Field classTransformerField = TransformingClassLoader.class.getDeclaredField("classTransformer");
                classTransformerField.setAccessible(true);
                ClassTransformer t = (ClassTransformer)classTransformerField.get(loader);
                TransformStore store = ObfuscationReflectionHelper.getPrivateValue(ClassTransformer.class, t, "transformers");
                LaunchPluginHandler pluginHandler = ObfuscationReflectionHelper.getPrivateValue(ClassTransformer.class, t, "pluginHandler");
                TransformerAuditTrail trail = ObfuscationReflectionHelper.getPrivateValue(ClassTransformer.class, t, "auditTrail");
                injectClassIntoSystemLoader("org.embeddedt.modernfix.util.FileUtil");
                injectClassIntoSystemLoader("org.embeddedt.modernfix.classloading.api.IHashableTransformer");
                injectClassIntoSystemLoader("org.embeddedt.modernfix.classloading.hashers.CoreModTransformerHasher");
                injectClassIntoSystemLoader("org.embeddedt.modernfix.classloading.hashers.MixinTransformerHasher");
                Class<?> newTransformerClass = injectClassIntoSystemLoader("cpw.mods.modlauncher.ModernFixCachingClassTransformer");
                Constructor<?> constructor = newTransformerClass.getConstructor(TransformStore.class, LaunchPluginHandler.class, TransformingClassLoader.class, TransformerAuditTrail.class);
                ClassTransformer newTransformer = (ClassTransformer)constructor.newInstance(store, pluginHandler, loader, trail);
                classTransformerField.set(loader, newTransformer);

                logger.info("Successfully injected caching transformer");
            }
        } catch(RuntimeException | ReflectiveOperationException | IOException e) {
            logger.error("Failed to make classloading changes", e);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(MIXIN_PACKAGE_ROOT)) {
            this.logger.error("Expected mixin '{}' to start with package root '{}', treating as foreign and " +
                    "disabling!", mixinClassName, MIXIN_PACKAGE_ROOT);

            return false;
        }

        String mixin = mixinClassName.substring(MIXIN_PACKAGE_ROOT.length());
        return isOptionEnabled(mixin);
    }

    public boolean isOptionEnabled(String mixin) {
        Option option = config.getEffectiveOptionForMixin(mixin);

        if (option == null) {
            this.logger.error("No rules matched mixin '{}', treating as foreign and disabling!", mixin);

            return false;
        }

        if (option.isOverridden()) {
            String source = "[unknown]";

            if (option.isUserDefined()) {
                source = "user configuration";
            } else if (option.isModDefined()) {
                source = "mods [" + String.join(", ", option.getDefiningMods()) + "]";
            }

            if (option.isEnabled()) {
                this.logger.warn("Force-enabling mixin '{}' as rule '{}' (added by {}) enables it", mixin,
                        option.getName(), source);
            } else {
                this.logger.warn("Force-disabling mixin '{}' as rule '{}' (added by {}) disables it and children", mixin,
                        option.getName(), source);
            }
        }

        return option.isEnabled();
    }
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if(mixinClassName.equals("org.embeddedt.modernfix.mixin.perf.compress_blockstate.BlockStateBaseMixin")) {
            // Delete unused fields off BlockStateBase
            Set<String> fieldsToDelete = Stream.of(
                    "field_235702_f_", // isAir
                    "field_235703_g_", // material
                    "field_235705_i_", // destroySpeed
                    "field_235706_j_", // requiresCorrectToolForDrops
                    "field_235707_k_", // canOcclude
                    "field_235708_l_", // isRedstoneConductor
                    "field_235709_m_", // isSuffocating
                    "field_235710_n_", // isViewBlocking
                    "field_235711_o_", // hasPostProcess
                    "field_235712_p_"  // emissiveRendering
            ).map(name -> ObfuscationReflectionHelper.remapName(INameMappingService.Domain.FIELD, name)).collect(Collectors.toSet());
            targetClass.fields.removeIf(field -> {
                if(fieldsToDelete.contains(field.name)) {
                    logger.info("Removing " + field.name);
                    return true;
                }
                return false;
            });
            for(MethodNode m : targetClass.methods) {
                if(m.name.equals("<init>")) {
                    ListIterator<AbstractInsnNode> iter = m.instructions.iterator();
                    while(iter.hasNext()) {
                        AbstractInsnNode node = iter.next();
                        if(node.getOpcode() == Opcodes.PUTFIELD) {
                            if(fieldsToDelete.contains(((FieldInsnNode)node).name)) {
                                iter.remove();
                            }
                        }
                    }
                }
            }
        } else if(mixinClassName.equals("org.embeddedt.modernfix.mixin.bugfix.chunk_deadlock.valhesia.BlockStateBaseMixin")) {
            // We need to destroy Valhelsia's callback so it can never run getBlockState
            for(MethodNode m : targetClass.methods) {
                if(m.name.contains("valhelsia_placeDousedTorch")) {
                    m.instructions.clear();
                    m.instructions.add(new InsnNode(Opcodes.RETURN));
                }
            }
        }
    }
}