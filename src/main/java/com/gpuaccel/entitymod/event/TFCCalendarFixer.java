package com.gpuaccel.entitymod.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;

/**
 * TFC 日历同步修复 v5。
 * <p>
 * calendarTicks 和 dayTime 的 %24000 值本来就不相等（相差约 18000），
 * 这是 TFC 的正常设计。真正的问题是在运行过程中两者的增量不同步，
 * 导致 6 tick 漂移（TFC 阈值 = 5）。
 * </p>
 * <p>
 * 策略：在 ServerTickEvent.START 记录上一 tick 的两个值，
 * 比较本 tick 的增量差异，如果有微小偏移（≤40 tick）则修正
 * calendarTicks 使增量保持一致。
 * </p>
 */
@Mod.EventBusSubscriber(modid = "gpuaccel")
public class TFCCalendarFixer {

    private static final Logger LOGGER = LogManager.getLogger("GPUAccel-TFCCalendarFix");

    private static boolean initialized = false;
    private static boolean available = false;
    private static Object serverCalendar;
    private static Field calendarTicksField;

    // 记录上一 tick 的值
    private static long prevDayTime = Long.MIN_VALUE;
    private static long prevCalendarTicks = Long.MIN_VALUE;

    private static int debugCount = 0;

    private static void initReflection() {
        if (initialized)
            return;
        initialized = true;
        try {
            Class<?> calendarsClass = Class.forName("net.dries007.tfc.util.calendar.Calendars");
            Field serverField = calendarsClass.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            serverCalendar = serverField.get(null);
            if (serverCalendar == null) {
                LOGGER.warn("[TFC日历] Calendars.SERVER = null");
                return;
            }
            Class<?> calendarClass = serverCalendar.getClass().getSuperclass();
            calendarTicksField = calendarClass.getDeclaredField("calendarTicks");
            calendarTicksField.setAccessible(true);
            available = true;
            LOGGER.info("[TFC日历] 初始化成功: {}", serverCalendar.getClass().getName());
        } catch (ClassNotFoundException e) {
            LOGGER.info("[TFC日历] TFC 未安装，跳过。");
        } catch (Exception e) {
            LOGGER.warn("[TFC日历] 初始化失败: {}", e.toString());
        }
    }

    /**
     * 通过比较增量修正漂移。
     * <p>
     * 如果上一 tick dayTime 增加了 N，calendarTicks 也应该增加 N。
     * 如果差值不同（偏移了），修正 calendarTicks 消除偏移。
     * </p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerTickStart(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START)
            return;

        initReflection();
        if (!available)
            return;

        try {
            ServerLevel overworld = event.getServer().overworld();
            if (overworld == null)
                return;

            long curDayTime = overworld.getDayTime();
            long curCalTicks = calendarTicksField.getLong(serverCalendar);

            // 第一次运行：只记录，不修正
            if (prevDayTime == Long.MIN_VALUE) {
                prevDayTime = curDayTime;
                prevCalendarTicks = curCalTicks;
                LOGGER.info("[TFC日历] 首次采样: dayTime={} calTicks={}", curDayTime, curCalTicks);
                return;
            }

            // 计算各自的增量
            long dayTimeDelta = curDayTime - prevDayTime;
            long calTicksDelta = curCalTicks - prevCalendarTicks;

            // 增量差 = 漂移量（应该为 0，如果 > 0 说明 dayTime 多走了）
            long drift = dayTimeDelta - calTicksDelta;

            // 诊断日志（前 30 次）
            if (debugCount < 30 && (drift != 0 || debugCount < 10)) {
                LOGGER.info("[TFC日历] dtDelta={} calDelta={} drift={} dt={} cal={}",
                        dayTimeDelta, calTicksDelta, drift, curDayTime, curCalTicks);
                debugCount++;
            }

            // 仅修正小漂移（≤40 tick），忽略大跳跃（睡觉/时间设置）
            if (drift != 0 && drift >= -40 && drift <= 40) {
                calendarTicksField.setLong(serverCalendar, curCalTicks + drift);
                if (debugCount < 50) {
                    LOGGER.info("[TFC日历] 修正漂移! drift={} calTicks {} → {}",
                            drift, curCalTicks, curCalTicks + drift);
                    debugCount++;
                }
                // 更新记录为修正后的值
                prevCalendarTicks = curCalTicks + drift;
            } else {
                prevCalendarTicks = curCalTicks;
            }
            prevDayTime = curDayTime;

        } catch (Exception e) {
            if (debugCount < 5) {
                LOGGER.warn("[TFC日历] 出错: {}", e.toString());
                debugCount++;
            }
        }
    }
}
