import { execSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';

const root = process.cwd();
const ua = join(root, '.understand-anything');
const intermediate = join(ua, 'intermediate');
const scan = JSON.parse(readFileSync(join(intermediate, 'scan-result.json'), 'utf8'));
const structure = JSON.parse(readFileSync(join(intermediate, 'structure-result.json'), 'utf8'));
const now = new Date().toISOString();
const gitCommitHash = execSync('git rev-parse HEAD', { cwd: root, encoding: 'utf8' }).trim();

const structureByPath = new Map(structure.results.map(item => [item.path, item]));
const fileIdByPath = new Map();
const nodes = [];
const edges = [];
const endpointNodes = [];
const layerBuckets = new Map();

const fileLevelTypes = new Set(['file', 'config', 'document', 'service', 'pipeline', 'table', 'schema', 'resource', 'endpoint']);

function kebab(value) {
  return value
    .replace(/[\\/]+/g, '-')
    .replace(/[^a-zA-Z0-9\u4e00-\u9fa5]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase();
}

function unique(items) {
  return [...new Set(items.filter(Boolean))];
}

function nodeTypeFor(file) {
  if (file.fileCategory === 'config') return 'config';
  if (file.fileCategory === 'docs') return 'document';
  return 'file';
}

function fileIdFor(file) {
  const type = nodeTypeFor(file);
  return `${type}:${file.path}`;
}

function complexity(lines) {
  if (lines >= 400) return 'high';
  if (lines >= 120) return 'medium';
  return 'low';
}

function readText(path) {
  const abs = join(root, path);
  if (!existsSync(abs)) return '';
  try {
    return readFileSync(abs, 'utf8');
  } catch {
    return '';
  }
}

function tagsForFile(file) {
  const p = file.path;
  const tags = [file.language, file.fileCategory];
  if (p.startsWith('frontend/electron/')) tags.push('Electron', '桌面壳');
  if (p.startsWith('frontend/src/views/')) tags.push('Vue 页面');
  if (p.startsWith('frontend/src/api/')) tags.push('API 客户端');
  if (p.includes('/controller/')) tags.push('REST 控制器', 'HTTP API');
  if (p.includes('/service/')) tags.push('业务服务');
  if (p.includes('/mapper/')) tags.push('MyBatis', '数据访问');
  if (p.includes('/entity/')) tags.push('领域实体');
  if (p.includes('/config/')) tags.push('后端配置');
  if (p.endsWith('pom.xml')) tags.push('Maven', 'Spring Boot');
  if (p.endsWith('package.json')) tags.push('npm', 'Vite', 'Electron');
  if (p.endsWith('.md')) tags.push('文档');
  if (p.startsWith('scripts/')) tags.push('构建脚本');
  return unique(tags);
}

function summaryForFile(file, s) {
  const p = file.path;
  const name = basename(p);
  if (p === 'backend/pom.xml') return '后端 Maven 构建清单，定义 Spring Boot、MyBatis-Plus、SQLite、Apache POI 和 PDFBox 等核心依赖。';
  if (p === 'backend/src/main/resources/application.yml') return 'Spring Boot 运行配置，集中声明服务端口、上传限制、SQLite 数据源和 MyBatis-Plus 行为。';
  if (p === 'backend/src/main/resources/start.sh') return '后端启动脚本，用于从打包环境启动 Spring Boot 服务。';
  if (p.endsWith('CertBatchApplication.java')) return 'Spring Boot 应用入口，设置无头图形模式并启动证书批量生成后端服务。';
  if (p.endsWith('/common/R.java')) return '统一 API 响应包装类，为前后端接口返回成功、失败和数据载荷结构。';
  if (p.includes('/controller/')) return `${name} 暴露后端 REST 接口，连接前端请求与模板、证书生成或授权业务服务。`;
  if (p.includes('/service/CertificateService.java')) return '证书生成核心服务，负责读取 Excel 数据、渲染模板图片并生成 PNG/PDF 输出。';
  if (p.includes('/service/LicenseService.java')) return '授权服务，负责机器码、激活码校验、授权记录保存和授权状态判断。';
  if (p.includes('/service/TemplateService.java')) return '模板业务服务，维护证书模板、占位符配置、模板图片路径和模板预览数据。';
  if (p.includes('/service/')) return `${name} 承载后端业务逻辑，并协调实体、Mapper 与控制器之间的数据流。`;
  if (p.includes('/mapper/')) return `${name} 是 MyBatis-Plus 数据访问接口，负责实体对象与 SQLite 表之间的持久化操作。`;
  if (p.includes('/entity/License.java')) return '授权实体，描述机器码、激活码、过期时间和授权状态等持久化字段。';
  if (p.includes('/entity/Placeholder.java')) return '模板占位符实体，描述字段名、坐标、字体、颜色和对齐方式。';
  if (p.includes('/entity/Template.java')) return '证书模板实体，保存模板名称、图片路径、尺寸和创建更新时间。';
  if (p.includes('/config/DatabaseInitializer.java')) return '数据库初始化组件，负责创建或迁移 SQLite 业务表结构。';
  if (p.includes('/config/DataDirInitializer.java')) return '应用数据目录初始化组件，为本地模板、输出和数据库文件准备目录。';
  if (p.includes('/config/LicenseInterceptor.java')) return '授权拦截器，在受保护 API 进入业务逻辑前校验当前机器授权状态。';
  if (p.includes('/config/WebConfig.java')) return 'Web MVC 配置，注册跨域、静态资源映射和授权拦截链。';
  if (p.includes('/config/')) return `${name} 提供后端运行期配置，支撑数据库、Web、授权或审计行为。`;
  if (p === 'frontend/electron/main.js') return 'Electron 主进程入口，创建桌面窗口、启动/管理后端进程并处理应用生命周期。';
  if (p === 'frontend/electron/preload.js') return 'Electron 预加载脚本，为渲染进程暴露受控的桌面能力边界。';
  if (p === 'frontend/src/api/index.js') return '前端 API 封装层，统一调用模板、证书生成和授权相关的后端 HTTP 接口。';
  if (p === 'frontend/src/main.js') return 'Vue 渲染进程入口，挂载应用、路由和 Element Plus UI 组件库。';
  if (p === 'frontend/src/router/index.js') return '前端路由配置，组织模板管理、模板编辑、批量生成和授权激活页面。';
  if (p.endsWith('ActivateView.vue')) return '授权激活页面，展示机器码、录入激活码并调用授权接口完成离线激活。';
  if (p.endsWith('BatchGenerate.vue')) return '批量生成页面，选择模板、上传 Excel、预览数据并触发证书图片/PDF 批量输出。';
  if (p.endsWith('TemplateEditor.vue')) return '模板可视化编辑页面，基于画布标注占位符位置并配置字体、颜色和对齐方式。';
  if (p.endsWith('TemplateManage.vue')) return '模板管理页面，提供模板上传、列表查看、编辑、删除和预览入口。';
  if (p.endsWith('App.vue')) return '前端根组件，承载整体布局、导航和主要页面出口。';
  if (p === 'frontend/package.json') return '前端 npm 清单，定义 Vue/Vite/Electron 依赖、开发命令和 Windows 打包配置。';
  if (p === 'frontend/vite.config.js') return 'Vite 构建配置，定义 Vue 插件、开发服务器代理和前端构建行为。';
  if (p === 'README.md') return '项目总览文档，说明证书批量生成工具的功能、架构、技术栈和运行方式。';
  if (p === 'GUIDE.md') return '使用指南文档，面向用户说明模板管理、Excel 上传和批量生成流程。';
  if (p === 'LICENSE-GUIDE.md') return '授权说明文档，描述离线授权、机器码、激活码和授权管理流程。';
  if (p.includes('generate_contract_docx.py')) return '协议文档生成脚本，用 Python 生成证书生成及系统服务协议的 Word 文档。';
  if (p.endsWith('.md')) return '项目 Markdown 文档，补充说明业务协议、用户流程或交付说明。';
  if (p.startsWith('scripts/')) return `${name} 是 Windows 批处理脚本，用于准备 JRE 或执行前后端整体构建。`;
  if (p.endsWith('index.html')) return '前端 HTML 入口，提供 Vue 应用挂载点。';
  const detail = s?.metrics?.functionCount ? `，包含 ${s.metrics.functionCount} 个可识别函数` : '';
  return `${name} 是 ${file.language} ${file.fileCategory} 文件${detail}，属于证书批量生成工具的一部分。`;
}

function layerForPath(path, type) {
  if (path.startsWith('frontend/electron/')) return 'layer:desktop-shell';
  if (path.startsWith('frontend/src/')) return 'layer:frontend-renderer';
  if (path.includes('/controller/')) return 'layer:backend-api';
  if (path.startsWith('backend/src/main/java/')) return 'layer:backend-domain-data';
  if (path.startsWith('backend/src/main/resources/') || path.endsWith('pom.xml') || path.endsWith('package.json') || path.endsWith('vite.config.js') || path.endsWith('index.html')) return 'layer:config-build';
  if (type === 'document' || path.startsWith('docs/') || path.startsWith('scripts/') || path.endsWith('.bat')) return 'layer:docs-scripts';
  return 'layer:config-build';
}

function addNode(node) {
  if (!nodes.some(existing => existing.id === node.id)) nodes.push(node);
}

function addEdge(edge) {
  if (!edge.source || !edge.target || edge.source === edge.target) return;
  const id = `${edge.source}|${edge.target}|${edge.type}`;
  if (!edges.some(existing => `${existing.source}|${existing.target}|${existing.type}` === id)) {
    edges.push({ weight: weightFor(edge.type), ...edge });
  }
}

function weightFor(type) {
  return {
    contains: 1,
    imports: 0.7,
    configures: 0.6,
    depends_on: 0.6,
    documents: 0.5,
    routes: 0.5,
    calls: 0.8,
    related: 0.5,
  }[type] ?? 0.5;
}

function addModule(id, name, summary, tags) {
  addNode({ id, type: 'module', name, summary, tags });
}

addModule('module:frontend', '前端桌面客户端', 'Vue 渲染进程与 Electron 主进程共同组成离线桌面客户端。', ['前端', 'Electron', 'Vue']);
addModule('module:backend', '后端业务服务', 'Spring Boot 后端提供模板管理、授权校验和证书生成能力。', ['后端', 'Spring Boot']);
addModule('module:docs', '文档与交付脚本', '用户指南、授权说明、协议文档和构建脚本支撑交付与使用。', ['文档', '脚本']);
addNode({ id: 'concept:offline-certificate-generation', type: 'concept', name: '离线证书批量生成流程', summary: '用户在桌面端维护模板和 Excel 数据，后端渲染证书并输出图片或 PDF。', tags: ['业务流程', '证书生成'] });

for (const file of scan.files) {
  const s = structureByPath.get(file.path);
  const type = nodeTypeFor(file);
  const id = fileIdFor(file);
  fileIdByPath.set(file.path, id);
  const node = {
    id,
    type,
    name: basename(file.path),
    filePath: file.path,
    summary: summaryForFile(file, s),
    tags: tagsForFile(file),
    language: file.language,
    complexity: complexity(file.sizeLines),
    loc: file.sizeLines,
  };
  addNode(node);
  const layerId = layerForPath(file.path, type);
  if (!layerBuckets.has(layerId)) layerBuckets.set(layerId, []);
  layerBuckets.get(layerId).push(id);

  if (file.path.startsWith('frontend/')) addEdge({ source: 'module:frontend', target: id, type: 'contains' });
  else if (file.path.startsWith('backend/')) addEdge({ source: 'module:backend', target: id, type: 'contains' });
  else addEdge({ source: 'module:docs', target: id, type: 'contains' });

  for (const cls of s?.classes ?? []) {
    const clsId = `class:${file.path}:${cls.name}`;
    addNode({
      id: clsId,
      type: 'class',
      name: cls.name,
      filePath: file.path,
      summary: `${cls.name} 是 ${basename(file.path)} 中定义的主要类，封装该文件的核心职责。`,
      tags: unique(['class', file.language, ...tagsForFile(file).slice(0, 2)]),
      startLine: cls.startLine,
      endLine: cls.endLine,
    });
    addEdge({ source: id, target: clsId, type: 'contains' });
  }

  for (const fn of s?.functions ?? []) {
    const fnId = `function:${file.path}:${fn.name}`;
    addNode({
      id: fnId,
      type: 'function',
      name: fn.name,
      filePath: file.path,
      summary: `${fn.name} 是 ${basename(file.path)} 中的函数或方法，参与该文件的业务处理流程。`,
      tags: unique(['function', file.language, ...tagsForFile(file).slice(0, 2)]),
      startLine: fn.startLine,
      endLine: fn.endLine,
    });
    addEdge({ source: id, target: fnId, type: 'contains' });
  }

  if (file.path.includes('/controller/')) {
    const text = readText(file.path);
    const base = (text.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? '').replace(/\/$/, '');
    const routeRegex = /@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)(?:\("([^"]*)"\))?/g;
    let match;
    while ((match = routeRegex.exec(text))) {
      if (match[0].startsWith('@RequestMapping') && match.index < 500) continue;
      const method = match[1].replace('Mapping', '').toUpperCase() || 'REQUEST';
      const pathPart = match[2] ?? '';
      const routePath = `${base}${pathPart.startsWith('/') ? pathPart : pathPart ? `/${pathPart}` : ''}` || base || '/';
      const endpointId = `endpoint:${file.path}:${method}:${routePath}`;
      const line = text.slice(0, match.index).split('\n').length;
      endpointNodes.push(endpointId);
      addNode({
        id: endpointId,
        type: 'endpoint',
        name: `${method} ${routePath}`,
        filePath: file.path,
        summary: `后端接口 ${method} ${routePath}，由 ${basename(file.path)} 处理并转交业务服务。`,
        tags: ['HTTP API', method, '后端端点'],
        startLine: line,
      });
      addEdge({ source: id, target: endpointId, type: 'contains' });
      addEdge({ source: endpointId, target: id, type: 'routes' });
    }
  }
}

for (const [sourcePath, targets] of Object.entries(scan.importMap ?? {})) {
  const source = fileIdByPath.get(sourcePath);
  for (const targetPath of targets ?? []) {
    const target = fileIdByPath.get(targetPath);
    if (source && target) addEdge({ source, target, type: 'imports' });
  }
}

const ids = Object.fromEntries([...fileIdByPath.entries()].map(([path, id]) => [path, id]));
for (const [source, targets] of [
  ['frontend/src/router/index.js', ['frontend/src/views/TemplateManage.vue', 'frontend/src/views/TemplateEditor.vue', 'frontend/src/views/BatchGenerate.vue', 'frontend/src/views/ActivateView.vue']],
  ['frontend/src/api/index.js', ['backend/src/main/java/com/certbatch/controller/TemplateController.java', 'backend/src/main/java/com/certbatch/controller/CertificateController.java', 'backend/src/main/java/com/certbatch/controller/LicenseController.java']],
  ['frontend/electron/main.js', ['backend/pom.xml', 'backend/src/main/resources/start.sh']],
  ['backend/src/main/resources/application.yml', ['backend/src/main/java/com/certbatch/config/DatabaseInitializer.java', 'backend/src/main/java/com/certbatch/config/WebConfig.java']],
  ['backend/pom.xml', ['backend/src/main/java/com/certbatch/CertBatchApplication.java']],
  ['frontend/package.json', ['frontend/electron/main.js', 'frontend/src/main.js']],
]) {
  for (const target of targets) if (ids[source] && ids[target]) addEdge({ source: ids[source], target: ids[target], type: source.includes('router') ? 'routes' : source.endsWith('.json') || source.endsWith('.yml') || source.endsWith('.xml') ? 'configures' : 'depends_on' });
}

for (const doc of ['README.md', 'GUIDE.md', 'LICENSE-GUIDE.md', 'docs/证书生成及系统服务协议.md']) {
  const docId = ids[doc];
  if (!docId) continue;
  addEdge({ source: docId, target: 'module:frontend', type: 'documents' });
  addEdge({ source: docId, target: 'module:backend', type: 'documents' });
  addEdge({ source: docId, target: 'concept:offline-certificate-generation', type: 'documents' });
}

for (const endpointId of endpointNodes) {
  if (ids['frontend/src/api/index.js']) addEdge({ source: ids['frontend/src/api/index.js'], target: endpointId, type: 'routes' });
}

const layerInfo = {
  'layer:desktop-shell': ['桌面壳层', 'Electron 主进程、预加载脚本和桌面生命周期管理。'],
  'layer:frontend-renderer': ['前端渲染层', 'Vue 页面、路由、API 封装和用户交互流程。'],
  'layer:backend-api': ['后端 API 层', 'Spring MVC 控制器和 HTTP 端点入口。'],
  'layer:backend-domain-data': ['后端业务与数据层', '业务服务、实体、Mapper、授权和数据库初始化逻辑。'],
  'layer:config-build': ['配置与构建层', 'Maven、npm、Vite、Spring 配置和资源入口。'],
  'layer:docs-scripts': ['文档与脚本层', '用户文档、授权说明、协议生成和构建准备脚本。'],
};

const layers = Object.entries(layerInfo).map(([id, [name, description]]) => ({
  id,
  name,
  description,
  nodeIds: unique(layerBuckets.get(id) ?? []),
})).filter(layer => layer.nodeIds.length > 0);

const tour = [
  {
    order: 1,
    title: '项目整体入口',
    description: '先阅读 README 和 GUIDE，理解证书批量生成工具的目标、技术栈和典型使用路径。',
    nodeIds: unique([ids['README.md'], ids['GUIDE.md'], 'concept:offline-certificate-generation']),
  },
  {
    order: 2,
    title: '桌面应用启动链路',
    description: '查看 Electron 主进程如何创建窗口、启动后端，再由 Vue 入口挂载渲染进程。',
    nodeIds: unique([ids['frontend/electron/main.js'], ids['frontend/electron/preload.js'], ids['frontend/src/main.js'], ids['frontend/src/App.vue']]),
  },
  {
    order: 3,
    title: '模板维护与可视化编辑',
    description: '沿着模板管理、模板编辑和前端 API 封装，理解模板上传、占位符配置和预览流程。',
    nodeIds: unique([ids['frontend/src/views/TemplateManage.vue'], ids['frontend/src/views/TemplateEditor.vue'], ids['frontend/src/api/index.js'], ids['backend/src/main/java/com/certbatch/controller/TemplateController.java'], ids['backend/src/main/java/com/certbatch/service/TemplateService.java']]),
  },
  {
    order: 4,
    title: '批量生成证书',
    description: '从批量生成页面进入后端证书服务，跟踪 Excel 解析、模板渲染和 PNG/PDF 输出职责。',
    nodeIds: unique([ids['frontend/src/views/BatchGenerate.vue'], ids['backend/src/main/java/com/certbatch/controller/CertificateController.java'], ids['backend/src/main/java/com/certbatch/service/CertificateService.java']]),
  },
  {
    order: 5,
    title: '授权与本地持久化',
    description: '查看授权页面、授权控制器、拦截器和实体/Mapper，理解离线激活与 SQLite 保存方式。',
    nodeIds: unique([ids['frontend/src/views/ActivateView.vue'], ids['backend/src/main/java/com/certbatch/controller/LicenseController.java'], ids['backend/src/main/java/com/certbatch/service/LicenseService.java'], ids['backend/src/main/java/com/certbatch/config/LicenseInterceptor.java'], ids['backend/src/main/java/com/certbatch/entity/License.java']]),
  },
  {
    order: 6,
    title: '构建与交付',
    description: '最后看 Maven、npm 和脚本文件，了解前后端构建、JRE 准备和桌面安装包交付。',
    nodeIds: unique([ids['backend/pom.xml'], ids['frontend/package.json'], ids['scripts/build-all.bat'], ids['scripts/prepare-jre.bat']]),
  },
];

const graph = {
  version: '1.0.0',
  project: {
    name: scan.projectName,
    languages: scan.languages,
    frameworks: scan.frameworks,
    description: scan.projectDescription,
    analyzedAt: now,
    gitCommitHash,
  },
  nodes,
  edges,
  layers,
  tour,
};

const nodeIds = new Set(nodes.map(n => n.id));
const issues = [];
const warnings = [];
for (const edge of edges) {
  if (!nodeIds.has(edge.source)) issues.push(`Edge source missing: ${edge.source}`);
  if (!nodeIds.has(edge.target)) issues.push(`Edge target missing: ${edge.target}`);
}
const assigned = new Set();
for (const layer of layers) {
  for (const id of layer.nodeIds) {
    if (!nodeIds.has(id)) issues.push(`Layer ${layer.id} refs missing node ${id}`);
    assigned.add(id);
  }
}
for (const node of nodes) {
  if (fileLevelTypes.has(node.type) && node.type !== 'endpoint' && !assigned.has(node.id)) {
    issues.push(`File node not assigned to layer: ${node.id}`);
  }
  if (!node.summary || !node.tags?.length) issues.push(`Node missing summary/tags: ${node.id}`);
}
for (const step of tour) {
  for (const id of step.nodeIds) if (!nodeIds.has(id)) issues.push(`Tour step ${step.order} refs missing node ${id}`);
}
const touched = new Set(edges.flatMap(edge => [edge.source, edge.target]));
for (const node of nodes) {
  if (!touched.has(node.id)) warnings.push(`Node has no edges: ${node.id}`);
}

const stats = {
  totalNodes: nodes.length,
  totalEdges: edges.length,
  totalLayers: layers.length,
  tourSteps: tour.length,
  nodeTypes: nodes.reduce((acc, node) => ({ ...acc, [node.type]: (acc[node.type] ?? 0) + 1 }), {}),
  edgeTypes: edges.reduce((acc, edge) => ({ ...acc, [edge.type]: (acc[edge.type] ?? 0) + 1 }), {}),
};

writeFileSync(join(intermediate, 'assembled-graph.json'), JSON.stringify(graph, null, 2), 'utf8');
writeFileSync(join(ua, 'knowledge-graph.json'), JSON.stringify(graph, null, 2), 'utf8');
writeFileSync(join(intermediate, 'review.json'), JSON.stringify({ issues, warnings, stats }, null, 2), 'utf8');
writeFileSync(
  join(intermediate, 'fingerprint-input.json'),
  JSON.stringify({ projectRoot: root, sourceFilePaths: scan.files.map(file => file.path), gitCommitHash }, null, 2),
  'utf8',
);
writeFileSync(
  join(ua, 'meta.json'),
  JSON.stringify(
    {
      lastAnalyzedAt: now,
      gitCommitHash,
      version: '1.0.0',
      analyzedFiles: scan.files.length,
    },
    null,
    2,
  ),
  'utf8',
);

console.log(JSON.stringify({ issues: issues.length, warnings: warnings.length, stats }, null, 2));
