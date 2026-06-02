# 开发调试 & 打包部署指南

---

## 一、环境要求

| 工具 | 版本 | 用途 | 备注 |
|------|------|------|------|
| JDK | 17+ | 后端编译运行 | 需配置 `JAVA_HOME` |
| Maven | 3.8+ | 后端依赖管理 | 需配置到 `PATH` |
| Node.js | 18+ | 前端开发构建 | 需配置到 `PATH` |
| npm | 9+ | 前端包管理 | 随 Node.js 安装 |

可使用以下命令验证环境：

```bash
java -version       # 应输出 17+
mvn -v               # 应输出 3.8+
node -v              # 应输出 v18+
npm -v               # 应输出 9+
```

---

## 二、项目结构

```
cert-batch/
├── backend/                    # SpringBoot 后端（端口 18080）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/certbatch/
│       │   ├── CertBatchApplication.java     # 启动类
│       │   ├── common/R.java                 # 统一响应
│       │   ├── config/                       # 配置（CORS、建表、元数据填充）
│       │   ├── controller/                   # REST 接口
│       │   ├── entity/                       # 数据实体
│       │   ├── mapper/                       # MyBatis-Plus Mapper
│       │   └── service/                      # 业务逻辑
│       └── resources/application.yml         # 配置文件
│
├── frontend/                   # Vue3 + Electron 前端（端口 5173）
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   ├── electron/
│   │   ├── main.js             # Electron 主进程
│   │   └── preload.js          # 预加载脚本
│   └── src/
│       ├── main.js             # Vue 入口
│       ├── App.vue             # 主布局
│       ├── router/index.js     # 路由
│       ├── api/index.js        # API 封装
│       └── views/              # 页面组件
│
├── jre/                        # 内嵌 JRE（打包时生成，不提交 Git）
├── scripts/
│   ├── prepare-jre.bat         # 生成精简 JRE
│   └── build-all.bat           # 一键打包
└── README.md
```

---

## 三、开发调试

### 3.1 首次准备

```bash
# 1. 克隆项目后，安装前端依赖
cd frontend
npm install

# 2. 后端无需额外安装，Maven 会自动下载依赖
```

### 3.2 启动后端

**方式一：命令行**
```bash
cd backend
mvn spring-boot:run
```

**方式二：IDE（推荐）**
- 用 IntelliJ IDEA 打开 `backend` 目录
- 找到 `CertBatchApplication.java`，右键 → Run
- 后端默认运行在 `http://localhost:18080`

启动成功标志：看到 `Started CertBatchApplication` 日志。

> **数据存储**：开发模式下，SQLite 数据库文件和模板图片存放在 `backend/cert-batch-data/` 目录下。

### 3.3 启动前端

**方式一：纯浏览器开发（推荐日常开发）**
```bash
cd frontend
npm run dev
```
- 浏览器打开 `http://localhost:5173`
- Vite 已配置代理，`/api/*` 请求自动转发到后端 `localhost:18080`
- 支持热更新（HMR），修改代码后浏览器自动刷新

**方式二：Electron 桌面窗口开发**
```bash
cd frontend
npm run electron:dev
```
- 会同时启动 Vite 开发服务器和 Electron 窗口
- 自动打开 DevTools 方便调试
- **注意**：此模式下需要先手动启动后端（3.2 步）

### 3.4 开发调试流程总结

```
终端1                     终端2                     浏览器/Electron
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│  cd backend      │    │  cd frontend     │    │                  │
│  mvn spring-     │    │  npm run dev     │    │  localhost:5173   │
│    boot:run      │    │                  │    │                  │
│                  │    │  (或)            │    │  (或)            │
│  端口: 18080     │    │  npm run         │    │  Electron 窗口   │
│                  │    │    electron:dev  │    │                  │
└──────────────────┘    └──────────────────┘    └──────────────────┘
      后端                    前端                    界面
```

### 3.5 调试技巧

| 场景 | 方法 |
|------|------|
| 后端接口调试 | 使用浏览器 DevTools Network 面板，或 Postman/Apifox 直接请求 `localhost:18080/api/*` |
| 前端页面调试 | 浏览器 F12 打开 DevTools，Vue Devtools 插件查看组件状态 |
| 数据库查看 | 使用 [DB Browser for SQLite](https://sqlitebrowser.org/) 打开 `backend/cert-batch-data/certbatch.db` |
| 后端日志 | 控制台默认输出 SQL 日志（`StdOutImpl`），生产环境可在 `application.yml` 关闭 |
| Canvas 编辑器调试 | 在 `TemplateEditor.vue` 中，通过 Vue Devtools 查看 `placeholders` 数组数据 |

### 3.6 后端 API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/template/list` | 模板列表 |
| GET | `/api/template/{id}` | 模板详情 |
| POST | `/api/template/create` | 创建模板（multipart: name + image） |
| PUT | `/api/template/{id}/name` | 重命名模板 |
| DELETE | `/api/template/{id}` | 删除模板 |
| GET | `/api/template/{id}/image` | 获取模板图片 |
| GET | `/api/template/{id}/placeholders` | 获取占位符列表 |
| POST | `/api/template/{id}/placeholders` | 保存占位符（全量替换） |
| POST | `/api/certificate/parse-excel` | 解析 Excel 文件 |
| POST | `/api/certificate/batch-generate` | 批量生成证书 |
| POST | `/api/certificate/preview` | 预览证书效果 |

---

## 四、打包部署

### 4.1 一键打包（推荐）

直接双击运行：
```bash
scripts\build-all.bat
```

该脚本自动完成以下三步，生成的安装包在 `frontend/dist-electron/` 目录下。

---

### 4.2 分步打包（手动）

#### 第一步：打包后端 JAR

```bash
cd backend
mvn clean package -DskipTests
```

产物：`backend/target/cert-batch-backend-1.0.0.jar`

#### 第二步：生成内嵌 JRE

```bash
scripts\prepare-jre.bat
```

> 该脚本使用 `jlink` 从本地 JDK 裁剪精简 JRE（约 40-50MB），仅保留必需模块：
> `java.base, java.desktop, java.logging, java.management, java.naming, java.net.http, java.security.jgss, java.sql, java.xml, jdk.unsupported, jdk.crypto.ec`

产物：`jre/` 目录

#### 第三步：打包 Electron 安装包

```bash
cd frontend
npm install          # 如果未安装过依赖
npm run electron:build
```

产物：`frontend/dist-electron/` 目录下的 `.exe` 安装文件

### 4.3 安装包内容结构

打包后的安装包内部结构：

```
安装目录/
├── 证书批量生成工具.exe        # 主程序
├── resources/
│   ├── app/                   # Vue 前端构建产物 + Electron 代码
│   ├── backend/
│   │   └── cert-batch-backend.jar   # SpringBoot 后端
│   └── jre/                   # 内嵌精简 JRE
│       └── bin/java.exe
```

### 4.4 运行机制

```
用户双击 EXE
    │
    ▼
Electron 启动
    │
    ├── 1. 查找内嵌 JRE (resources/jre/bin/java.exe)
    │
    ├── 2. 用 JRE 启动 SpringBoot JAR (子进程, 端口 18080)
    │
    ├── 3. 等待后端就绪 (最多 30 秒轮询)
    │
    └── 4. 加载 Vue 前端页面
```

### 4.5 用户数据位置

安装后，用户数据存放在：
```
C:\Users\<用户名>\AppData\Roaming\cert-batch-frontend\cert-batch-data\
├── certbatch.db          # SQLite 数据库
└── templates/            # 模板图片文件
```

> 卸载应用不会删除此目录，用户数据会保留。

---

## 五、常见问题

### Q1: 后端启动报错 `端口 18080 被占用`
```bash
# Windows 查找占用进程
netstat -ano | findstr 18080
# 结束进程
taskkill /PID <进程ID> /F
```

### Q2: 前端 `npm install` 慢
配置淘宝镜像：
```bash
npm config set registry https://registry.npmmirror.com
```

### Q3: Electron 下载慢
配置 Electron 镜像：
```bash
npm config set electron_mirror https://npmmirror.com/mirrors/electron/
```

### Q4: 打包时 `electron-builder` 下载 winCodeSign / nsis 慢
配置镜像：
```bash
npm config set electron_builder_binaries_mirror https://npmmirror.com/mirrors/electron-builder-binaries/
```

### Q5: 打包后双击 EXE 提示"后端启动失败"
- 检查 `jre/` 目录是否完整（运行 `scripts\prepare-jre.bat` 重新生成）
- 确保 `backend/target/cert-batch-backend-1.0.0.jar` 存在
- 检查端口 18080 是否被占用

### Q6: 生成的证书中文显示为方块
确保服务器/用户电脑上安装了模板中使用的字体（如宋体、楷体等）。Windows 系统一般自带这些中文字体。

### Q7: 开发模式下如何清除数据重新开始
删除 `backend/cert-batch-data/` 目录即可，重启后端会自动重建数据库。
