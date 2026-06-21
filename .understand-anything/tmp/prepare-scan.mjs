import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const intermediate = join(root, '.understand-anything', 'intermediate');
const scan = JSON.parse(readFileSync(join(intermediate, 'scan-script-result.json'), 'utf8'));
const imports = JSON.parse(readFileSync(join(intermediate, 'import-map.json'), 'utf8'));

const scanResult = {
  ...scan,
  projectName: 'Cert-Batch \u8bc1\u4e66\u6279\u91cf\u751f\u6210\u5de5\u5177',
  projectDescription:
    '\u4e00\u4e2a\u79bb\u7ebf Windows \u684c\u9762\u5e94\u7528\uff0c\u4f7f\u7528 Electron/Vue \u524d\u7aef\u548c Spring Boot \u540e\u7aef\u7ba1\u7406\u8bc1\u4e66\u6a21\u677f\u3001\u89e3\u6790 Excel \u6570\u636e\u5e76\u6279\u91cf\u751f\u6210\u8bc1\u4e66\u56fe\u7247\u6216 PDF\u3002',
  languages: ['Java', 'JavaScript', 'Vue', 'Markdown', 'Python', 'Shell', 'Batch', 'YAML', 'XML', 'HTML'],
  frameworks: [
    'Spring Boot',
    'Maven',
    'MyBatis-Plus',
    'SQLite',
    'Apache POI',
    'Apache PDFBox',
    'Vue 3',
    'Vite',
    'Electron',
    'Element Plus',
    'Konva.js',
  ],
  importMap: imports.importMap,
};

writeFileSync(join(intermediate, 'scan-result.json'), JSON.stringify(scanResult, null, 2), 'utf8');

writeFileSync(
  join(intermediate, 'structure-input.json'),
  JSON.stringify(
    {
      projectRoot: root,
      batchFiles: scanResult.files,
      batchImportData: scanResult.importMap,
    },
    null,
    2,
  ),
  'utf8',
);
