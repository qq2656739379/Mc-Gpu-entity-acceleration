# GPU Entity Acceleration Mod

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Forge](https://img.shields.io/badge/Forge-1.20.1--47.4.0-orange.svg)](https://files.minecraftforge.net/)
[![Java](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://adoptium.net/)

一个面向 Minecraft Forge 1.20.1 的**服务端性能优化模组**。通过10 项 Mixin 注入优化。

---

## 目录

1. [性能成果](#性能成果)
2. [核心功能](#核心功能)
3. [Mixin 优化清单](#mixin-优化清单)
4. [算法原理](#算法原理)
5. [安装](#安装)
6. [OpenCL 驱动安装 (Linux)](#opencl-驱动安装-linux)
7. [构建](#构建)
8. [兼容性](#兼容性)
9. [已知问题](#已知问题)
10. [项目结构](#项目结构)

---

## 性能成果

在 Intel i5-14600K + NVIDIA CMP 30HX 服务器上，加载 100+ 模组（TFC 3.2.19、Create 等）的实测数据：

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| **TPS (1 玩家)** | 6.00 | **20.00** |
| **MSPT (中位)** | >160 ms | **32.6 ms** |
| **空闲占比** | 0% | **22.4%** |
| **TPS (3 玩家)** | — | ~14.5–15.5 |

### 各热点优化幅度

| 热点 | 原始占比 | 优化后 | 手段 |
|------|----------|--------|------|
| `Brain.tick` / AI 调度 | 39% | ~5% | GPU 流场 + Brain 行为节流 |
| `addAdditionalSaveData` | 21% | ~0.9% | SaveData 100-tick 节流缓存 |
| `ServerFunctionManager` (`@e[nbt=...]`) | 28.92% | 9.13% | saveWithoutId 分层去重缓存 |
| `CompoundTag.copy()` | 3.27% | 0% | 零拷贝直引用 |
| `getIndirectPassengersStream` | 5.89% | ~0% | 无乘客短路返回 |
| TFC `Support.isSupported()` | 13.94% | ~0.7% | 直接映射结果缓存 + 近邻快速路径 |
| TFC Calendar 同步 | ~2% | ~0% | 反射修复 + 漂移阈值过滤 |

---

## 核心功能

### 1. GPU 流场寻路 (FlowFieldSystem)

利用 OpenCL 在 GPU 上执行 BFS 波前传播，为家畜群生成共享流场。主线程零阻塞：

- **异步回读**：使用 `CL_FALSE` 非阻塞读取 + `cl_event` 轮询，彻底消除 32MB 同步读取卡顿
- **增量更新**：每 20 tick 检查目标移动，仅当曼哈顿距离 ≥ 8 时重算
- **分帧 BFS**：每帧 8 次迭代，64 次迭代完成全场，避免单帧尖峰
- **AABB 空间查询**：替代全实体遍历，仅查询 64 格范围内的家畜

### 2. Mixin 服务端热点优化

通过 10 项 Mixin 注入，精准消除 Spark 性能分析中发现的 CPU 热点。详见下方清单。

### 3. TFC 深度集成

针对 TerraFirmaCraft 的两项专有优化（可选加载，TFC 不存在时自动跳过）：
- 日历同步修复（消除每 tick 的反射开销和日志刷屏）
- 方块支撑检查缓存（消除 243 方块暴力扫描）

---

## Mixin 优化清单

### 核心 Mixin (`gpuaccel.mixins.json`) — 8 项

| Mixin | 目标 | 原理 |
|-------|------|------|
| **MixinSaveDataThrottle** | `Entity.saveWithoutId()` → `addAdditionalSaveData()` | `@Redirect` 拦截序列化调用，100 tick 内返回缓存的 NBT 快照。排除 Player 和特定模组实体（Create、车万女仆）。 |
| **MixinSaveWithoutIdDedup** | `Entity.saveWithoutId()` | `@Inject` HEAD 实现分层去重：Create 实体 100-tick 窗口，其余同 tick 去重。直接引用缓存，无 `copy()` 开销。 |
| **MixinEntityPassengerShortCircuit** | `Entity.getIndirectPassengers()` | `@Inject` HEAD 检查 `passengers` 字段，为空时直接返回 `Collections.emptyList()`，跳过递归遍历。 |
| **MixinBrainBehaviorThrottle** | `Brain.tick()` | 降低非关键 Brain 行为的 tick 频率，减少 AI 决策开销。 |
| **MixinCompoundTagPreAlloc** | `CompoundTag` 构造 | 预分配 Tag Map 容量，减少 HashMap 扩容。 |
| **MixinEntityGpuState** | `Entity` | 为实体附加 GPU 状态标记，标识是否参与 GPU 计算。 |
| **MixinMob** | `Mob` | 集成 GPU 流场导航，将寻路决策委托给 FlowFieldSystem。 |
| **MixinMobNavigation** | `MobNavigation` | 配合 MixinMob，跳过原版 A* 寻路，改用流场向量。 |

### TFC Mixin (`gpuaccel.tfc.mixins.json`) — 2 项（可选）

| Mixin | 目标 | 原理 |
|-------|------|------|
| **MixinTFCCalendarSync** | TFC `ServerCalendar.onServerTick()` | 反射读取 `calendarTicks` / `doDaylightCycle` / `arePlayersLoggedOn`，修复日历同步逻辑。漂移阈值 \|delta\| ≥ 3，过滤启动时无害的 ±1–2 tick 抖动。 |
| **MixinTFCSupportFastPath** | TFC `Support.isSupported()` | 8192 槽直接映射缓存（1 秒 TTL）+ 6-近邻快速路径。HEAD 注入检查缓存→近邻，RETURN 注入缓存全扫描结果。消除每 tick 243 方块范围暴力遍历。 |

> **关于 refMap**：运行时 Mixin 未加载 refMap，所有对 Minecraft / TFC 类的字段访问均使用反射 + MCP→SRG 双名称回退（如 `saveWithoutId` / `m_20240_`）。

---

## 算法原理

### 流场寻路 (Flow Field Pathfinding)

使用 GPU BFS 波前传播生成流场，所有同类实体共享同一张向量场：

1. **代价场生成**：从目标点开始 BFS，每个体素记录到达代价
   $$ Cost(n) = \min_{m \in neighbors} Cost(m) + StepCost(n) $$

2. **向量场生成**：计算代价场梯度下降方向
   $$ \vec{V}(x) = - \nabla Cost(x) $$

### 群体智能 (Boids)

基于 Reynolds Boids 算法的三力模型 + 目标吸引力：

$$ \vec{a}_i = \frac{W_s \vec{F}_{sep} + W_a \vec{F}_{align} + W_c \vec{F}_{coh} + W_t \vec{F}_{target}}{m} $$

- **分离** $\vec{F}_{sep} = \sum_{j} \frac{\vec{p}_i - \vec{p}_j}{\|\vec{p}_i - \vec{p}_j\|^2}$
- **对齐** $\vec{F}_{align} = \bar{\vec{v}}_{neighbors} - \vec{v}_i$
- **凝聚** $\vec{F}_{coh} = \bar{\vec{p}}_{neighbors} - \vec{p}_i$

### 费洛蒙扩散 (Reaction-Diffusion)

8 通道 3D 网格模拟气味扩散（拉普拉斯卷积）：

$$ C_{new}(x) = C_{old}(x) + (\bar{C}_{neighbors} - C_{old}(x)) \times R_{diff} \times \Delta t $$

---

## 安装

### 环境要求

| 项目 | 要求 |
|------|------|
| Minecraft | 1.20.1 |
| Forge | 47.4.0+ |
| Java | 17+（推荐 21） |
| GPU | 支持 OpenCL 1.2+ 的 NVIDIA / AMD / Intel 显卡 |

### 安装步骤

1. 从 [Releases](https://github.com/qq2656739379/Mc-Gpu-entity-acceleration/releases) 下载 `gpu-entity-acceleration-1.0.0.jar`
2. 放入服务端 `mods/` 目录
3. 确保系统已安装 OpenCL 驱动（Windows 通常随显卡驱动自带；Linux 见下方指南）
4. 启动服务器

> 本模组为**纯服务端**模组，客户端无需安装。

### 推荐服务端配置 (`server.properties`)

```properties
view-distance=8
simulation-distance=4
entity-broadcast-range-percentage=64
```

---

## OpenCL 驱动安装 (Linux)

本模组依赖 OpenCL 1.2+。Windows 用户通常无需额外操作。

### 检测当前状态
```bash
sudo apt update && sudo apt install clinfo
clinfo
```
若输出 `Number of platforms: 0`，按以下步骤安装。

### NVIDIA
```bash
sudo apt install nvidia-driver-535 ocl-icd-libopencl1
# 重启系统
```

### AMD
```bash
# 方案 A: 开源驱动 (Mesa)
sudo apt install mesa-opencl-icd

# 方案 B: 官方驱动 (AMDGPU-PRO)
# 从 https://www.amd.com/en/support 下载安装脚本
./amdgpu-install --usecase=opencl --no-3d
```

### Intel
```bash
sudo apt install intel-opencl-icd
```

---

## 构建

```bash
# Linux / macOS
chmod +x gradlew
./gradlew build --no-daemon

# Windows
gradlew.bat build --no-daemon
```

构建产物位于 `build/libs/` 目录。

### 开发依赖

- Gradle 8.14.3（项目自带 wrapper）
- JDK 17+
- JOCL 2.0.6、LWJGL 3.3.1（由 Gradle 自动下载）

---

## 兼容性

### TerraFirmaCraft (TFC)
- 深度集成：日历同步修复、方块支撑检查缓存
- TFC Mixin 为可选加载（`required: false`），TFC 不存在时自动跳过

### Touhou Little Maid (车万女仆)
- 自动排除：女仆实体不参与 GPU 计算，不影响交互逻辑
- SaveData 节流自动跳过 `touhou_little_maid` 命名空间

### Create
- SaveData 节流自动跳过 `create` 命名空间
- saveWithoutId 去重给予 Create 实体 100-tick 宽松窗口

### 其他模组
- 未注册的模组生物默认由 CPU 处理
- 一般不会产生冲突，本模组仅优化原版和 TFC 的特定方法

---

## 已知问题

| 问题 | 说明 |
|------|------|
| `No refMap loaded` 警告 | Mixin 未加载 refMap，不影响功能。所有字段访问已改用反射 + SRG 双名称回退。 |
| TFC RockData NPE | TFC 3.2.19 已知 bug（区块生成时 `surfaceHeight` NPE），非本模组问题。 |
| 3 玩家 TPS ~14.5 | 多玩家下仍有优化空间，主要瓶颈为 TFC 系统和 datapack 函数。 |

---

## 项目结构

```
src/main/java/com/gpuaccel/entitymod/
├── GPUEntityAccelMod.java          # Forge 模组入口
├── GPUManager.java                 # OpenCL 设备管理 & Buffer 操作
├── FlowFieldSystem.java            # GPU 流场 BFS + 异步回读
├── VoxelManager.java               # 体素化世界数据
├── EntityTickHandler.java           # 实体 tick 调度
├── config/                          # Forge 配置
├── mixin/                           # 10 项 Mixin 注入
│   ├── MixinSaveDataThrottle.java
│   ├── MixinSaveWithoutIdDedup.java
│   ├── MixinEntityPassengerShortCircuit.java
│   ├── MixinBrainBehaviorThrottle.java
│   ├── MixinCompoundTagPreAlloc.java
│   ├── MixinEntityGpuState.java
│   ├── MixinMob.java
│   ├── MixinMobNavigation.java
│   ├── MixinTFCCalendarSync.java
│   └── MixinTFCSupportFastPath.java
└── util/                            # 性能分析工具
```

---

## 许可证

[MIT License](LICENSE)
