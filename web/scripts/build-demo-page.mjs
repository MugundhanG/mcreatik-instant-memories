// Turns the demo build (dist-demo) into one self-contained HTML page (dist-demo/demo.html).
import fs from 'node:fs'
import path from 'node:path'

const dir = 'dist-demo'
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8')
const css = [...html.matchAll(/<link rel="stylesheet"[^>]*href="\.\/([^"]+)"/g)].map((m) => fs.readFileSync(path.join(dir, m[1]), 'utf8'))
const js = [...html.matchAll(/<script type="module"[^>]*src="\.\/([^"]+)"/g)].map((m) => fs.readFileSync(path.join(dir, m[1]), 'utf8'))
if (js.length !== 1) throw new Error(`Expected one script, found ${js.length}`)
const safeJs = js[0].replaceAll('</script', '<\\/script')
const page = `<title>McreatiK Live Gallery Demo</title>
<meta name="robots" content="noindex">
<style>${css.join('\n')}</style>
<div id="root"></div>
<script type="module">${safeJs}</script>
`
fs.writeFileSync(path.join(dir, 'demo.html'), page)
console.log(`demo.html: ${(page.length / 1024).toFixed(0)} KB`)
