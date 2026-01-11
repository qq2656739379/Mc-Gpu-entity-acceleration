package com.gpuaccel.entitymod;

import com.gpuaccel.entitymod.ai.VoxelManager;
import com.gpuaccel.entitymod.ai.SwarmAISystem;
import com.gpuaccel.entitymod.ai.ClimateSystem;
import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.gpuaccel.entitymod.config.SwarmConfig;
import com.gpuaccel.entitymod.config.VoxelConfig;
import com.gpuaccel.entitymod.example.ExampleCommands;
import com.gpuaccel.entitymod.gpu.GPUManager;
import com.gpuaccel.entitymod.physics.PhysicsSimulation;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Mod(GPUEntityAccelMod.MOD_ID)
public class GPUEntityAccelMod {
    public static final String MOD_ID = "gpuaccel";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static GPUManager gpuManager;
    private static SwarmAISystem swarmAISystem;
    private static PhysicsSimulation physicsSimulation;
    private static ClimateSystem climateSystem;
    private static boolean nativesLoaded = false;

    public GPUEntityAccelMod() {
        loadNatives();
        
        // 🛠️ 修复：显式指定文件名，解决 Config conflict detected 崩溃
        // 为每个 COMMON 类型的配置指定一个独特的文件名
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GPUAccelConfig.SPEC, "gpuaccel-general.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SwarmConfig.COMMON_SPEC, "gpuaccel-swarm.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VoxelConfig.COMMON_SPEC, "gpuaccel-voxel.toml");

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static synchronized void loadNatives() {
        if (nativesLoaded) return;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            boolean isWindows = os.contains("win");
            boolean isLinux = os.contains("nux") || os.contains("linux");

            String resourcePath;
            if (isWindows) resourcePath = "/windows/x64/org/lwjgl/lwjgl.dll";
            else if (isLinux) resourcePath = "/linux/x64/org/lwjgl/liblwjgl.so";
            else return;

            if (!arch.contains("64")) return;

            InputStream is = GPUEntityAccelMod.class.getResourceAsStream(resourcePath);
            if (is == null) return;

            File tempDir = new File(System.getProperty("java.io.tmpdir"), "gpuaccel_natives");
            if (!tempDir.exists()) tempDir.mkdirs();

            String fileName = isWindows ? "lwjgl.dll" : "liblwjgl.so";
            File nativeFile = new File(tempDir, fileName);
            Files.copy(is, nativeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            is.close();

            System.setProperty("org.lwjgl.librarypath", tempDir.getAbsolutePath());
            System.load(nativeFile.getAbsolutePath());
            nativesLoaded = true;
        } catch (Exception ignored) {}
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("初始化 GPU 加速模块...");
        
        try {
            gpuManager = new GPUManager();
            if (gpuManager.isGPUAvailable()) {
                swarmAISystem = new SwarmAISystem(gpuManager);
                physicsSimulation = new PhysicsSimulation(gpuManager);
                climateSystem = new ClimateSystem(gpuManager);
                VoxelManager.init();
                LOGGER.info("GPU 系统就绪: {}", gpuManager.getDeviceName());
            } else {
                LOGGER.warn("未检测到兼容的 GPU，加速功能已禁用。");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize GPU systems.", t);
            gpuManager = null;
        }
    }

    public static GPUManager getGPUManager() { return gpuManager; }
    public static SwarmAISystem getSwarmAISystem() { return swarmAISystem; }
    public static PhysicsSimulation getPhysicsSimulation() { return physicsSimulation; }
    public static ClimateSystem getClimateSystem() { return climateSystem; }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // 注册命令
        ExampleCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (swarmAISystem != null) {
            try {
                swarmAISystem.clearGpuTags(event.getServer());
            } catch (Exception e) {
                LOGGER.warn("Failed to clear tags on stop", e);
            }
            swarmAISystem.cleanup();
        }
        if (physicsSimulation != null) physicsSimulation.cleanup();
        if (climateSystem != null) climateSystem.cleanup();
        if (gpuManager != null) gpuManager.cleanup();
    }
}