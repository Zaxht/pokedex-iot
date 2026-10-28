import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const browserPath = process.argv[2];
const modulesPath = process.argv[3];
if (!browserPath || !modulesPath) throw new Error('Usage: node Render-Mockups.mjs <browser executable> <node_modules path with playwright>');
const require = createRequire(path.join(modulesPath, 'package.json'));
const { chromium } = require('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const directory = path.join(root, 'docs', 'mockups');
const browser = await chromium.launch({ executablePath: browserPath, headless: true });
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 1150 }, deviceScaleFactor: 2 });
  page.on('pageerror', error => errors.push(error.message));
  const views = ['login','connection','collection','list','scan','detail','unknown','friends','settings','arduino'];
  const base = pathToFileURL(path.join(directory, 'index.html')).href;
  for (const [index, view] of views.entries()) {
    await page.goto(base + '#' + view);
    await page.waitForFunction(view => document.body.dataset.view === view, view);
    await page.evaluate(async () => { await document.fonts.ready; await Promise.all([...document.images].map(img => img.decode())); });
    const overflow = await page.locator('.screen').evaluate(el => el.scrollWidth > el.clientWidth + 2);
    if (overflow) throw new Error('Horizontal overflow in ' + view);
    await page.locator('#phone').screenshot({ path: path.join(directory, `${String(index+1).padStart(2,'0')}-${view}.png`) });
  }
  await page.goto(base + '#login');
  await page.getByRole('button', { name: 'Ingresar', exact:true }).click();
  await page.waitForFunction(() => document.body.dataset.view === 'collection');
  await page.getByRole('button', { name:'Ver lista', exact:true }).click();
  await page.waitForFunction(() => document.body.dataset.view === 'list');
  await page.goto(base + '#arduino');
  await page.getByRole('slider', { name:'Perilla del Arduino' }).fill('32');
  await page.getByRole('button', { name:'Cerrar', exact:true }).click();
  if (await page.locator('.volume-value').innerText() !== '32') throw new Error('Volume did not persist between mockup views');
  await page.goto(base + '#friends');
  await page.getByRole('button', { name:'Aceptar', exact:true }).click();
  if (await page.locator('.shellhead .yellow').evaluate(el => el.classList.contains('on'))) throw new Error('Notification stayed on');
  if (errors.length) throw new Error(errors.join('\n'));
  console.log('10 mockups rendered; navigation, volume, notification and overflow checks passed.');
} finally { await browser.close(); }
