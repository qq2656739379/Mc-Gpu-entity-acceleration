package com.gpuaccel.entitymod.mixin;

import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Shadow;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 TFC 日历持续失同步 (Sync = 6) 导致的 CPU 浪费。
 * <p>
 * 问题：TFC {@code ServerCalendar.onOverworldTick()} 的
 * {@code TIME_DESYNC_THRESHOLD = 5}，但服务器日历偏差恰好为 6，
 * 导致每 tick 都触发"修复"尝试但永远无法成功。
 * 连锁反应：所有 {@code BarrelBlockEntity} 每 tick 执行
 * {@code onCalendarUpdate} → {@code updateRecipe} → 全配方匹配（~9% CPU）。
 * </p>
 * <p>
 * 修复：在 TFC 的 desync 检测之前，强制将 {@code calendarTicks}
 * 对齐到 {@code level.getDayTime()}，使偏差不超过阈值。
 * </p>
 * <p>
 * 注意：{@code ServerCalendar} 是 TFC 模组类，字段名不经过 SRG 混淆。
 * 但因运行时 "No refMap loaded"，{@code @Shadow} 无法定位父类
 * {@code Calendar} 中的字段，改用反射访问。
 * </p>
 */
@Mixin(targets = "net.dries007.tfc.util.calendar.ServerCalendar", remap = false)
public abstract class MixinTFCCalendarSync {

    @Unique
    private static final Logger gpuAccel$LOGGER = LogManager.getLogger("GPUAccel-TFCCalendarFix");

    @Unique
    private static boolean gpuAccel$logged = false;

    @Shadow(remap = false)
    protected long calendarTicks;

    @Shadow(remap = false)
    protected boolean doDaylightCycle;

    @Shadow(remap = false)
    protected boolean arePlayersLoggedOn;

    /**
     * 在 onOverworldTick 执行之前，强制对齐 calendarTicks。
     * <p>
     * TFC 的原始逻辑：
     * 
     * <pre>
     * long deltaWorldTime = (level.getDayTime() % 24000) - getCalendarDayTime();
     * if (deltaWorldTime > 5 || deltaWorldTime < -5) { // TIME_DESYNC_THRESHOLD = 5
     *     // 尝试修复 + 发送同步包 + 打印 WARN
     * }
     * </pre>
     * 
     * 我们在此之前将 calendarTicks 微调，使 delta 始终在阈值内。
     * </p>
     */
    @Inject(method = "onOverworldTick", at = @At("HEAD"), require = 0, remap = false)
    private void gpuAccel$forceCalendarSyncServerLevel(ServerLevel level, CallbackInfo ci) {
        gpuAccel$doSync(level);
    }

    @Inject(method = "onOverworldTick", at = @At("HEAD"), require = 0, remap = false)
    private void gpuAccel$forceCalendarSyncLevel(net.minecraft.world.level.Level level, CallbackInfo ci) {
        gpuAccel$doSync(level);
    }

    @Unique
    private void gpuAccel$doSync(net.minecraft.world.level.Level level) {
        try {
            if (!this.doDaylightCycle || !this.arePlayersLoggedOn)
                return;

            long currentCalendarTicks = this.calendarTicks;

            // 计算当前 TFC 日历的 "日内时间" (0 ~ 23999)
            long calendarDayTime = currentCalendarTicks % 24000L;
            if (calendarDayTime < 0)
                calendarDayTime += 24000L;

            long worldDayTime = level.getDayTime() % 24000L;
            if (worldDayTime < 0)
                worldDayTime += 24000L;

            long delta = worldDayTime - calendarDayTime;

            // 处理环绕
            if (delta > 12000L)
                delta -= 24000L;
            if (delta < -12000L)
                delta += 24000L;

            // 仅当偏差 ≥3 且在合理范围内时修正
            // TFC 阈值 = 5，±1~2 tick 的偏差是正常的 tick 延迟，无需处理
            long absDelta = delta < 0 ? -delta : delta;
            if (absDelta >= 3 && absDelta <= 2000) {
                this.calendarTicks = currentCalendarTicks + delta;
                if (!gpuAccel$logged) {
                    gpuAccel$LOGGER.info("TFC 日历修复已生效，消除 {} tick 偏差", delta);
                    gpuAccel$logged = true;
                }
            }
        } catch (Exception e) {
            gpuAccel$LOGGER.error("TFC Calendar 同步逻辑异常", e);
        }
    }
}
