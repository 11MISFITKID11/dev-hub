# DevHub 前后端分离项目 IDEA 启动指南

> 目标：在 IntelliJ IDEA 中一键启动后端（Spring Boot）+ 前端（Vite dev server），本地开发调试。
> 前提：中间件（MySQL/Redis/Milvus）继续跑在 Docker 里，只有应用进程在 IDEA 中跑。

---

## 一、启动前的准备（一次性）

### 1. 确认中间件容器在跑

```bash
export PATH="/d/DockerDesktop/resources/bin:$PATH"
docker compose -f D:/docker/middleware/docker-compose.yml up -d mysql redis milvus etcd minio
docker ps --format "{{.Names}}: {{.Status}}"   # 应看到 5 个中间件 Up（devhub-app/devhub-web 不需要起）
```

### 2. 确认本机环境

- **JDK 17+**（`java -version`；你本机 D:\java21 可用，IDEA 里也要配 17+）
- **Node 18+**（`node -v`；你本机 v24 ✓）
- **Maven**：IDEA 自带 Bundled Maven 即可

### 3. 数据库连接参数

后端默认连 `localhost` 的 3306/6379，但你的 MySQL/Redis 是 Docker 映射端口（宿主机 3307）。在 IDEA 运行配置里加环境变量（见下文第三节），或直接确认 docker-compose 的端口映射：

```bash
docker port agent-mysql    # 应输出 3306 -> 0.0.0.0:3307
docker port agent-redis
```

---

## 二、在 IDEA 中打开两个项目

IDEA 一个窗口只能一个工程，推荐两种方式（选 A 即可）：

**方式 A（推荐）：把 devhub 和 devhub-web 作为两个独立窗口**

1. `File → Open` → 选 `D:\kaifa\javaproject\devhub`（后端 Maven 工程，IDEA 自动识别 pom.xml 并 import）
2. 再 `File → Open` 新窗口 → 选 `D:\kaifa\javaproject\devhub-web`（前端，IDEA 识别 package.json）

**方式 B：一个窗口挂两个模块**

`File → New → Module from Existing Sources` → 分别导入。前后端技术栈不同，分开窗口更干净。

---

## 三、启动后端（devhub，端口 8080）

### 1. 等 Maven import 完成（右下角进度条），确认 Project Structure 里 SDK 是 17+

`File → Project Structure → Project → SDK` 选 17 或 21。

### 2. 创建运行配置

`Run → Edit Configurations → + → Spring Boot`：

| 配置项 | 值 |
|---|---|
| Name | DevHubApplication |
| Main class | com.devhub.DevHubApplication |
| Module | devhub |
| JRE | 17 |
| Active profiles | dev |
| **Environment variables** | 见下表 |

环境变量（粘贴到 Environment variables 栏，IDEA 用 `;` 分隔）：

```
MYSQL_HOST=localhost;MYSQL_PORT=3307;MYSQL_DB=devhub;REDIS_HOST=localhost;REDIS_PORT=6379;MILVUS_HOST=localhost;OPENAI_API_KEY=你的智谱Key;APP_ADMIN_TOKEN=mvp-token-2026
```

> **Milvus 端口**：compose 里 milvus 映射到宿主机 19530（默认不冲突则 `MILVUS_HOST=localhost` 即可；`docker port milvus-standalone` 确认）。
> **OpenAI API Key**：看 `D:\docker\middleware\.env` 文件里已有的 `OPENAI_API_KEY` 值，直接复制。

### 3. 启动

点绿色 ▶ 运行。看到：

```
Successfully applied N migration to schema `devhub`   ← Flyway
Started DevHubApplication in X.XXX seconds
```

浏览器访问 `http://localhost:8080/actuator/health` 返回 `{"status":"UP"}` 即成功。

> **注意**：本机 8080 跑的是 IDEA 后端；此前 Docker 里的 `agent-app`（8080 端口）和 `devhub-app`（8081）如果还在跑会端口冲突。IDEA 启动前先停掉它们：
> ```bash
> docker stop agent-app devhub-app
> ```

---

## 四、启动前端（devhub-web，端口 5173）

### 1. 首次装依赖（Terminal 面板）

在 devhub-web 窗口的 IDEA Terminal：

```bash
npm config set registry https://registry.npmmirror.com   # 国内源（已配可跳过）
npm install
```

### 2. 创建 npm 运行配置

`Run → Edit Configurations → + → npm`：

| 配置项 | 值 |
|---|---|
| Name | devhub-web dev |
| package.json | D:\kaifa\javaproject\devhub-web\package.json |
| Command | run |
| Scripts | dev |

### 3. 启动

点 ▶，Terminal 输出：

```
VITE ready in XXXms
➜  Local: http://localhost:5173/
```

浏览器打开 **http://localhost:5173** → 跳转登录页 → `admin / admin123` 登录，进入完整系统。

> **代理已配好**：`vite.config.js` 里 `/api` 代理到 `http://localhost:8080`（后端），所以本地开发跨域、JWT、SSE 全部直通，**不需要 nginx**。改前端代码保存即热更新。

---

## 五、日常开发循环

| 场景 | 操作 |
|---|---|
| 改 Java 代码 | IDEA 里改 → Ctrl+F9 重Build → 重启 DevHubApplication（或开 DevTools 热重载） |
| 改 Vue 页面 | 保存即热更新，无需任何操作 |
| 看后端日志 | IDEA Run 面板（比 docker logs 直观，可断点） |
| **断点调试** | 后端：Debug ▶ 启动，在 Controller 打断点；前端：浏览器 F12 Sources 断点 |
| 跑测试 | `mvn test`（agent-platform 侧）或 Terminal 里 `python tools/testing/test_devhub.py` |
| 结束开发 | 停两个运行配置即可；**别忘了需要演示时重新 `docker compose up -d devhub devhub-web`** |

---

## 六、常见问题

**Q1：后端启动报 `Communications link failure`**
MySQL 容器没起或端口不对。`docker ps` 确认 agent-mysql 在跑；`MYSQL_PORT=3307`（宿主机映射端口）不是 3306。

**Q2：后端报 `Unknown database 'devhub'`**
```bash
docker exec agent-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE IF NOT EXISTS devhub DEFAULT CHARACTER SET utf8mb4;"'
```
重启后端（Flyway 会建全部表）。

**Q3：前端页面白屏 / 401**
后端没起（5173 代理到 8080 失败）。先确认 `http://localhost:8080/actuator/health` 通。

**Q4：端口 8080 被占**
`netstat -ano | findstr :8080` 找到 PID → 任务管理器结束；大概率是忘了停 agent-app 容器。

**Q5：SSE 在 IDEA 本地不流式**
本地直连后端没有 nginx，天然不缓冲，不会遇到此问题；只有走 Docker nginx（8082）才需要那套 proxy_buffering 配置。

**Q6：想同时保留 Docker 版和 IDEA 版**
Docker 版前端在 8082（nginx），IDEA 后端占 8080、Vite 占 5173——三者可共存，只要 8080 不给 agent-app 和 devhub-app 抢。

---

## 七、一套命令速查

```bash
# 中间件（每次开机后）
docker compose -f D:/docker/middleware/docker-compose.yml up -d mysql redis milvus etcd minio

# 停掉可能抢 8080 的应用容器（IDEA 开发时）
docker stop agent-app devhub-app

# IDEA 内：跑 DevHubApplication（8080） + npm dev（5173）
# 访问 http://localhost:5173

# 演示恢复（交付模式）
docker compose -f D:/docker/middleware/docker-compose.yml up -d devhub devhub-web
# 访问 http://localhost:8082（Vue3） / http://localhost:8081（单文件版）
```
