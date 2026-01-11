# Agents 使用说明（给 Jules 的环境搭建提示）

下面的说明面向运行在短寿命、安全隔离虚拟机（VM）或容器中的自动化 agent（如 Jules）。目标是让 agent 能在最小干预下：克隆本仓库、准备依赖、构建并运行快速测试。

重要基线
- 假设运行环境可访问互联网以克隆仓库和下载构建依赖。
- 使用临时用户与短生命周期 VM，避免在宿主系统保存凭据。
- 需要安装或可自动安装的工具：`git`, `curl`/`wget`, JDK 17（或项目指定的 JDK 版本）、Docker（可选，若使用容器）

首选快速流程（无持久化，仅用于一次性任务）
1. 克隆仓库：

```bash
git clone https://github.com/qq2656739379/Mc-Gpu-entity-acceleration.git
cd Mc-Gpu-entity-acceleration
```

2. 使用项目提供的脚本快速准备环境：

- Linux VM（Debian/Ubuntu 等）：
  - 运行 `scripts/setup.sh`（示例：`REPO=... ./scripts/setup.sh`）。脚本会尝试安装 OpenJDK 17、git，并用仓库自带的 `gradlew` 构建项目。

- Windows VM：
  - 以管理员权限运行 `.	asks
un`（或直接执行 `scripts/setup.ps1`），脚本使用 `winget` 安装 Git 与 Temurin JDK 17，然后运行 `gradlew.bat build`。

- 容器化（更可复用/可缓存）：
  - 使用仓库中的 `Dockerfile` 构建镜像：

```bash
docker build -t gpu-entity-accel --build-arg REPO=https://github.com/qq2656739379/Mc-Gpu-entity-acceleration.git .
docker run --rm -it gpu-entity-accel bash
```

安全与资源限制建议
- 运行时限制：为 VM/容器设置 CPU 和内存配额（例如 2 vCPU、4–8 GB RAM），并设置磁盘配额以避免持久化大量构建缓存。
- 网络：构建过程需要访问 Maven/Gradle 仓库，确保短期网络访问，完成后可以关闭外网。
- 凭据：不要在脚本中包含私人凭据；若需要访问私有依赖或私有仓库，请通过临时凭据或 artifact 代理处理。

快速验证（示例命令）
- 构建：

```bash
./gradlew build --no-daemon
```

- 成果检查：

```bash
ls build/libs
```

常见问题与提示
- 如果 `gradlew` 不可执行，请运行 `chmod +x gradlew`（Linux/macOS）。
- 若构建因 JDK 版本不匹配失败，请安装项目期望的 JDK 版本并重试。
- 若需要运行单元测试或集成测试，可在 `./gradlew` 上附加相应任务。

如果 Jules 需要更多复现步骤（例如运行一个小的 smoke test、下载特定 GPU 驱动或 OpenCL SDK），请告诉我需要的测试命令或目标，我会把这些步骤加入 `agents.md` 或相应脚本中。
