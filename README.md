# GPU Entity Acceleration Mod

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Forge](https://img.shields.io/badge/Forge-1.20.1-orange.svg)](https://files.minecraftforge.net/)

一个专为 Minecraft Forge 1.20.1 设计的高性能服务端 Mod。它利用 OpenCL 技术，将繁重的生物 AI 和物理计算卸载到 GPU 上并行处理，从而在不降低服务器 TPS 的情况下支持更大规模的生物数量。

## ✨ 核心功能

### 🚀 GPU 并行加速 (OpenCL)
- **高性能计算**：利用显卡的并行处理能力，处理数千个实体的行为逻辑。
- **智能回退**：自动检测 OpenCL 环境，若 GPU 不可用则平滑切换回 CPU 计算，确保稳定性。

### 🧠 群体智能系统 (Swarm AI)
- **Boids 算法实现**：完美复刻经典的鸟群/鱼群行为（分离、对齐、聚合）。
- **优化飞行生物**：专为蝙蝠、蜜蜂等飞行生物设计，模拟自然的群聚飞舞效果。
- **高效处理**：在 GPU 上同时计算数千个实体的相互作用力，几乎不占用主线程时间。

### 🗺️ 体素地形感知 (Voxel System)
- **智能避障**：基于体素化地图的快速碰撞检测，实体能感知地形并自动规避。
- **栅栏识别**：通过“高方块”识别逻辑，防止生物错误地跳过栅栏或围墙。
- **零卡顿更新**：采用分时切片扫描技术 (Incremental Update)，地形数据更新对服务器性能影响微乎其微。

### 🌍 动态气候系统 (Climate System)
- **GPU 温度平滑**：利用 GPU 异步计算区块温度分布，实现更自然的热量扩散模拟（可用于生存模组扩展）。

### ⚛️ 物理模拟引擎
- **实体物理**：接管实体的重力、空气阻力、地面摩擦计算。
- **碰撞响应**：基于 GPU 的快速碰撞反馈，支持自定义实体弹力系数。

## 💻 系统要求

| 组件 | 最低要求 | 推荐配置 |
|------|----------|----------|
| **Minecraft** | 1.20.1 | 1.20.1 |
| **Forge** | 47.4.0+ | 最新稳定版 |
| **Java** | Java 17 | Java 17 |
| **GPU** | 支持 OpenCL 1.2 的显卡 | NVIDIA GTX 1060 / AMD RX 580 或更高 |
| **驱动** | 最新显卡驱动 | 包含 OpenCL 支持的驱动 |

> **注意**：本模组为服务端 Mod，客户端无需安装即可进入服务器。

## 📥 安装指南

1. **环境准备**：
   - 确保服务器已安装 **Forge 1.20.1**。
   - 确保服务器显卡驱动已更新，并支持 OpenCL。
     - **NVIDIA**: 安装 CUDA Toolkit 或标准驱动。
     - **AMD**: 安装标准驱动即可。
     - **Linux 用户**: 可能需要安装 `clinfo` 和对应的 OpenCL 运行时 (如 `nvidia-opencl-icd`).

2. **安装 Mod**：
   - 将 Mod JAR 文件放入服务器的 `mods` 文件夹。
   - 启动服务器。

3. **验证安装**：
   - 查看控制台日志，寻找类似 `[GPU Manager] GPU Device: NVIDIA GeForce RTX 3060` 的信息。
   - 在游戏内使用 `/gpuaccel info` 命令检查状态。

## ⚙️ 配置详解

本模组配置文件位于 `config/` 目录下（注意：从旧版的 `serverconfig` 移至 `config` 以支持更通用的配置）。

### 1. 全局配置 (`gpuaccel-general.toml`)
控制 GPU 加速的核心开关和算法选择。

```toml
[GPU Settings]
  # 启用 GPU 加速 (必须有支持 OpenCL 的设备)
  enableGPU = true
  # 触发 GPU 加速的最小实体数 (少于此数量使用 CPU 以减少开销)
  minEntitiesForGPU = 10

[Algorithm Selection]
  # 启用 GPU 群体 AI (Swarm AI)
  enableSwarmAIGPU = true
  # 启用 GPU 物理模拟 (默认关闭，按需开启)
  enablePhysicsGPU = false
  # 激进模式：处理所有非玩家生物 (包括模组生物)，可能增加负载
  aggressiveMode = false

[Performance Settings]
  # 更新间隔 (Tick)。1=每tick更新。
  # 调高此值可大幅提升性能，但生物反应会变慢。
  updateInterval = 1
```

### 2. 群体 AI 配置 (`gpuaccel-swarm.toml`)
调整生物群聚的行为参数。

```toml
[Swarm Settings]
  # 吸引力：生物向目标点移动的力度
  "Attraction Force" = 0.05
  # 悬停频率：飞行生物原地悬停的抖动频率
  "Hover Frequency" = 2.0
  # ...其他参数可调整聚集程度和分散度
```

### 3. 体素系统配置 (`gpuaccel-voxel.toml`)
调整地形感知的精度和性能。

```toml
[voxel_system]
  # 扫描半径 (单位：块)，影响生物能感知的地形范围
  scanRadius = 32
  # 地形数据重扫描间隔 (Tick)
  updateInterval = 20
```

## 🎮 命令使用

需要 OP 权限 (Level 2+)。

### 基础命令
- `/gpuaccel info`
  - 显示当前 GPU 设备信息、显存使用情况和计算单元数量。
  - 用于验证 OpenCL 是否正常工作。

- `/gpuaccel spawn_swarm`
  - 在玩家周围生成 50 只蝙蝠，用于测试群体 AI 效果。

### 高级控制 (需启用 `AlgorithmCommand`)
*注：以下命令在部分版本中可用，用于实时调试。*
- `/gpualgo global <true|false>`: 实时切换 GPU 加速总开关。
- `/gpualgo swarm <true|false>`: 实时切换群体 AI 模块。
- `/gpualgo status`: 查看当前各模块开启状态。

## 🔧 故障排除

### ❓ 服务器启动时提示 "No OpenCL platforms found"
- **原因**：系统未安装 OpenCL 驱动，或驱动不完整。
- **解决**：
  - Windows: 更新显卡驱动。
  - Linux: 运行 `clinfo` 检查，安装 `ocl-icd-opencl-dev` 或对应显卡厂商的 OpenCL 包。

### ❓ 生物行为卡顿或瞬移
- **原因**：GPU 负载过高或数据传输延迟。
- **解决**：
  - 在 `gpuaccel-general.toml` 中将 `updateInterval` 调大 (例如 2 或 3)。
  - 增大 `minEntitiesForGPU` 阈值。

### ❓ "Config conflict detected" 崩溃
- **原因**：旧版配置文件冲突。
- **解决**：删除 `config/` 目录下的相关 `.toml` 文件，让 Mod 重新生成。

## 🛠️ 开发者指南

### 构建项目
```bash
./gradlew build
```

### 接入 API
其他模组可以通过获取 `GPUManager` 实例来使用 OpenCL 加速：

```java
import com.gpuaccel.entitymod.GPUEntityAccelMod;

// 获取 GPU 管理器
var gpuManager = GPUEntityAccelMod.getGPUManager();
if (gpuManager.isGPUAvailable()) {
    // 你的 OpenCL 代码...
}
```

## 📄 许可证

本项目采用 [MIT License](LICENSE) 开源。
