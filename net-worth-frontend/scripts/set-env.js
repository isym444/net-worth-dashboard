const fs = require('fs');
const path = require('path');

const apiBaseUrl = process.env.API_BASE_URL || 'https://net-worth-service-nt7d.onrender.com';

const outPath = path.join(__dirname, '../src/environments/environment.prod.ts');
fs.writeFileSync(
  outPath,
  `export const environment = {
  production: true,
  apiBaseUrl: '${apiBaseUrl}',
};
`,
);

console.log(`Wrote ${outPath} with apiBaseUrl=${apiBaseUrl}`);
