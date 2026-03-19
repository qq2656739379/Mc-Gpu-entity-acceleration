package com.gpuaccel.entitymod;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;

import com.gpuaccel.entitymod.ai.FlowFieldSystem;
import com.gpuaccel.entitymod.ai.VoxelManager;
import com.gpuaccel.entitymod.command.AlgorithmCommand;
import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.gpuaccel.entitymod.config.VoxelConfig;
import com.gpuaccel.entitymod.example.ExampleCommands;
import com.gpuaccel.entitymod.gpu.GPUManager;
import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * GPU 加速模组主类。
 * <p>
 * 核心职责：初始化 GPU 管理器和流场导航系统。
 * 本模组专注于利用 OpenCL 提供高性能的 3D 流场寻路加速，
 * 并在底层通过 Mixin 优化实体高频逻辑与 NBT 序列化，
 * 完美兼容原版 Goal/Brain 系统与其他主流 Mod 的 AI 决策。
 * </p>
 */
@Mod(GPUEntityAccelMod.MOD_ID)
public class GPUEntityAccelMod {
    public static final String MOD_ID = "gpuaccel";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static GPUManager gpuManager;
    private static FlowFieldSystem flowFieldSystem;
    private static boolean nativesLoaded = false;

    public GPUEntityAccelMod(FMLJavaModLoadingContext context) {
        loadNatives();

        context.registerConfig(ModConfig.Type.COMMON, GPUAccelConfig.SPEC, "gpuaccel-general.toml");
        context.registerConfig(ModConfig.Type.COMMON, VoxelConfig.COMMON_SPEC, "gpuaccel-voxel.toml");

        context.getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static synchronized void loadNatives() {
        if (nativesLoaded)
            return;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String arch = System.getProperty("os.arch", "").toLowerCase();
            boolean isWindows = os.contains("win");
            boolean isLinux = os.contains("nux") || os.contains("linux");

            String resourcePath;
            if (isWindows)
                resourcePath = "/windows/x64/org/lwjgl/lwjgl.dll";
            else if (isLinux)
                resourcePath = "/linux/x64/org/lwjgl/liblwjgl.so";
            else
                return;

            if (!arch.contains("64"))
                return;

            InputStream is = GPUEntityAccelMod.class.getResourceAsStream(resourcePath);
            if (is == null)
                return;

            File tempDir = new File(System.getProperty("java.io.tmpdir"), "gpuaccel_natives");
            if (!tempDir.exists())
                tempDir.mkdirs();

            String fileName = isWindows ? "lwjgl.dll" : "liblwjgl.so";
            File nativeFile = new File(tempDir, fileName);
            Files.copy(is, nativeFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            is.close();

            System.setProperty("org.lwjgl.librarypath", tempDir.getAbsolutePath());
            System.load(nativeFile.getAbsolutePath());
            nativesLoaded = true;
        } catch (Exception ignored) {
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("初始化 GPU 流场加速模块...");

        try {
            gpuManager = new GPUManager();
            if (gpuManager.isGPUAvailable()) {
                flowFieldSystem = new FlowFieldSystem(gpuManager);
                VoxelManager.init();
                LOGGER.info("GPU 流场系统就绪: {}", gpuManager.getDeviceName());
            } else {
                LOGGER.warn("未检测到兼容的 GPU，流场加速已禁用。回退到原版 A* 寻路。");
            }

            // 缓存统计定期报告已禁用
        } catch (Throwable t) {
            LOGGER.error("无法初始化 GPU 系统。", t);
            gpuManager = null;
        }
    }

    public static GPUManager getGPUManager() {
        return gpuManager;
    }

    public static FlowFieldSystem getFlowFieldSystem() {
        return flowFieldSystem;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ExampleCommands.register(event.getDispatcher());
        AlgorithmCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (flowFieldSystem != null)
            flowFieldSystem.cleanup();
        if (gpuManager != null)
            gpuManager.cleanup();
    }
}
