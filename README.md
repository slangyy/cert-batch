# 证书批量生成工具 (Cert-Batch)

> 一款离线 Windows 桌面应用，用于管理证书模板并批量生成证书图片/PDF。

## 功能概述

### 1. 模板管理
- **上传模板**：用户上传空白证书模板图片（支持 PNG/JPG）
- **可视化标注**：在模板图片上通过鼠标点击/拖拽标注关键字占位符的位置
- **占位符属性配置**：
  - 占位符名称（如"姓名"、"日期"、"课程名称"等）
  - 位置坐标（X/Y）—— 通过拖拽自动获取
  - 字体名称（宋体、楷体等）
  - 字体大小
  - 字体颜色
  - 对齐方式（左对齐/居中/右对齐）
- **模板预览**：可预览模板效果，填入示例文字查看实际渲染结果
- **模板增删改查**：对已有模板进行管理

### 2. 批量生成证书
- **选择模板**：从已维护的模板列表中选择
- **上传 Excel**：上传包含学生信息的 Excel 文件（.xlsx）
  - 第一行为列名，需与模板中的占位符名称一一对应
  - 每一行代表一个学生的证书数据
- **数据预览**：解析 Excel 后预览数据，确认映射关系正确
- **批量生成**：根据模板和数据，批量渲染生成证书
  - 支持输出 PNG 图片
  - 支持输出 PDF 文件
- **输出目录**：用户选择输出目录，生成的证书按学生信息命名

## 技术架构

```
┌─────────────────────────────────────────────┐
│              Electron 外壳                   │
│  ┌───────────────────────────────────────┐   │
│  │          Vue3 前端 (Renderer)          │   │
│  │  - 模板可视化编辑器 (Canvas/Konva.js)  │   │
│  │  - 模板管理页面                        │   │
│  │  - 批量生成页面                        │   │
│  │  - Element Plus UI 组件库              │   │
│  └──────────────┬────────────────────────┘   │
│                 │ HTTP (localhost)            │
│  ┌──────────────▼────────────────────────┐   │
│  │       SpringBoot 后端 (子进程)         │   │
│  │  - 模板 CRUD (SQLite 存储)             │   │
│  │  - Excel 解析 (Apache POI)             │   │
│  │  - 证书图片渲染 (Java2D Graphics2D)    │   │
│  │  - PDF 生成 (iText/PDFBox)             │   │
│  └───────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

### 前端技术栈
| 技术 | 用途 |
|------|------|
| Vue 3 + Vite | 前端框架 |
| Element Plus | UI 组件库 |
| Konva.js + vue-konva | 模板可视化拖拽编辑器（Canvas） |
| Axios | HTTP 请求 |
| Electron | 桌面应用外壳 |

### 后端技术栈
| 技术 | 用途 |
|------|------|
| SpringBoot 3.x | 后端框架 |
| SQLite + MyBatis-Plus | 数据存储（轻量级，无需安装数据库） |
| Apache POI | Excel 文件解析 |
| Java2D (Graphics2D) | 在模板图片上渲染文字 |
| Apache PDFBox | PNG 转 PDF |

### 数据存储
- **SQLite 数据库**：存储模板信息和占位符配置（无需安装额外数据库，真正离线可用）
- **文件系统**：模板图片存储在本地应用数据目录

## 项目结构

```
cert-batch/
├── frontend/                    # Vue3 前端项目
│   ├── src/
│   │   ├── views/
│   │   │   ├── TemplateManage.vue    # 模板管理页面
│   │   │   ├── TemplateEditor.vue    # 模板可视化编辑器
│   │   │   └── BatchGenerate.vue     # 批量生成页面
│   │   ├── components/
│   │   │   ├── TemplateCanvas.vue    # Canvas 拖拽编辑组件
│   │   │   └── PlaceholderConfig.vue # 占位符属性配置组件
│   │   ├── api/                      # API 请求封装
│   │   ├── router/                   # 路由配置
│   │   ├── App.vue
│   │   └── main.js
│   ├── electron/
│   │   └── main.js                   # Electron 主进程
│   └── package.json
│
├── backend/                     # SpringBoot 后端项目
│   ├── src/main/java/com/certbatch/
│   │   ├── controller/
│   │   │   ├── TemplateController.java
│   │   │   └── CertificateController.java
│   │   ├── service/
│   │   │   ├── TemplateService.java
│   │   │   └── CertificateService.java
│   │   ├── mapper/
│   │   │   ├── TemplateMapper.java
│   │   │   └── PlaceholderMapper.java
│   │   ├── entity/
│   │   │   ├── Template.java
│   │   │   └── Placeholder.java
│   │   ├── config/
│   │   │   └── WebConfig.java
│   │   └── CertBatchApplication.java
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── mapper/
│   └── pom.xml
│
└── README.md
```

## 工作流程

### 模板维护流程
1. 用户点击"新建模板"，输入模板名称
2. 上传空白证书模板图片
3. 进入可视化编辑器，模板图片显示在 Canvas 上
4. 点击"添加占位符"，在图片上点击/拖拽放置占位符
5. 在右侧属性面板配置占位符的名称、字体、大小、颜色、对齐方式
6. 可拖拽调整占位符位置
7. 支持预览效果（填入示例文字渲染）
8. 保存模板

### 批量生成流程
1. 用户选择已有的证书模板
2. 上传 Excel 文件（.xlsx），系统自动解析
3. 系统显示 Excel 列名与模板占位符的对应关系，用户确认
4. 用户选择输出格式（PNG/PDF）和输出目录
5. 点击"开始生成"，后端批量渲染证书
6. 显示生成进度，完成后可打开输出目录查看

## 打包部署
- 后端打包为可执行 JAR（内嵌 Tomcat + SQLite）
- 前端通过 Electron 打包为 Windows 安装包（.exe）
- Electron 启动时自动启动 SpringBoot JAR 作为后端子进程
- 用户只需安装 JRE（或内嵌 JRE）即可运行
- **完全离线运行，无需网络连接**

## 环境要求
- JDK 17+（运行后端）
- Node.js 18+（开发前端，打包后不需要）
