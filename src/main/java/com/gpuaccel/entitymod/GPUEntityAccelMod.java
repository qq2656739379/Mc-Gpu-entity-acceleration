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

/**
 * GPU 加速模组主类。
 * <p>
 * 负责初始化模组组件、加载本地库 (OpenCL)、注册配置和事件监听器。
 * </p>
 */
@Mod(GPUEntityAccelMod.MOD_ID)
public class GPUEntityAccelMod {
    /** 模组 ID */
    public static final String MOD_ID = "gpuaccel";
    /** 日志记录器 */
    public static final Logger LOGGER = LogUtils.getLogger();

    private static GPUManager gpuManager;
    private static SwarmAISystem swarmAISystem;
    private static PhysicsSimulation physicsSimulation;
    private static ClimateSystem climateSystem;
    private static boolean nativesLoaded = false;

    /**
     * 构造函数：执行早期的初始化工作。
     */
    public GPUEntityAccelMod() {
        // 加载 OpenCL 本地库
        loadNatives();
        
        // 注册配置文件
        // 显式指定文件名以避免配置冲突
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, GPUAccelConfig.SPEC, "gpuaccel-general.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SwarmConfig.COMMON_SPEC, "gpuaccel-swarm.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VoxelConfig.COMMON_SPEC, "gpuaccel-voxel.toml");

        // 注册生命周期事件监听器
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);

        // 注册 Forge 事件总线
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 动态加载 LWJGL 本地库 (OpenCL 支持)。
     * 包含对 Windows 和 Linux 系统的支持。
     */
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

            // 仅支持 64 位架构
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

    /**
     * 通用设置阶段：初始化 GPU 系统和相关子系统。
     *
     * @param event FML 通用设置事件
     */
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
            LOGGER.error("无法初始化 GPU 系统。", t);
            gpuManager = null;
        }
    }

    /** @return 全局 GPU 管理器实例 */
    public static GPUManager getGPUManager() { return gpuManager; }

    /** @return 群体智能 AI 系统实例 */
    public static SwarmAISystem getSwarmAISystem() { return swarmAISystem; }

    /** @return 物理模拟系统实例 */
    public static PhysicsSimulation getPhysicsSimulation() { return physicsSimulation; }

    /** @return 气候系统实例 */
    public static ClimateSystem getClimateSystem() { return climateSystem; }

    /**
     * 注册服务器命令。
     *
     * @param event 注册命令事件
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ExampleCommands.register(event.getDispatcher());
    }

    /**
     * 服务器停止时清理资源。
     * 释放 GPU 内存和 OpenCL 上下文。
     *
     * @param event 服务器停止事件
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (swarmAISystem != null) {
            try {
                swarmAISystem.clearGpuTags(event.getServer());
            } catch (Exception e) {
                LOGGER.warn("停止时清理标签失败", e);
            }
            swarmAISystem.cleanup();
        }
        if (physicsSimulation != null) physicsSimulation.cleanup();
        if (climateSystem != null) climateSystem.cleanup();
        if (gpuManager != null) gpuManager.cleanup();
    }
}
