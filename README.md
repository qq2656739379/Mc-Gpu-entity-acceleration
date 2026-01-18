# GPU Entity Acceleration Mod

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Forge](https://img.shields.io/badge/Forge-1.20.1-orange.svg)](https://files.minecraftforge.net/)

本模组利用 OpenCL 技术将 Minecraft 实体的 AI 决策与物理计算卸载到 GPU 上并行处理。通过大规模并行计算，我们能在保持高 TPS 的同时支持数千个实体，并引入了基于费洛蒙的群体智能和流场寻路算法。

---

## 📚 目录

1. [驱动安装指南 (Linux)](#-驱动安装指南-linux)
2. [算法原理与数学公式](#-算法原理与数学公式)
3. [如何修改生物算法 (配置)](#-如何修改生物算法-配置)
4. [支持的生物列表](#-支持的生物列表)
5. [兼容性说明](#-兼容性说明)
6. [安装与构建](#-安装与构建)

---

## 📥 驱动安装指南 (Linux)

本模组依赖 OpenCL 1.2+。在 Linux 服务器（如 Ubuntu/Debian）上，您需要安装对应的 OpenCL 运行时 (ICD)。

### 1. 检测当前状态
首先安装 `clinfo` 工具来检查系统是否已正确识别 OpenCL 设备：
```bash
sudo apt update
sudo apt install clinfo
clinfo
```
如果输出显示 `Number of platforms: 0`，请按照下方对应厂商安装驱动。

### 2. NVIDIA 显卡
对于 NVIDIA 显卡，OpenCL 包含在专有驱动中。

```bash
# 添加显卡驱动 PPA (可选，推荐 Ubuntu 用户)
sudo add-apt-repository ppa:graphics-drivers/ppa
sudo apt update

# 安装驱动 (以 535 版本为例，请根据显卡型号选择)
sudo apt install nvidia-driver-535

# 某些发行版可能需要额外安装 OpenCL ICD 加载器
sudo apt install ocl-icd-libopencl1
```
*安装完成后重启系统。*

### 3. AMD 显卡
推荐使用开源 Mesa 驱动（支持大多数现代 AMD 卡），或者安装 AMD 官方专有驱动 (AMDGPU-PRO)。

**方案 A: 开源驱动 (Mesa Clover/Rusticl)**
适用于大多数场景，安装简单。
```bash
sudo apt install mesa-opencl-icd
```

**方案 B: 官方专有驱动 (AMDGPU-PRO)**
前往 [AMD 官网](https://www.amd.com/en/support) 下载对应发行版的安装脚本。
```bash
# 解压并进入目录
tar -Jxvf amdgpu-install-*.tar.xz
cd amdgpu-install-*

# 仅安装 OpenCL 部分 (headless 模式适用于无头服务器)
./amdgpu-install --usecase=opencl --no-3d
```

### 4. Intel 核显/显卡
Intel 提供了计算运行时 (Compute Runtime/NEO)。

```bash
sudo apt install intel-opencl-icd
```

---

## 🧮 算法原理与数学公式

本模组在 GPU 上实现了多套并行算法。以下是核心逻辑的数学表达。

### 1. 群体智能 (Swarm / Boids)
基于 Reynolds 的 Boids 算法，我们计算三个核心力，并加上目标吸引力。

**公式：**
对于每个实体 $i$，其加速度 $\vec{a}_i$ 为：

$$ \vec{a}_i = \frac{W_s \vec{F}_{sep} + W_a \vec{F}_{align} + W_c \vec{F}_{coh} + W_t \vec{F}_{target}}{m} $$

其中：
- **分离 (Separation) $\vec{F}_{sep}$**: 避免拥挤。
  $$ \vec{F}_{sep} = \sum_{j \in neighbors} \frac{\vec{p}_i - \vec{p}_j}{||\vec{p}_i - \vec{p}_j||^2} $$
- **对齐 (Alignment) $\vec{F}_{align}$**: 与邻居同向飞行。
  $$ \vec{F}_{align} = \left( \frac{1}{N} \sum_{j} \vec{v}_j \right) - \vec{v}_i $$
- **凝聚 (Cohesion) $\vec{F}_{coh}$**: 向邻居中心靠拢。
  $$ \vec{F}_{coh} = \left( \frac{1}{N} \sum_{j} \vec{p}_j \right) - \vec{p}_i $$

### 2. 费洛蒙扩散 (Reaction-Diffusion)
我们使用 8 通道的 3D 网格来模拟气味（如食物、捕食者、同类）。气味在空间中通过拉普拉斯卷积进行扩散。

**公式 (离散化)：**
$$ C_{new}(x) = C_{old}(x) + \left( \bar{C}_{neighbors} - C_{old}(x) \right) \times R_{diff} \times \Delta t $$

其中：
- $C(x)$ 是体素 $x$ 处的费洛蒙浓度。
- $\bar{C}_{neighbors}$ 是 6-邻域的平均浓度。
- $R_{diff}$ 是扩散率 (Diffusion Rate)。

### 3. 流场寻路 (Flow Field Pathfinding)
为了支持地面单位的高效寻路，我们使用波前传播 (Wavefront Propagation) 生成流场。

1.  **生成代价场 (Cost Field):** 从目标点开始进行广度优先搜索 (BFS)。
    $$ Cost(n) = \min_{m \in neighbors} (Cost(m)) + StepCost(n) $$
2.  **生成向量场 (Vector Field):** 计算代价场的梯度下降方向。
    $$ \vec{V}(x) = - \nabla Cost(x) $$

### 4. 物理积分 (Physics Integration)
使用半隐式欧拉法 (Semi-implicit Euler) 保证稳定性。

$$ \vec{v}_{t+1} = \vec{v}_t + (\vec{g} + \frac{\vec{F}_{drag}}{m}) \cdot \Delta t $$
$$ \vec{p}_{t+1} = \vec{p}_t + \vec{v}_{t+1} \cdot \Delta t $$

---

## ⚙️ 如何修改生物算法 (配置)

您可以通过修改配置文件来调整算法权重，从而改变生物的行为模式。所有配置位于 `config/` 目录下。

### 修改群体行为 (`gpuaccel-swarm.toml`)
此文件控制 Boids 算法的各项系数。

| 参数项 | 说明 | 推荐调试方向 |
|--------|------|--------------|
| `separationWeight` | 分离力权重 | 调大此值可让生物群更分散，避免重叠。 |
| `alignmentWeight` | 对齐力权重 | 调大此值可让生物群飞得更整齐（像鸟群）。 |
| `cohesionWeight` | 凝聚力权重 | 调大此值可让生物紧紧抱团。 |
| `maxSpeed` | 最大速度 | 限制生物在 GPU 模式下的飞行速度。 |
| `Attraction Force` | 目标吸引力 | 控制生物飞向目标（如花朵、玩家）的欲望强度。 |

### 修改全局物理与性能 (`gpuaccel-general.toml`)

| 参数项 | 说明 |
|--------|------|
| `enablePhysicsGPU` | 开启 GPU 物理模拟（重力/碰撞）。默认为 `false`。 |
| `updateInterval` | GPU 计算间隔。`1` 为每 Tick 更新。调高可提升 FPS 但会降低动作流畅度。 |
| `aggressiveMode` | 激进模式。开启后会接管所有未知生物的计算（可能导致兼容性问题）。 |

---

## 📝 支持的生物列表

本模组内置了对 **原版 (Vanilla)** 和 **TerraFirmaCraft (TFC:TNG)** 生物的深度支持。这些生物会被自动分配到特定的 AI 行为组中。

### 1. 原版生物 (Vanilla)

| 实体 ID | AI 类型 | 行为逻辑 |
|---------|---------|----------|
| `minecraft:cow` | Livestock (家畜) | 群居，寻找食物气味，被捕食者惊吓。 |
| `minecraft:sheep` | Livestock (家畜) | 群居，寻找食物气味。 |
| `minecraft:pig` | Livestock (家畜) | 群居，寻找食物气味。 |
| `minecraft:chicken` | Livestock (家畜) | 群居，低恐慌阈值。 |
| `minecraft:horse` | Livestock (家畜) | 群居，跑得快。 |
| `minecraft:wolf` | Predator (捕食者) | 追踪肉类气味，攻击猎物。 |
| `minecraft:bear` | Predator (捕食者) | 追踪鱼类气味。 |
| `minecraft:zombie` | Zombie (僵尸) | 追踪玩家气味，无视地形代价。 |
| `minecraft:husk` | Zombie (僵尸) | 同上。 |
| `minecraft:drowned` | Zombie (僵尸) | 同上。 |
| `minecraft:cod` | Fish (鱼类) | 水中群游，3D Boids 行为。 |
| `minecraft:salmon` | Fish (鱼类) | 同上。 |
| `minecraft:squid` | Fish (鱼类) | 同上。 |

### 2. TerraFirmaCraft (TFC) 生物

TFC 生物拥有更复杂的参数（如不同的奔跑速度和更强的感知范围）。

| 类别 | 包含的生物 (部分示例) | 特殊行为 |
|------|----------------------|----------|
| **大型捕食者** | `tfc:bear`, `tfc:polar_bear`, `tfc:grizzly_bear`, `tfc:black_bear`, `tfc:lion`, `tfc:tiger`, `tfc:sabertooth` | 极具攻击性，优先追踪大型猎物和玩家。 |
| **中型捕食者** | `tfc:wolf`, `tfc:direwolf`, `tfc:hyena`, `tfc:cougar`, `tfc:panther` | 结群狩猎，速度快。 |
| **野生猎物** | `tfc:deer`, `tfc:gazelle`, `tfc:wildebeest` | 极高的恐慌敏感度，闻到捕食者气味会立即反向逃跑。 |
| **小型猎物** | `tfc:rabbit`, `tfc:hare`, `tfc:pheasant`, `tfc:quail`, `tfc:turkey` | 寻找灌木/谷物气味，受惊后快速四散。 |
| **家畜** | `tfc:cow`, `tfc:sheep`, `tfc:pig`, `tfc:chicken`, `tfc:goat`, `tfc:yak`, `tfc:camel` | 典型的群居行为，受 TFC 熟悉度系统影响。 |

---

## 🔌 兼容性说明

### ✅ TerraFirmaCraft (TFC)
- **深度集成**：自动读取 TFC 实体的 `Familiarity` (熟悉度) 数据。
- **物理调整**：针对 TFC 生物的体型调整了碰撞箱和质量参数。
- **特化行为**：TFC 捕食者拥有专门的 OpenCL 逻辑分支。

### 🛡️ Touhou Little Maid (车万女仆)
- **自动排除**：为了防止破坏女仆的复杂交互逻辑，模组内置了“安全区”机制。
- **逻辑**：任何位于 `touhou_little_maid` 实体附近的生物，会自动回退到 CPU 运算，确保您可以正常与女仆互动而不受 GPU 物理干扰。

### ⚠️ 其他模组
- **未知生物**：默认情况下，未注册的模组生物将由 CPU 处理。若开启 `aggressiveMode`，它们将被强制接管，可能导致行为异常（如只会发呆或乱跑）。

---

## 🔨 安装与构建

### 客户端/服务端
本模组主要在**服务端**运行核心逻辑。客户端安装可选（用于调试渲染，但非必须）。

### 构建项目
如果您是开发者，可以使用 Gradle 构建：

```bash
# Linux / macOS
./gradlew build

# Windows
gradlew.bat build
```

构建产物位于 `build/libs/` 目录。
