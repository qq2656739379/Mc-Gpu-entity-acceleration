# GPU Entity Acceleration Mod

一个用于 Minecraft Forge 1.20.1 (47.4) 的服务端 Mod，通过 GPU 加速生物相关计算（群体 AI、物理模拟）。

## 功能特性

### 🚀 GPU 加速计算
- 使用 OpenCL 进行 GPU 加速
- 自动检测可用的 GPU 设备
- GPU 不可用时自动回退到 CPU 计算

### 🐦 群体 AI 系统
- 实现经典的 Boids 算法（分离、对齐、聚合）
- 适用于飞行生物（蝙蝠、蜜蜂等）
- 可配置的行为参数
- 大量实体时使用 GPU 并行计算

### ⚙️ 物理模拟系统
- GPU 加速的物理引擎
- 重力、空气阻力、地面摩擦
- 碰撞检测和响应
- 可为自定义实体启用

## 系统要求

- Minecraft 1.20.1
- Forge 47.4.0
- Java 17
- 支持 OpenCL 的 GPU（推荐）或 CPU

## 安装

1. 确保安装了 Forge 47.4.0
2. 将 Mod JAR 文件放入 `mods` 文件夹
3. 启动服务器

## 开发构建

### 环境设置

```bash
# 克隆/创建项目
cd javajiashu

# 生成开发环境
./gradlew genIntellijRuns  # IntelliJ IDEA
# 或
./gradlew genEclipseRuns   # Eclipse

# 构建 Mod
./gradlew build
```

### 项目结构

```
src/main/java/com/gpuaccel/entitymod/
├── GPUEntityAccelMod.java          # Mod 主类
├── gpu/
│   └── GPUManager.java             # GPU 管理和 OpenCL 封装
├── ai/
│   └── SwarmAISystem.java          # 群体 AI 系统
├── physics/
│   └── PhysicsSimulation.java      # 物理模拟系统
├── event/
│   └── EntityTickHandler.java      # 事件处理器
└── config/
    └── GPUAccelConfig.java         # 配置文件
```

## 配置

配置文件位于 `serverconfig/gpuaccel-server.toml`：

```toml
[GPU Settings]
  # 启用 GPU 加速
  enableGPU = true
  # 使用 GPU 的最小实体数量
  minEntitiesForGPU = 10

[Swarm AI Settings]
  # 启用群体 AI
  enableSwarmAI = true
  # 分离半径
  separationRadius = 3.0
  # 对齐半径
  alignmentRadius = 5.0
  # 聚合半径
  cohesionRadius = 7.0
  # 各种力的权重
  separationWeight = 1.5
  alignmentWeight = 1.0
  cohesionWeight = 1.0
  # 最大速度
  maxSpeed = 0.5

[Physics Settings]
  # 启用物理模拟（默认关闭）
  enablePhysics = false
  gravity = 9.8
  airResistance = 0.1
  groundFriction = 2.0
  restitution = 0.5

[Performance Settings]
  # 更新间隔（tick）
  updateInterval = 2
```

## 使用示例

### 为自定义实体启用群体 AI

群体 AI 自动应用于：
- 所有飞行动物（FlyingAnimal）
- 鱼类、蝙蝠、蜜蜂等

### 为实体启用物理模拟

给实体添加标签：
```java
entity.addTag("gpu_physics");
```

### 在代码中使用 GPU 系统

```java
// 获取 GPU 管理器
GPUManager gpuManager = GPUEntityAccelMod.getGPUManager();

// 获取群体 AI 系统
SwarmAISystem swarmAI = GPUEntityAccelMod.getSwarmAISystem();
List<Mob> entities = ...; // 你的实体列表
swarmAI.computeSwarmBehavior(entities);

// 获取物理模拟系统
PhysicsSimulation physics = GPUEntityAccelMod.getPhysicsSimulation();
physics.updatePhysics(entities, deltaTime);
```

## 性能优化建议

1. **GPU 阈值**：调整 `minEntitiesForGPU` 以找到最佳平衡点
2. **更新间隔**：增加 `updateInterval` 可提高性能，但会降低流畅度
3. **半径设置**：减小行为半径可减少计算量
4. **选择性启用**：只对需要的实体类型启用 GPU 加速

## OpenCL 驱动安装

### NVIDIA GPU
下载并安装 NVIDIA CUDA Toolkit（包含 OpenCL）

### AMD GPU
下载并安装 AMD APP SDK 或最新的 GPU 驱动

### Intel GPU
Intel GPU 驱动通常包含 OpenCL 支持

### 验证 OpenCL
```bash
# 使用 clinfo 工具检查
clinfo
```

## 故障排除

### GPU 未检测到
- 检查 OpenCL 驱动是否安装
- 查看服务器日志中的 GPU 信息
- 确认 GPU 支持 OpenCL 1.2+

### 性能问题
- 检查是否使用了正确的 GPU（而非集成显卡）
- 调整配置参数
- 监控 GPU 使用率

### 编译错误
- 确保使用 Java 17
- 检查 Gradle 版本
- 清理并重新构建：`./gradlew clean build`

## 技术细节

### GPU 计算流程
1. 收集实体数据（位置、速度等）
2. 传输到 GPU 内存
3. 执行 OpenCL 内核计算
4. 读取结果回 CPU
5. 应用到 Minecraft 实体

### OpenCL 内核
- **群体 AI 内核**：计算分离、对齐、聚合力
- **物理内核**：更新位置、速度、应用力
- **碰撞内核**：检测和响应实体间碰撞

## 扩展开发

### 添加自定义 GPU 内核

```java
String myKernel = """
    __kernel void myComputation(...) {
        // OpenCL C 代码
    }
    """;

cl_kernel kernel = gpuManager.compileKernel(myKernel, "myComputation");
```

### 创建新的 GPU 系统

参考 `SwarmAISystem` 和 `PhysicsSimulation` 的实现模式。

## 许可证

MIT License

## 贡献

欢迎提交 Issue 和 Pull Request！

## 作者

GPUAccel Team

---

**注意**：这是一个服务端 Mod，不需要客户端安装。
